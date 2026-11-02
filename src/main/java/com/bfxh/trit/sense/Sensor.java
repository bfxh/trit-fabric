package com.bfxh.trit.sense;

import com.bfxh.trit.core.Angles;
import com.bfxh.trit.core.Tri;
import com.bfxh.trit.core.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/**
 * 感知层：直接从游戏世界读状态。
 *
 * <p>相比数据包版，这里拿到的是<strong>连续值</strong>而不是 8 方向 3 档的量化结果，
 * 也没有聊天栏文本解析带来的延迟。代价是要自己做性能预算：
 * 资源扫描是 O(r³) 的方块读取，做成每 {@link #RESOURCE_PERIOD} 拍一次并缓存。
 */
public final class Sensor {

    /** 敌对生物扫描半径（格）。 */
    public static final float ENEMY_RADIUS = 24f;
    /** 资源扫描半径（格）。 */
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
    private long enemyAtTick = NEVER;

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
            float[] rel = relativeAngles(p, targetXyz[0], targetXyz[1], targetXyz[2]);
            w.yawToTarget = rel[0];
            w.pitchToTarget = rel[1];
        }

        scanResources(level, p, tick);
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
    }

    // ------------------------------------------------------------------

    private void scanEnemies(ClientLevel level, LocalPlayer p, long tick) {
        if (enemyAtTick != NEVER && tick - enemyAtTick < ENEMY_PERIOD) return;
        enemyAtTick = tick;

        double best = Double.MAX_VALUE;
        int count = 0;
        int bestId = -1;
        double[] bestXyz = null;

        double r2 = (double) ENEMY_RADIUS * ENEMY_RADIUS;
        for (Entity e : level.entitiesForRendering()) {
            if (e == p) continue;
            if (!(e instanceof LivingEntity le)) continue;
            if (!(e instanceof Enemy)) continue;
            if (le.isDeadOrDying()) continue;
            double d2 = e.distanceToSqr(p);
            if (d2 > r2) continue;
            count++;
            if (d2 < best) {
                best = d2;
                bestId = e.getId();
                bestXyz = new double[]{e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ()};
            }
        }

        if (best == Double.MAX_VALUE) {
            enemyDist = null;
            enemyCount = 0;
            targetId = -1;
            targetXyz = null;
        } else {
            enemyDist = (float) Math.sqrt(best);
            enemyCount = count;
            targetId = bestId;
            targetXyz = bestXyz;
        }
    }

    private void scanResources(ClientLevel level, LocalPlayer p, long tick) {
        if (resAtTick != NEVER && tick - resAtTick < RESOURCE_PERIOD) return;
        resAtTick = tick;

        int cx = p.blockPosition().getX();
        int cy = p.blockPosition().getY();
        int cz = p.blockPosition().getZ();
        int r = (int) RESOURCE_RADIUS;

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
        w.hazard = hazardTri;
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
}
