package com.bfxh.trit.behave;

import com.bfxh.trit.core.Channel;
import com.bfxh.trit.core.Mode;
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
            float yawT = c.w.yawToTarget == null ? 999f : c.w.yawToTarget;
            float pitchT = c.w.pitchToTarget == null ? 999f : c.w.pitchToTarget;

            // 瞄准：有精确角度就不猜，直接给方向（预测过的提前量已经含在角度里）
            Tri yaw = turnDir(c.w.yawToTarget, 5f);
            if (yaw != Tri.ZERO) out.add(Vote.of(Channel.YAW, yaw, 5, "combat_track"));
            Tri pitch = turnDir(c.w.pitchToTarget, 5f);
            if (pitch != Tri.ZERO) out.add(Vote.of(Channel.PITCH, pitch, 3, "combat_aim"));

            boolean aligned = Math.abs(yawT) < 45f && Math.abs(pitchT) < 45f;

            // 距离控制：贴脸就退一点，远了就压上
            if (d > 3.0f) {
                out.add(Vote.of(Channel.FORWARD, Tri.POS, 4, "combat_close"));
            } else if (d < 1.4f) {
                out.add(Vote.of(Channel.FORWARD, Tri.NEG, 2, "combat_spacing"));
            } else {
                out.add(Vote.abstain(Channel.FORWARD, 2, "combat_hold_range"));
            }

            // 侧移绕圈，不站桩挨打
            out.add(Vote.of(Channel.STRAFE, flip(c.tick), 2, "combat_strafe"));

            // 近战：够得着又瞄上了就打
            if (d <= 3.2f && aligned) {
                out.add(Vote.of(Channel.ATTACK, Tri.POS, 4, "combat"));
            }

            // 远程：有弓/弩+箭、距离拉开、视线通畅 → 拉弓放风筝（走位中射击，不傻蹲）
            if (c.w.hasBow == Boolean.TRUE && c.w.hasAmmo == Boolean.TRUE
                    && d > 5f && c.w.targetOccluded != Boolean.TRUE) {
                out.add(Vote.of(Channel.RANGED, Tri.POS, 5, "combat_ranged"));
                // 边打边往后拉一点距离，比原地蹲着更活
                out.add(Vote.of(Channel.FORWARD, Tri.NEG, 2, "combat_kite"));
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
            // 只在「采集模式」主动找树/矿；其它模式不抢着挖，避免和你抢控制权
            if (c.mode != Mode.FORAGE) return;
            if (c.phase != Phase.IDLE) return;
            // 有活威胁就别挖了，交给战斗/撤退（这是「打仗时还挖木头」的根因）
            if (c.w.threat == Tri.POS) return;
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
                // 被遮挡（隔着墙/树）：别硬冲，绕一下找能下手的角
                if (c.w.resourceOccluded == Boolean.TRUE) {
                    out.add(Vote.of(Channel.STRAFE, flip(c.tick), 3, "forage_circle_occluded"));
                }
            } else {
                // 到位了但视线被挡（比如树贴着墙、或自己卡在夹角）：
                // 走过去贴脸 + 小幅绕，而不是站着对空气硬挖
                if (c.w.resourceOccluded == Boolean.TRUE) {
                    out.add(Vote.of(Channel.FORWARD, Tri.POS, 4, "forage_approach_occluded"));
                    out.add(Vote.of(Channel.STRAFE, flip(c.tick), 3, "forage_circle_occluded"));
                    return;
                }
                // 正常：站住挖。走动会把准星带偏
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
            // 平滑扫视：正弦来回，约 6 秒一圈，自然回到正前方，不会卡在某个死角。
            double v = Math.cos(c.tick * 0.025);
            out.add(Vote.of(Channel.YAW, v >= 0 ? Tri.POS : Tri.NEG, 3, "idle_scan"));
            // 把俯仰慢慢拉平，别一直盯着地面或天空（这是「视角往地下看」的残留根因）
            if (c.w.pitch != null && Math.abs(c.w.pitch) > 3f) {
                out.add(Vote.of(Channel.PITCH, c.w.pitch > 0 ? Tri.NEG : Tri.POS, 2, "idle_level"));
            }
        }
    }

    // ==================================================================
    // 隐藏 / 蹲伏：有威胁但不在近战距离就蹲下降低存在感；
    // 站在悬崖边蹲下防止被推下去。这是「灵性」的一部分。
    // ==================================================================
    public static final class Stealth implements Behavior {
        @Override
        public String name() {
            return "stealth";
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            // 只在「悬崖边别往后退掉坑里」时蹲下——这是保命，不是默认动作。
            if (c.w.pit == Boolean.TRUE && c.phase != Phase.ENGAGE && c.phase != Phase.RETREAT) {
                out.add(Vote.of(Channel.CROUCH, Tri.POS, 3, "stealth_edge"));
            }
            // 有威胁但根本看不到目标（纯未知）、且不在交战：缩一下藏身，别傻站着硬刚。
            // 注意：一旦进入 ENGAGE 由战斗逻辑接管，这里不再掺和，避免「只会蹲不会跑」。
            if (c.w.threat == Tri.POS && c.phase == Phase.IDLE && c.w.targetId < 0) {
                out.add(Vote.of(Channel.CROUCH, Tri.POS, 1, "stealth_hide"));
            }
        }
    }

    // ==================================================================
    // 建造：前方是坑 / 液体，且背包有方块 → 放一块搭桥，别掉下去。
    // 只在「建造模式」主动铺路；平时遇到坑由巡逻的跳/绕处理。
    // ==================================================================
    public static final class Build implements Behavior {
        @Override
        public String name() {
            return "build";
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            if (c.mode != Mode.BUILD) return;
            if (c.phase == Phase.ENGAGE || c.phase == Phase.RETREAT) return;
            if (c.w.gapAhead != Boolean.TRUE) return;
            if (c.w.hasBlocks != Boolean.TRUE) return;
            // 前面是坑：放一块，同时站住别往前冲
            out.add(Vote.of(Channel.PLACE, Tri.POS, 5, "build_bridge"));
            out.add(Vote.abstain(Channel.FORWARD, 4, "build_hold"));
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

    /** 水平面距离（忽略 y）。 */
    private static double distXZ(double[] a, double[] b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        double dx = a[0] - b[0], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ==================================================================
    // 开路：卡住且正前方是可挖方块（土/草/沙/石头等，不含危险与原木/矿）→ 挖开。
    // 解决「只会挖木头、不会挖土/各种方块开路」以及被墙卡死的问题。
    // 与采集互不抢：采集在挖它自己的目标时这里让位。
    // ==================================================================
    public static final class BlockedDig implements Behavior {
        @Override
        public String name() {
            return "blocked_dig";
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            if (c.w.canDigFront != Boolean.TRUE) return;
            if (!c.w.stuck) return;
            if (c.phase == Phase.ENGAGE || c.phase == Phase.RETREAT) return;
            if (c.w.resourcePos != null && c.w.resourceDist != null && c.w.resourceDist < 4f) return;
            Tri yaw = turnDir(c.w.yawToFront, 8f);
            if (yaw != Tri.ZERO) out.add(Vote.of(Channel.YAW, yaw, 4, "blocked_dig_turn"));
            Tri pitch = turnDir(c.w.pitchToFront, 6f);
            if (pitch != Tri.ZERO) out.add(Vote.of(Channel.PITCH, pitch, 3, "blocked_dig_aim"));
            if (yaw == Tri.ZERO && pitch == Tri.ZERO) {
                out.add(Vote.hold(Channel.ATTACK, 4, "blocked_dig"));
                out.add(Vote.abstain(Channel.FORWARD, 3, "blocked_dig_hold"));
            }
        }
    }

    // ==================================================================
    // 跟随：FOLLOW 模式下跟踪某个实体（环盘菜单里用准星指定的，或默认最近玩家）。
    // 保持约 4 格距离，看着它走，遇墙绕开。自保（交战/撤退）时让位。
    // ==================================================================
    public static final class Follow implements Behavior {
        @Override
        public String name() {
            return "follow";
        }

        @Override
        public void vote(Behavior.Ctx c, List<Vote> out) {
            if (c.mode != Mode.FOLLOW) return;
            if (c.w.followId < 0 || c.w.followXyz == null) return;
            if (c.phase == Phase.ENGAGE || c.phase == Phase.RETREAT) return;

            Tri yaw = turnDir(c.w.followYaw, 6f);
            if (yaw != Tri.ZERO) out.add(Vote.of(Channel.YAW, yaw, 5, "follow_track"));
            Tri pitch = turnDir(c.w.followPitch, 6f);
            if (pitch != Tri.ZERO) out.add(Vote.of(Channel.PITCH, pitch, 3, "follow_aim"));

            double d = distXZ(c.w.pos, c.w.followXyz);
            if (d > 5.0) {
                out.add(Vote.of(Channel.FORWARD, Tri.POS, 4, "follow_close"));
            } else if (d < 2.5) {
                out.add(Vote.of(Channel.FORWARD, Tri.NEG, 3, "follow_back"));
            } else {
                out.add(Vote.abstain(Channel.FORWARD, 2, "follow_hold"));
            }

            if (c.w.wallAt(0)) {
                out.add(Vote.abstain(Channel.FORWARD, 4, "follow_wall"));
                out.add(Vote.of(Channel.YAW, flip(c.tick), 4, "follow_turn"));
                if (c.w.step == Boolean.TRUE) out.add(Vote.of(Channel.JUMP, Tri.POS, 2, "follow_hop"));
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
                new Build(),
                new BlockedDig(),
                new Stealth(),
                new Patrol(),
                new IdleScan(),
                new Follow(),
                anchor,
        };
    }
}
