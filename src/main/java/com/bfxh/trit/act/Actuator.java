package com.bfxh.trit.act;

import com.bfxh.trit.core.Channel;
import com.bfxh.trit.core.Decision;
import com.bfxh.trit.core.Phase;
import com.bfxh.trit.core.Tri;
import com.bfxh.trit.core.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 执行层：把裁决结果写进游戏。
 *
 * <p>分三块：
 * <ol>
 *   <li>移动 —— 写 {@link AiInput}，替换 {@code player.input}。</li>
 *   <li>视角 —— 直接改 {@code yRot / xRot}。速率而不是瞬时对齐，
 *       这样「三值通道」的语义（左转 / 不动 / 右转）才能保留。</li>
 *   <li>左右键 —— 走 {@code gameMode}，等价于玩家真实点击，服务端认可。</li>
 * </ol>
 */
public final class Actuator {

    /** 视角每拍转动的角度（度）。20 TPS 下约 240°/秒。 */
    private static final float YAW_RATE = 12f;
    private static final float PITCH_RATE = 8f;
    /** 近战攻击距离（格）。 */
    private static final float MELEE_RANGE = 3.2f;
    /** 采集的瞄准容差（度），超出就先转过去。 */
    private static final float AIM_TOLERANCE = 25f;

    private final AiInput input = new AiInput();
    private ClientInput saved = null;
    private boolean attached;

    /** 攻击冷却拍数，避免每拍都点。 */
    private int attackCooldown;
    /** 上一次挖掘的目标，用来判断是不是换了个方块。 */
    private int[] miningPos;
    /** 是否由我们发起了「按住右键」（进食）。交还控制权前必须松开。 */
    private boolean holdingUse;

    public boolean isAttached() {
        return attached;
    }

    /** 接管：保存玩家原输入源，换成 AI 的。 */
    public void attach(LocalPlayer p) {
        if (!attached) {
            saved = p.input;
            p.input = input;
            attached = true;
        }
    }

    /**
     * 交还：恢复玩家原输入源并全部松手。
     *
     * <p>{@code p} 允许为 null（玩家已退出世界），此时只保证松手。
     */
    public void detach(LocalPlayer p) {
        input.release();
        if (attached && p != null && saved != null) {
            p.input = saved;
        }
        attached = false;
        saved = null;
        stopMining(null);
        holdingUse = false;
        attackCooldown = 0;
    }

    /**
     * 应用一拍裁决。
     *
     * @param cfg 视角开关等参数
     */
    public void apply(Minecraft mc, Decision d, WorldView w, boolean moveEnabled, boolean attackEnabled) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        // 输入源可能被游戏重置（重生、切世界），每拍确认一次
        if (p.input != input) {
            saved = p.input;
            p.input = input;
        }

        if (d.phase == Phase.OFF) {
            input.release();
            stopMining(mc);
            releaseUse(mc, p);
            return;
        }

        Tri fwd = d.dirs[Channel.FORWARD.index()];
        Tri strafe = d.dirs[Channel.STRAFE.index()];
        Tri yawCh = d.dirs[Channel.YAW.index()];
        Tri pitchCh = d.dirs[Channel.PITCH.index()];
        Tri jump = d.dirs[Channel.JUMP.index()];
        Tri use = d.dirs[Channel.USE.index()];
        Tri attack = d.dirs[Channel.ATTACK.index()];

        boolean sprint = fwd == Tri.POS && d.phase != Phase.RETREAT && w.hp != null && w.hp > 0.5f;

        if (moveEnabled) {
            input.set(
                    fwd == Tri.POS,
                    fwd == Tri.NEG,
                    strafe == Tri.POS,
                    strafe == Tri.NEG,
                    jump == Tri.POS,
                    false,
                    sprint
            );
        } else {
            input.release();
        }

        // 视角：固定速率会在接近目标时过冲，然后来回摆。
        // 有精确角度差时按差值的比例收速，接近了就慢下来。
        Float guide = null;
        if (d.phase == Phase.ENGAGE && w.targetId >= 0) guide = w.yawToTarget;
        else if (d.attackHold) guide = w.yawToResource;
        if (yawCh != Tri.ZERO) {
            float step = YAW_RATE;
            if (guide != null) step = Math.min(YAW_RATE, Math.max(1.5f, Math.abs(guide) * 0.35f));
            p.setYRot(p.getYRot() + yawCh.toInt() * step);
        }
        if (pitchCh != Tri.ZERO) {
            float step = PITCH_RATE;
            if (guide != null) step = Math.min(PITCH_RATE, Math.max(1.2f, Math.abs(guide) * 0.3f));
            float np = p.getXRot() + pitchCh.toInt() * step;
            p.setXRot(Math.max(-89f, Math.min(89f, np)));
        }

        if (d.phase == Phase.RECOVER) {
            input.release();
            if (d.recoverClick) {
                p.respawn();
            }
            stopMining(mc);
            releaseUse(mc, p);
            return;
        }

        // 右键 / 食用。只对食物下嘴 —— 主手拿着方块时 useItem 会把方块放下去。
        if (use == Tri.POS) {
            if (!p.isUsingItem() && ensureFoodInHand(p)) {
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                holdingUse = true;
            }
        } else {
            releaseUse(mc, p);
        }

        // 左键
        if (!attackEnabled || attack == Tri.ZERO) {
            stopMining(mc);
            return;
        }

        if (d.attackHold) {
            mine(mc, w);
        } else {
            stopMining(mc);
            strike(mc, w);
        }
    }

    // ------------------------------------------------------------------

    private void strike(Minecraft mc, WorldView w) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return;
        if (attackCooldown > 0) {
            attackCooldown--;
            return;
        }
        if (w.targetId < 0 || w.dist == null) return;
        if (w.dist > MELEE_RANGE) return;
        Entity e = level.getEntity(w.targetId);
        if (e == null || e.isRemoved()) return;
        // gameMode.attack 内部已经处理挥手动画，26.3 的 swing 需要额外参数，不再单独调
        mc.gameMode.attack(p, e);
        // 近战冷却大约 0.5 秒，留 10 拍
        attackCooldown = 10;
    }

    /** 持续挖掘（砍树 / 挖矿）。 */
    private void mine(Minecraft mc, WorldView w) {
        LocalPlayer p = mc.player;
        MultiPlayerGameMode gm = mc.gameMode;
        if (p == null || gm == null) return;

        int[] target = w.resourcePos;
        if (target == null) {
            stopMining(mc);
            return;
        }
        // 瞄准到位了才下镐，否则先把视角转过去
        if (w.yawToResource != null && Math.abs(w.yawToResource) > AIM_TOLERANCE) {
            stopMining(mc);
            return;
        }

        BlockPos pos = new BlockPos(target[0], target[1], target[2]);
        if (miningPos == null || miningPos[0] != target[0] || miningPos[1] != target[1] || miningPos[2] != target[2]) {
            stopMining(mc);
            miningPos = target.clone();
        }

        Direction dir = faceTowards(p, pos);
        if (!gm.isDestroying()) {
            gm.startDestroyBlock(pos, dir);
        } else {
            gm.continueDestroyBlock(pos, dir);
        }
    }

    private void stopMining(Minecraft mc) {
        if (miningPos != null) {
            if (mc != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            miningPos = null;
        }
    }

    /**
     * 松开右键。
     *
     * <p>不松的话玩家会一直卡在「使用物品」状态：进食进度条停在那儿，
     * 交还控制权后玩家自己按左右键也会失效。
     */
    private void releaseUse(Minecraft mc, LocalPlayer p) {
        if (holdingUse) {
            holdingUse = false;
            if (mc != null && mc.gameMode != null && p != null) {
                mc.gameMode.releaseUsingItem(p);
            }
        }
    }

    /** 主手是食物吗？ */
    private static boolean isEdible(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        return s.getItem().components().get(DataComponents.FOOD) != null;
    }

    /**
     * 确保主手拿着食物：主手不是就在快捷栏里找一个换过去。
     *
     * <p>只动「选中槽」，不动物品本身，玩家交还后槽位停在哪就停在哪，不丢东西。
     */
    private static boolean ensureFoodInHand(LocalPlayer p) {
        if (isEdible(p.getMainHandItem())) return true;
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (isEdible(s)) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        return false;
    }

    /** 从玩家大致指向目标方块的那个面，用于 {@code startDestroyBlock}。 */
    private static Direction faceTowards(LocalPlayer p, BlockPos pos) {
        double dx = (pos.getX() + 0.5) - p.getX();
        double dy = (pos.getY() + 0.5) - p.getEyeY();
        double dz = (pos.getZ() + 0.5) - p.getZ();
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
