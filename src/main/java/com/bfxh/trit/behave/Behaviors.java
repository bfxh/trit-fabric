package com.bfxh.trit.behave;

import com.bfxh.trit.core.Channel;
import com.bfxh.trit.core.Phase;
import com.bfxh.trit.core.Tri;
import com.bfxh.trit.core.Vote;

import java.util.List;

/**
 * 行为模块集合。
 *
 * <p>每个模块只管一件事，独立往通道上投票，互不协商。谁对谁错由
 * {@link com.bfxh.trit.core.Machine} 按权重和「未知优先」规则裁决。
 *
 * <p>移植自 Rust 侧 {@code behaviors.rs}，但感知精度高得多：
 * 有连续的距离和角度，不需要靠 8 方向量化猜方位。
 */
public final class Behaviors {

    private Behaviors() {
    }

    /** 侧移方向翻转周期（拍）。 */
    private static final long FLIP = 40;

    private static Tri flip(long tick) {
        return ((tick / FLIP) & 1L) == 0 ? Tri.POS : Tri.NEG;
    }

    /** 把角度差转成方向票：超出死区才转，避免抖动。 */
    private static Tri turnDir(Float delta, float deadZone) {
        if (delta == null) return Tri.ZERO;
        if (delta > deadZone) return Tri.POS;
        if (delta < -deadZone) return Tri.NEG;
        return Tri.ZERO;
    }

    // ==================================================================
    // 巡逻：没敌人、没采集目标时到处走，遇墙绕、遇坑跳、遇台阶跳
    // ==================================================================
    public static final class Patrol implements Behavior {
        private long lastTurn;

        @Override
        public String name() {
            return "patrol";
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            if (c.phase != Phase.IDLE) return;
            // 有采集目标时交给 Forage，别抢方向盘
            if (c.w.resourcePos != null) return;

            boolean frontBlocked = c.w.wallAt(0);
            boolean frontLeft = c.w.wallAt(1) || c.w.wallAt(7);
            boolean frontRight = c.w.wallAt(2) || c.w.wallAt(6);

            if (frontBlocked) {
                // 撞墙了：不再前进，朝空的一侧转
                out.add(Vote.abstain(Channel.FORWARD, 5, "patrol_wall"));
                Tri side = frontLeft && !frontRight ? Tri.POS : (frontRight && !frontLeft ? Tri.NEG : flip(c.tick));
                out.add(Vote.of(Channel.YAW, side, 4, "patrol_turn"));
                if (c.w.step == Boolean.TRUE) out.add(Vote.of(Channel.JUMP, Tri.POS, 3, "patrol_hop"));
                lastTurn = c.tick;
                return;
            }

            if (c.w.pit == Boolean.TRUE) {
                out.add(Vote.abstain(Channel.FORWARD, 5, "patrol_pit"));
                out.add(Vote.of(Channel.YAW, flip(c.tick), 4, "patrol_avoid_pit"));
                return;
            }

            if (c.w.stuck) {
                out.add(Vote.of(Channel.YAW, flip(c.tick), 4, "patrol_unstick"));
                out.add(Vote.of(Channel.JUMP, Tri.POS, 3, "patrol_hop"));
                return;
            }

            out.add(Vote.of(Channel.FORWARD, Tri.POS, 3, "patrol"));
            if (c.w.step == Boolean.TRUE) {
                out.add(Vote.of(Channel.JUMP, Tri.POS, 2, "patrol_step"));
            }
            // 长时间直线走容易贴着地形边缘磨，偶尔修正一下朝向
            if (c.tick - lastTurn > 120) {
                out.add(Vote.of(Channel.YAW, flip(c.tick), 1, "patrol_drift"));
                lastTurn = c.tick;
            }
        }
    }

    // ==================================================================
    // 交战：接近、瞄准、攻击、侧移绕圈
    // ==================================================================
    public static final class Combat implements Behavior {

        @Override
        public String name() {
            return "combat";
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            if (c.phase != Phase.ENGAGE) return;
            if (c.w.targetId < 0 || c.w.dist == null) return;

            float d = c.w.dist;

            // 瞄准：有精确角度就不猜，直接给方向
            Tri yaw = turnDir(c.w.yawToTarget, 5f);
            if (yaw != Tri.ZERO) out.add(Vote.of(Channel.YAW, yaw, 4, "combat_track"));
            Tri pitch = turnDir(c.w.pitchToTarget, 5f);
            if (pitch != Tri.ZERO) out.add(Vote.of(Channel.PITCH, pitch, 2, "combat_aim"));

            // 距离控制
            if (d > 3.0f) {
                out.add(Vote.of(Channel.FORWARD, Tri.POS, 4, "combat_close"));
            } else if (d < 1.6f) {
                out.add(Vote.of(Channel.FORWARD, Tri.NEG, 2, "combat_spacing"));
            } else {
                out.add(Vote.abstain(Channel.FORWARD, 2, "combat_hold_range"));
            }

            // 绕圈：不站桩挨打
            out.add(Vote.of(Channel.STRAFE, flip(c.tick), 2, "combat_strafe"));

            // 够得着就打
            if (d <= 3.2f && Math.abs(c.w.yawToTarget == null ? 999f : c.w.yawToTarget) < 45f) {
                out.add(Vote.of(Channel.ATTACK, Tri.POS, 3, "combat"));
            }

            // 打不过就跳一跳（暴击 + 打乱对方预判）
            if (d <= 3.0f && (c.tick % 20) == 0) {
                out.add(Vote.of(Channel.JUMP, Tri.POS, 2, "combat_crit"));
            }

            // 一群怪围上来就边打边退
            if (c.w.enemies != null && c.w.enemies >= 3) {
                out.add(Vote.of(Channel.FORWARD, Tri.NEG, 3, "combat_outnumbered"));
            }
        }
    }

    // ==================================================================
    // 撤退：低血且有威胁，头也不回地跑
    // ==================================================================
    public static final class Flee implements Behavior {
        @Override
        public String name() {
            return "flee";
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            if (c.phase != Phase.RETREAT) return;
            out.add(Vote.of(Channel.FORWARD, Tri.NEG, 4, "flee"));
            out.add(Vote.of(Channel.STRAFE, flip(c.tick), 1, "flee_juke"));
            if (c.w.step == Boolean.TRUE) out.add(Vote.of(Channel.JUMP, Tri.POS, 2, "flee_hop"));
            // 背后有墙就往旁边让，别贴着墙原地推
            if (c.w.wallAt(4)) {
                out.add(Vote.of(Channel.STRAFE, flip(c.tick), 4, "flee_wall"));
            }
        }
    }

    // ==================================================================
    // 危险规避：脚下附近有岩浆 / 火，权重压过一切
    // ==================================================================
    public static final class HazardAvoid implements Behavior {
        @Override
        public String name() {
            return "hazard";
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            if (c.w.hazard != Tri.POS) return;
            out.add(Vote.of(Channel.FORWARD, Tri.NEG, 6, "hazard_back"));
            out.add(Vote.of(Channel.STRAFE, flip(c.tick), 3, "hazard_side"));
            out.add(Vote.of(Channel.JUMP, Tri.POS, 3, "hazard_hop"));
        }
    }

    // ==================================================================
    // 采集：朝最近的原木 / 矿石走过去，瞄准后按住左键
    // ==================================================================
    public static final class Forage implements Behavior {

        @Override
        public String name() {
            return "forage";
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            if (c.phase != Phase.IDLE) return;
            // 血量太低先别干活
            if (c.w.hp != null && c.w.hp < 0.35f) return;
            int[] res = c.w.resourcePos;
            if (res == null) return;

            // 必须用「当前目标」的距离，不能用 min(wood, ore)：
            // 目标是远处的树时最近的矿可能更近，取 min 会误判成「已经到了」。
            float d = c.w.resourceDist == null ? Float.MAX_VALUE : c.w.resourceDist;
            if (d == Float.MAX_VALUE) return;

            // 先把视角压到目标上
            Tri yaw = turnDir(c.w.yawToResource, 8f);
            if (yaw != Tri.ZERO) out.add(Vote.of(Channel.YAW, yaw, 4, "forage_turn"));
            Tri pitch = turnDir(c.w.pitchToResource, 6f);
            if (pitch != Tri.ZERO) out.add(Vote.of(Channel.PITCH, pitch, 3, "forage_aim"));

            if (d > 3.0f) {
                // 还没走到：前进。视角偏太多就先转再走
                if (yaw != Tri.ZERO && Math.abs(c.w.yawToResource) > 35f) {
                    out.add(Vote.abstain(Channel.FORWARD, 4, "forage_turn_first"));
                } else {
                    out.add(Vote.of(Channel.FORWARD, Tri.POS, 4, "forage_walk"));
                }
                if (c.w.step == Boolean.TRUE) {
                    out.add(Vote.of(Channel.JUMP, Tri.POS, 3, "forage_step_up"));
                }
            } else {
                // 到位了：站住挖。站着不动是刻意的 —— 走动会把准星带偏
                out.add(Vote.abstain(Channel.FORWARD, 5, "forage_hold"));
                out.add(Vote.abstain(Channel.STRAFE, 4, "forage_hold"));
                if (yaw == Tri.ZERO && pitch == Tri.ZERO) {
                    out.add(Vote.hold(Channel.ATTACK, 5, "forage_mine"));
                }
            }
        }
    }

    // ==================================================================
    // 进食：饿了就吃，别饿着去砍树（砍完跑不动、也不能自然回血）
    // ==================================================================
    public static final class Eat implements Behavior {
        @Override
        public String name() {
            return "eat";
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            if (c.w.food == null) return;
            if (c.w.food > 12) return;
            if (c.phase == Phase.ENGAGE || c.phase == Phase.RETREAT) return;
            out.add(Vote.of(Channel.USE, Tri.POS, 3, "eat"));
            // 吃东西的时候站着别动，走动不打断但会拖慢进度
            out.add(Vote.abstain(Channel.FORWARD, 3, "eat_stand"));
        }
    }

    // ==================================================================
    // 静息回血：血不满又没威胁，站桩等自然回复
    // ==================================================================
    public static final class Regen implements Behavior {
        @Override
        public String name() {
            return "regen";
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            if (c.w.hp == null) return;
            if (c.w.hp > 0.6f) return;
            if (c.w.threat == Tri.POS) return;
            // 饱食度不够时自然回血是不生效的，这时候站桩等于原地等死。
            // 饿着就该继续干活找吃的，而不是杵着。
            if (c.w.food == null || c.w.food < 17) return;
            out.add(Vote.abstain(Channel.FORWARD, 6, "regen_stand"));
            out.add(Vote.abstain(Channel.STRAFE, 4, "regen_stand"));
        }
    }

    // ==================================================================
    // 待命扫视：什么都没有时转转头，别像个木桩
    // ==================================================================
    public static final class IdleScan implements Behavior {
        @Override
        public String name() {
            return "idle_scan";
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            if (c.phase != Phase.IDLE) return;
            if (c.w.resourcePos != null) return;
            if (c.w.targetId >= 0) return;
            // 每 2 秒换个方向扫
            out.add(Vote.of(Channel.YAW, flip(c.tick), 3, "idle_scan"));
        }
    }

    // ==================================================================
    // 锚点：离出发点太远就往回走，避免一路跑丢
    // ==================================================================
    public static final class Anchor implements Behavior {
        private double[] anchor;
        private boolean has;

        @Override
        public String name() {
            return "anchor";
        }

        public void setAnchor(double[] p) {
            anchor = p == null ? null : p.clone();
            has = anchor != null;
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            if (!has || c.w.pos == null) return;
            if (c.phase == Phase.ENGAGE || c.phase == Phase.RETREAT) return;
            double dx = anchor[0] - c.w.pos[0];
            double dz = anchor[2] - c.w.pos[2];
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 64) return;

            // 朝锚点的方位角。MC 的 forward = (-sin(yaw), cos(yaw))，
            // 所以 want = atan2(-dx, dz)。
            if (c.w.yaw == null) return;
            double want = Math.toDegrees(Math.atan2(-dx, dz));
            float delta = (float) (want - c.w.yaw);
            while (delta > 180) delta -= 360;
            while (delta < -180) delta += 360;
            out.add(Vote.of(Channel.YAW, turnDir(delta, 15f), 5, "anchor_turn"));
            out.add(Vote.abstain(Channel.FORWARD, 3, "anchor_edge"));
            if (Math.abs(delta) < 40f) {
                out.add(Vote.of(Channel.FORWARD, Tri.POS, 4, "anchor_return"));
            }
        }
    }

    /** 组装默认行为链。顺序不影响结果：裁决只看权重和方向。 */
    public static Behavior[] defaults(Anchor anchor) {
        return new Behavior[]{
                new HazardAvoid(),
                new Combat(),
                new Flee(),
                new Eat(),
                new Regen(),
                new Forage(),
                new Patrol(),
                new IdleScan(),
                anchor,
        };
    }
}
