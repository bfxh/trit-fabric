package com.bfxh.trit.sense;

import com.bfxh.trit.core.Angles;
import com.bfxh.trit.core.Tri;
import com.bfxh.trit.core.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 感知层：直接从游戏世界读状态。
 *
 * <p>相比数据包版，这里拿到的是<strong>连续值</strong>而不是 8 方向 3 档的量化结果，
 * 也没有聊天栏文本解析带来的延迟。代价是要自己做性能预算：
 * 资源扫描是 O(r³) 的方块读取，做成每 {@link #RESOURCE_PERIOD} 拍一次并缓存。
 */
public final class Sensor {

    /** 敌对生物扫描半径默认值（格）；实际每拍按渲染距离缩放。 */
    public static final float ENEMY_RADIUS = 24f;
    /** 资源扫描半径默认值（格）；实际每拍按渲染距离缩放。 */
    public static final float RESOURCE_RADIUS = 14f;
    /** 危险方块扫描半径（格）。 */
    private static final int HAZARD_RADIUS = 2;

    /** 敌人每 2 拍扫一次。 */
    private static final int ENEMY_PERIOD = 2;
    /** 资源每 10 拍扫一次（0.5 秒）。 */
    private static final int RESOURCE_PERIOD = 10;

    // ---- 方块分类表。用反射按字段名建立，避免逐个写常量导致版本漂移 ----
    private static final Set<Block> LOGS = new HashSet<>();
    private static final Set<Block> ORES = new HashSet<>();
    private static final Set<Block> HAZARDS = new HashSet<>();
    /** 可挖开的「软/普通」方块（卡住时开路用）：土、草、沙、砂砾、雪、叶子、粘土、泥等。 */
    private static final Set<Block> SOFT = new HashSet<>();

    /** 当前实际扫描半径（按渲染距离缩放）。 */
    private float enemyRadius = ENEMY_RADIUS;
    private float resourceRadius = RESOURCE_RADIUS;

    /** 跟随目标：玩家在环盘菜单里用准星指定的实体 id；null = 还没指定，用默认（最近玩家）。 */
    private Integer forcedFollowId;

    /** 目标上一拍坐标，用于预测其速度并提前量瞄准（打移动目标）。 */
    private final Map<Integer, double[]> lastTargetPos = new HashMap<>();
    private final Map<Integer, Long> lastTargetTick = new HashMap<>();

    /**
     * 分类表要<strong>懒构建</strong>。
     *
     * <p>放在静态块里的话，加载 {@code Sensor} 类就会触发 {@code Blocks} 的初始化，
     * 而 mod 可能在游戏注册表还没准备好时就被引到这个类 —— 那样崩在启动阶段，
     * 连日志都来不及看。改成第一次真正感知时才建表。
     */
    private static boolean tablesBuilt;

    private static synchronized void buildTables() {
        if (tablesBuilt) return;
        tablesBuilt = true;
        for (Field f : Blocks.class.getFields()) {
            if (!Block.class.isAssignableFrom(f.getType())) continue;
            Object v;
            try {
                v = f.get(null);
            } catch (Exception e) {
                continue;
            }
            if (!(v instanceof Block b)) continue;
            String n = f.getName();
            if (n.endsWith("_LOG") || n.endsWith("_STEM") || n.equals("BAMBOO_BLOCK")) {
                LOGS.add(b);
            } else if (n.endsWith("_ORE")) {
                ORES.add(b);
            } else if (n.equals("LAVA") || n.equals("FIRE") || n.equals("SOUL_FIRE")
                    || n.equals("MAGMA_BLOCK") || n.equals("CACTUS") || n.equals("WITHER_ROSE")
                    || n.equals("SWEET_BERRY_BUSH") || n.equals("CAMPFIRE") || n.equals("SOUL_CAMPFIRE")) {
                HAZARDS.add(b);
            } else if (n.endsWith("_LEAVES") || n.endsWith("_DIRT") || n.equals("GRASS_BLOCK")
                    || n.equals("DIRT") || n.equals("COARSE_DIRT") || n.equals("ROOTED_DIRT")
                    || n.equals("SAND") || n.equals("RED_SAND") || n.equals("GRAVEL") || n.equals("SNOW")
                    || n.equals("SNOW_BLOCK") || n.equals("CLAY") || n.equals("SOUL_SAND")
                    || n.equals("SOUL_SOIL") || n.equals("MOSS_BLOCK") || n.equals("MUD")
                    || n.equals("PODZOL") || n.equals("MYCELIUM") || n.equals("FARMLAND")
                    || n.equals("DIRT_PATH") || n.equals("RED_SAND") || n.equals("SCAFFOLDING")
                    || n.equals("HAY_BLOCK") || n.equals("SPONGE") || n.equals("WET_SPONGE")) {
                SOFT.add(b);
            }
        }
    }

    // ---- 缓存 ----
    /**
     * 「上次扫描的拍号」初值。
     *
     * <p>不能写 {@code Long.MIN_VALUE}：{@code tick - Long.MIN_VALUE} 会溢出成负数，
     * 让 {@code tick - last < PERIOD} 永远成立，扫描就一次都不会跑。
     * 取一个足够小的负数即可。
     */
    private static final long NEVER = -1_000_000L;

    private Float woodDist;
    private Float oreDist;
    private int[] resPos;
    private Float resDist;
    private long resAtTick = NEVER;
    /** 选定当前采集目标时的拍号，用于「够不到就换目标」的超时。 */
    private long resPickedAt;
    /** 选定目标时它离玩家多远。 */
    private float resDistAtPick;
    private Float enemyDist;
    private Integer enemyCount;
    private int targetId = -1;
    private double[] targetXyz;
    /** 战斗目标的速度（格/拍），用于提前量瞄准（预测）。 */
    private double[] targetVel;
    private long enemyAtTick = NEVER;

    /** 判断一个实体对玩家是否构成「威胁」（不止于 Enemy 子类）。 */
    private static boolean isHostile(Entity e, LocalPlayer p) {
        if (e instanceof Enemy) return true;
        if (e instanceof Mob m && m.getTarget() == p) return true;
        if (p.getLastHurtByMob() == e) return true;
        return false;
    }

    /** 上一拍坐标，用于卡住判定。 */
    private double lastX, lastY, lastZ;
    private int stillTicks;
    private boolean hasLast;

    /** 采集目标上一次的位置，避免每拍重新选目标导致来回横跳。 */
    private int[] stickyRes;

    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    /**
     * 采一拍感知。
     *
     * @param mc     游戏实例
     * @param tick   全局拍号
     * @param armed  AI 是否已接管
     */
    public WorldView sense(Minecraft mc, long tick, boolean armed) {
        buildTables();

        // 感知半径跟随「你能看到多远」：渲染距离越大，能察觉的敌人/资源越远。
        // 这样「屏幕多大看到多大」，而不是写死的 24/14 格。
        int rd = 12;
        try {
            Integer v = mc.options.renderDistance().get();
            if (v != null) rd = v;
        } catch (Exception ignore) {
            // 某些版本 OptionInstance.get 可能抛；用默认值兜底
        }
        enemyRadius = Math.min(160f, Math.max(ENEMY_RADIUS, rd * 16f));
        resourceRadius = Math.min(56f, Math.max(RESOURCE_RADIUS, rd * 8f));

        WorldView w = new WorldView();
        w.tick = tick;

        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            // 没进世界：一切都未知。不能当成「没有敌人 / 没有墙」。
            w.threat = Tri.ZERO;
            w.hazard = Tri.ZERO;
            return w;
        }

        w.dead = p.isDeadOrDying();
        float maxHp = p.getMaxHealth();
        w.hp = maxHp > 0f ? p.getHealth() / maxHp : null;
        w.food = p.getFoodData().getFoodLevel();
        w.pos = new double[]{p.getX(), p.getY(), p.getZ()};
        w.pitch = p.getXRot();
        w.yaw = p.getYRot();
        w.humanTakeover = !armed;

        // 26.3 的客户端 LevelData 不再暴露 dayTime，用天空暗度判断是否夜晚。
        // 白天 0，夜晚 4（满月）~ 更高，取 3 作分界避免黄昏误判。
        w.night = level.getSkyDarken() >= 3;

        // 卡住判定：连续 20 拍位移小于 0.02 格
        if (hasLast) {
            double d = Math.abs(p.getX() - lastX) + Math.abs(p.getY() - lastY) + Math.abs(p.getZ() - lastZ);
            if (d < 0.02) stillTicks++;
            else stillTicks = 0;
        }
        lastX = p.getX();
        lastY = p.getY();
        lastZ = p.getZ();
        hasLast = true;
        w.stuck = stillTicks > 20;

        scanEnemies(level, p, tick);
        w.dist = enemyDist;
        w.enemies = enemyCount;
        w.targetId = targetId;
        // 有明确扫描结果：能确定有没有敌人。扫不到就保持未知。
        w.threat = enemyCount == null ? Tri.ZERO : (enemyCount > 0 ? Tri.POS : Tri.NEG);
        if (targetId >= 0 && targetXyz != null) {
            // 预测：朝目标「未来位置」瞄准（提前量），移动目标不用再描后脑勺
            double tx = targetXyz[0], ty = targetXyz[1], tz = targetXyz[2];
            if (targetVel != null && w.dist != null) {
                float lead = Math.min(16f, w.dist / 2.5f);
                tx += targetVel[0] * lead;
                ty += targetVel[1] * lead;
                tz += targetVel[2] * lead;
            }
            float[] rel = relativeAngles(p, tx, ty, tz);
            w.yawToTarget = rel[0];
            w.pitchToTarget = rel[1];
        }

        scanResources(level, p, tick);
        // 每拍校验采集目标是否还在：挖掉了立刻失效并强制重扫，
        // 否则下一拍还对着空气硬挖（表现为「树挖完了还在搞 / 傻站着」）。
        // 只在「上一拍确实有目标、这拍没了」时才重扫，无目标时不会每拍空扫。
        if (stickyRes != null && !isResource(level, stickyRes)) {
            stickyRes = null;
            resAtTick = NEVER;
            scanResources(level, p, tick);
        }
        w.wood = woodDist;
        w.ore = oreDist;
        w.resourcePos = resPos;
        w.resourceDist = resDist;
        if (resPos != null) {
            float[] rel = relativeAngles(p, resPos[0] + 0.5, resPos[1] + 0.5, resPos[2] + 0.5);
            w.yawToResource = rel[0];
            w.pitchToResource = rel[1];
        }

        scanHazard(level, p);
        scanWallAndStep(level, p);
        attachTerrainTo(w);
        scanInteractionAndGear(mc, level, p, w);
        scanFollowTarget(level, p, w);

        // 准星所指方块的角度与可挖性（卡住开路用）
        if (w.frontPos != null) {
            float[] rel = relativeAngles(p, w.frontPos[0] + 0.5, w.frontPos[1] + 0.5, w.frontPos[2] + 0.5);
            w.yawToFront = rel[0];
            w.pitchToFront = rel[1];
            String f = w.blkFront;
            w.canDigFront = f != null && !f.equals("air") && !f.equals("hazard")
                    && !f.equals("fluid") && !f.equals("log") && !f.equals("ore");
        }

        // 准星指向的方块
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = bhr.getBlockPos();
            w.frontPos = new int[]{bp.getX(), bp.getY(), bp.getZ()};
            w.frontFace = bhr.getDirection().ordinal();
            w.blkFront = classify(level, bp);
        } else {
            w.blkFront = "air";
        }
        BlockPos below = p.blockPosition().below();
        w.blkBelow = classify(level, below);

        return w;
    }

    /** 接管时清空缓存，避免用上一次接管时的陈旧目标。 */
    public void reset() {
        woodDist = null;
        oreDist = null;
        resPos = null;
        resDist = null;
        stickyRes = null;
        resAtTick = NEVER;
        resPickedAt = 0;
        resDistAtPick = 0f;
        enemyDist = null;
        enemyCount = null;
        targetId = -1;
        targetXyz = null;
        enemyAtTick = NEVER;
        stillTicks = 0;
        hasLast = false;
        lastTargetPos.clear();
        lastTargetTick.clear();
    }

    /**
     * 由环盘菜单在选定「跟随」时调用：把准星所指的实体设为固定跟随目标。
     * 传 null 表示取消指定，改回「跟随最近玩家」。
     */
    public void setForcedFollow(Integer entityId) {
        forcedFollowId = entityId;
    }

    // ------------------------------------------------------------------

    private void scanEnemies(ClientLevel level, LocalPlayer p, long tick) {
        if (enemyAtTick != NEVER && tick - enemyAtTick < ENEMY_PERIOD) return;
        enemyAtTick = tick;

        double best = Double.MAX_VALUE;
        int count = 0;
        int bestId = -1;
        double[] bestXyz = null;

        double r2 = (double) enemyRadius * enemyRadius;
        for (Entity e : level.entitiesForRendering()) {
            if (e == p) continue;
            if (!(e instanceof LivingEntity le) || le.isDeadOrDying()) continue;
            if (!isHostile(e, p)) continue;
            double d2 = e.distanceToSqr(p);
            if (d2 > r2) continue;
            count++;
            if (d2 < best) {
                best = d2;
                bestId = e.getId();
                bestXyz = new double[]{e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ()};
            }
        }

        if (bestId < 0) {
            enemyDist = null;
            enemyCount = 0;
            targetId = -1;
            targetXyz = null;
            targetVel = null;
            return;
        }
        enemyDist = (float) Math.sqrt(best);
        enemyCount = count;
        targetId = bestId;

        // 速度估计 → 提前量（预测）瞄准移动目标
        double[] prev = lastTargetPos.get(bestId);
        long prevT = lastTargetTick.getOrDefault(bestId, tick);
        if (prev != null) {
            long dt = Math.max(1, tick - prevT);
            targetVel = new double[]{
                    (bestXyz[0] - prev[0]) / dt,
                    (bestXyz[1] - prev[1]) / dt,
                    (bestXyz[2] - prev[2]) / dt
            };
        } else {
            targetVel = new double[]{0, 0, 0};
        }
        lastTargetPos.put(bestId, bestXyz.clone());
        lastTargetTick.put(bestId, tick);
        targetXyz = bestXyz;
    }

    private void scanResources(ClientLevel level, LocalPlayer p, long tick) {
        if (resAtTick != NEVER && tick - resAtTick < RESOURCE_PERIOD) return;
        resAtTick = tick;

        int cx = p.blockPosition().getX();
        int cy = p.blockPosition().getY();
        int cz = p.blockPosition().getZ();
        int r = (int) resourceRadius;

        double bestWood = Double.MAX_VALUE;
        int[] bestWoodPos = null;
        double bestOre = Double.MAX_VALUE;
        int[] bestOrePos = null;

        for (int y = cy - 4; y <= cy + 5; y++) {
            if (y < level.getMinY() || y >= level.getMaxY()) continue;
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    probe.set(x, y, z);
                    BlockState st = level.getBlockState(probe);
                    if (st.isAir()) continue;
                    Block b = st.getBlock();
                    double d = (x + 0.5 - p.getX()) * (x + 0.5 - p.getX())
                            + (y + 0.5 - p.getY()) * (y + 0.5 - p.getY())
                            + (z + 0.5 - p.getZ()) * (z + 0.5 - p.getZ());
                    if (LOGS.contains(b)) {
                        if (d < bestWood) {
                            bestWood = d;
                            bestWoodPos = new int[]{x, y, z};
                        }
                    } else if (ORES.contains(b)) {
                        if (d < bestOre) {
                            bestOre = d;
                            bestOrePos = new int[]{x, y, z};
                        }
                    }
                }
            }
        }

        woodDist = bestWoodPos == null ? null : (float) Math.sqrt(bestWood);
        oreDist = bestOrePos == null ? null : (float) Math.sqrt(bestOre);

        // ---- 选目标：粘性优先，避免每拍换目标导致 AI 来回横跳 ----

        // 1) 上一次的目标还在吗？（可能被挖掉了，也可能跑出了扫描范围）
        if (stickyRes != null && !isResource(level, stickyRes)) {
            stickyRes = null;
        }

        // 2) 超时放弃：选了 10 秒还没接近，说明这个目标多半够不到
        //    （隔着基岩、在地下深处、或者在悬崖对面），死磕下去就是一直撞墙。
        if (stickyRes != null && tick - resPickedAt > 200) {
            float d = distanceTo(p, stickyRes);
            if (d > resDistAtPick - 0.5f) {
                stickyRes = null;
            } else {
                resPickedAt = tick;
                resDistAtPick = d;
            }
        }

        // 3) 没有粘性目标就选新的：有木头先砍树（前期最缺），否则挖矿
        if (stickyRes == null) {
            stickyRes = bestWoodPos != null ? bestWoodPos : bestOrePos;
            resPickedAt = tick;
            resDistAtPick = stickyRes == null ? 0f : distanceTo(p, stickyRes);
        }

        resPos = stickyRes;
        resDist = resPos == null ? null : distanceTo(p, resPos);
    }

    private boolean isResource(ClientLevel level, int[] pos) {
        if (pos == null) return false;
        probe.set(pos[0], pos[1], pos[2]);
        Block b = level.getBlockState(probe).getBlock();
        return LOGS.contains(b) || ORES.contains(b);
    }

    /** isResource 的 BlockPos 重载，给射线遮挡检测用。 */
    private boolean isResource(ClientLevel level, BlockPos pos) {
        if (pos == null) return false;
        Block b = level.getBlockState(pos).getBlock();
        return LOGS.contains(b) || ORES.contains(b);
    }

    private static float distanceTo(LocalPlayer p, int[] pos) {
        double dx = pos[0] + 0.5 - p.getX();
        double dy = pos[1] + 0.5 - p.getY();
        double dz = pos[2] + 0.5 - p.getZ();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void scanHazard(ClientLevel level, LocalPlayer p) {
        BlockPos c = p.blockPosition();
        boolean found = false;
        for (int dy = -1; dy <= 1 && !found; dy++) {
            for (int dx = -HAZARD_RADIUS; dx <= HAZARD_RADIUS && !found; dx++) {
                for (int dz = -HAZARD_RADIUS; dz <= HAZARD_RADIUS && !found; dz++) {
                    probe.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (HAZARDS.contains(level.getBlockState(probe).getBlock())) found = true;
                }
            }
        }
        hazardTri = found ? Tri.POS : Tri.NEG;
    }

    /** scanHazard 的结果，由 sense() 读走。 */
    Tri hazardTri = Tri.ZERO;

    private void scanWallAndStep(ClientLevel level, LocalPlayer p) {
        float yaw = p.getYRot();
        int mask = 0;
        BlockPos base = p.blockPosition();
        for (int i = 0; i < 8; i++) {
            // 0 = 正前，顺时针每 45°
            double a = Math.toRadians(yaw + i * 45.0);
            int dx = (int) Math.round(-Math.sin(a));
            int dz = (int) Math.round(Math.cos(a));
            boolean blocked = false;
            for (int dy = 0; dy <= 1; dy++) {
                probe.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                BlockState st = level.getBlockState(probe);
                if (!st.isAir() && st.isSolid()) {
                    blocked = true;
                    break;
                }
            }
            if (blocked) mask |= (1 << i);
        }
        wallMask = mask;

        // 正前 1 格：能跳上去的台阶 vs 会摔下去的坑
        double a = Math.toRadians(yaw);
        int dx = (int) Math.round(-Math.sin(a));
        int dz = (int) Math.round(Math.cos(a));
        probe.set(base.getX() + dx, base.getY(), base.getZ() + dz);
        boolean solidAtFoot = !level.getBlockState(probe).isAir();
        probe.set(base.getX() + dx, base.getY() + 1, base.getZ() + dz);
        boolean solidAtHead = !level.getBlockState(probe).isAir();
        stepAhead = solidAtFoot && !solidAtHead;

        // 坑：脚下两格内都没有支撑
        boolean support = false;
        for (int dy = 1; dy <= 3; dy++) {
            probe.set(base.getX() + dx, base.getY() - dy, base.getZ() + dz);
            if (!level.getBlockState(probe).isAir()) {
                support = true;
                break;
            }
        }
        pitAhead = !support;
    }

    int wallMask;
    boolean stepAhead;
    boolean pitAhead;

    /** 把 scanWallAndStep / scanHazard 的结果写进 WorldView。 */
    private void attachTerrainTo(WorldView w) {
        w.wall = wallMask;
        w.step = stepAhead;
        w.pit = pitAhead;
        w.gapAhead = pitAhead;
        w.hazard = hazardTri;
    }

    /**
     * 玩家交互 + 装备 + 地形细节。
     *
     * <p>这一块专门服务于「灵性操作」：
     * <ul>
     *   <li>{@code humanActing}：玩家自己按了移动/攻击/使用键，或正在破坏方块。
     *       为真时 AI 必须全部让权，绝不抢控制权（这是「不干扰你挖矿」的硬保证）。</li>
     *   <li>{@code resourceOccluded} / {@code targetOccluded}：射线步进判断视线被墙挡住，
     *       挡住了就别傻站着硬挖/硬射，改为接近或绕行。</li>
     *   <li>装备扫描：有没有弓、箭、可放置方块，决定能用远程还是只能近战、能不能填坑。</li>
     * </ul>
     */
    private void scanInteractionAndGear(Minecraft mc, ClientLevel level, LocalPlayer p, WorldView w) {
        // 玩家自身操作：物理按键或正在破坏方块 → 让权。
        // 26.3 的蹲下键是 keyShift（没有独立 keyCrouch），漏掉它就会「你一蹲下 AI 还在抢」。
        boolean key = mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
                || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown()
                || mc.options.keyJump.isDown() || mc.options.keyAttack.isDown()
                || mc.options.keyUse.isDown() || mc.options.keyShift.isDown();
        boolean breaking = mc.gameMode != null && mc.gameMode.isDestroying();
        w.humanActing = key || breaking;

        // 采集目标遮挡：射线打到「非资源方块」即视为被挡
        if (resPos != null) {
            Vec3 from = eyeVec(p);
            Vec3 to = new Vec3(resPos[0] + 0.5, resPos[1] + 0.5, resPos[2] + 0.5);
            w.resourceOccluded = lineBlocked(level, from, to, bp -> isResource(level, bp));
        } else {
            w.resourceOccluded = null;
        }

        // 战斗目标遮挡：射线打到任意实心方块即视为被挡
        if (targetId >= 0 && targetXyz != null) {
            Vec3 from = eyeVec(p);
            Vec3 to = new Vec3(targetXyz[0], targetXyz[1], targetXyz[2]);
            w.targetOccluded = lineBlocked(level, from, to, null);
        } else {
            w.targetOccluded = null;
        }

        // 装备扫描
        boolean bow = false, ammo = false, blocks = false;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            Item it = s.getItem();
            if (it instanceof BowItem) {
                bow = true;
                if (hasArrow(p)) ammo = true;
            } else if (it instanceof CrossbowItem) {
                bow = true;
                if (CrossbowItem.isCharged(s) || hasArrow(p)) ammo = true;
            } else if (it instanceof BlockItem) {
                blocks = true;
            }
        }
        w.hasBow = bow;
        w.hasAmmo = ammo;
        w.hasBlocks = blocks;

        w.footPos = new int[]{p.blockPosition().getX(), p.blockPosition().getY() - 1, p.blockPosition().getZ()};
        w.onGround = p.onGround();
        w.inLiquid = !level.getFluidState(p.blockPosition()).isEmpty()
                || !level.getFluidState(p.blockPosition().below()).isEmpty();
    }

    /** 背包里有没有箭。crossbow 上膛也算有弹，但 isCharged 在上面单独判断了。 */
    private static boolean hasArrow(LocalPlayer p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).getItem() instanceof ArrowItem) return true;
        }
        return false;
    }

    private static Vec3 eyeVec(LocalPlayer p) {
        return new Vec3(p.getX(), p.getEyeY(), p.getZ());
    }

    /**
     * 从 from 到 to 步进，遇到第一个「实心且不被 ignore 接受」的方块即返回 true。
     *
     * @param ignore 返回 true 的位置跳过（用于资源：整棵树都算视线通畅）。
     */
    private static boolean lineBlocked(ClientLevel level, Vec3 from, Vec3 to, Predicate<BlockPos> ignore) {
        double dist = from.distanceTo(to);
        int steps = (int) (dist / 0.25) + 1;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            Vec3 pnt = from.lerp(to, t);
            BlockPos bp = BlockPos.containing(pnt);
            if (ignore != null && ignore.test(bp)) continue;
            BlockState st = level.getBlockState(bp);
            if (!st.isAir() && st.isSolid()) return true;
        }
        return false;
    }

    /**
     * 目标相对当前朝向的角度差。
     *
     * @return {@code [yaw差, pitch差]}，单位度。yaw 差为正 = 目标在右边；
     *         pitch 差为正 = 目标在下方。
     */
    private static float[] relativeAngles(LocalPlayer p, double tx, double ty, double tz) {
        return new float[]{
                Angles.yawDelta(p.getYRot(), p.getX(), p.getZ(), tx, tz),
                Angles.pitchDelta(p.getXRot(), p.getEyeY(), p.getX(), p.getZ(), tx, ty, tz)
        };
    }

    /**
     * 把方块分成执行层够用的语义类别。
     *
     * <p>只分到「能不能挖、挖了会不会死」这个粒度就够了，再细反而要维护一张
     * 随版本漂移的方块表。
     */
    private String classify(ClientLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) return "air";
        Block b = st.getBlock();
        if (HAZARDS.contains(b)) return "hazard";
        if (LOGS.contains(b)) return "log";
        if (ORES.contains(b)) return "ore";
        if (b == Blocks.WATER) return "fluid";
        return "solid";
    }

    // ------------------------------------------------------------------
    // 跟随目标：环盘菜单选「跟随」时，若准星指着某个实体就固定跟它；
    // 否则默认跟最近的「其他玩家」，再退一步跟最近的非敌意活体（动物等）。
    // ------------------------------------------------------------------
    private void scanFollowTarget(ClientLevel level, LocalPlayer p, WorldView w) {
        w.followId = -1;
        w.followXyz = null;
        w.followYaw = null;
        w.followPitch = null;

        Entity pick = null;
        if (forcedFollowId != null) {
            Entity e = level.getEntity(forcedFollowId);
            if (e != null && e.isAlive() && e != p) {
                pick = e;
            } else {
                forcedFollowId = null; // 目标没了，退回默认
            }
        }
        if (pick == null) {
            double best = Double.MAX_VALUE;
            for (AbstractClientPlayer pl : level.players()) {
                if (pl == p) continue;
                double d2 = pl.distanceToSqr(p);
                if (d2 < best) {
                    best = d2;
                    pick = pl;
                }
            }
        }
        if (pick == null) {
            double best = Double.MAX_VALUE;
            for (Entity e : level.entitiesForRendering()) {
                if (e == p || !(e instanceof LivingEntity le) || le.isDeadOrDying() || e instanceof Enemy) continue;
                double d2 = e.distanceToSqr(p);
                if (d2 < best) {
                    best = d2;
                    pick = e;
                }
            }
        }
        if (pick == null) return;

        double[] xyz = new double[]{pick.getX(), pick.getY() + pick.getBbHeight() * 0.5, pick.getZ()};
        w.followId = pick.getId();
        w.followXyz = xyz;
        float[] rel = relativeAngles(p, xyz[0], xyz[1], xyz[2]);
        w.followYaw = rel[0];
        w.followPitch = rel[1];
    }
}
