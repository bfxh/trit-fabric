package com.bfxh.trit.neo;

/**
 * 纯算弹道内核：给定弹射物的位置 / 速度 / 类型，逐拍外推走向，求与「我」的
 * 最近接近点（CPA）和命中时间（TTI）。
 *
 * <p><strong>零 Minecraft 依赖</strong>，所以 {@code python test.py} 能直接编译并断言，
 * 不需要开游戏。{@code Sensor} 只负责把实体数据喂进来。
 *
 * <p>积分顺序照 MC 的箭头实现：先减重力 → 推进位置 → 对 x/z 乘空气阻力。
 * 一拍的先后差是亚格级的，而这种「模型和真实物理差半拍」的量级正是
 * {@link ProjectilePredictor} 用残差学出来的东西 —— 这里不追求把常数写死成真理。
 */
public final class Ballistics {

    private Ballistics() {
    }

    /** 弹道类型：每类一套（重力 格/拍²，空气阻力 每拍乘子）。 */
    public enum Kind {
        /** 弓箭（{@code AbstractArrow} 系）。 */
        ARROW(0.05, 0.99),
        /** 三叉戟：有重力，阻力略不同，按箭兜底。 */
        TRIDENT(0.05, 0.99),
        /** 雪球 / 鸡蛋 / 药水 / 珍珠：用实体默认重力，无空气阻力。 */
        THROWN(0.08, 1.0),
        /** 烈焰人火球 / 凋灵头 / 大风球：近似直线，无重力。 */
        FIREBALL(0.0, 1.0),
        /** 末影龙火球：慢、直线。 */
        DRAGON_FIREBALL(0.0, 1.0),
        /** 潜影贝导弹：会转弯 —— 直线模型必然不准，σ 会被学得很大，这是设计而非缺陷。 */
        SHULKER_BULLET(0.0, 1.0),
        /** 阵风球：抛物线，落地爆炸。 */
        WIND_CHARGE(0.03, 1.0),
        /** 烟花弹（弩射击用）。 */
        FIREWORK(0.0, 0.98),
        /** 认不出的弹射物：一律按箭处理，靠残差学习纠偏。 */
        UNKNOWN(0.05, 0.99);

        public final double gravity;
        public final double drag;

        Kind(double gravity, double drag) {
            this.gravity = gravity;
            this.drag = drag;
        }
    }

    /** 类型数，供预测器开定长数组。 */
    public static final int KIND_N = Kind.values().length;

    /** 接触半径（格）：玩家碰撞盒半宽 ~0.3 + 弹判定 + 一点余量。 */
    public static final double HIT_RADIUS = 0.9;
    /** 外推窗口上限（拍）：3 秒。超过就不算威胁。 */
    public static final int MAX_TICKS = 60;

    /** 学习修正的允许范围，防止把重力学飞。 */
    public static final double GRAV_CORR_MAX = 0.06;
    public static final double DRAG_CORR_MAX = 0.03;

    /**
     * 一次外推的结果。对象可复用（{@link #reset()}），避免每拍产生垃圾。
     */
    public static final class Approach {
        /** 首次进入接触半径的拍号；-1 = 窗口内打不到。 */
        public int tti = -1;
        /** 窗口内最近距离（格）。 */
        public double cpa = Double.MAX_VALUE;
        /** 最近距离出现在第几拍。 */
        public int cpaTick;
        /** 命中点（tti 有效时）或最近点坐标。 */
        public final double[] at = new double[3];
        /** 此刻是否在接近我（相对速度与相对位置反向）。 */
        public boolean closing;
        /** 外推结束时弹还在不在窗口内（用于「已飞过」判定）。 */
        public double endDist = Double.MAX_VALUE;

        void reset() {
            tti = -1;
            cpa = Double.MAX_VALUE;
            cpaTick = 0;
            closing = false;
            endDist = Double.MAX_VALUE;
        }
    }

    /**
     * 推进一拍：<em>就地</em>修改位置和速度。
     *
     * @param p        位置 [x,y,z]
     * @param v        速度 [vx,vy,vz]（格/拍）
     * @param k        类型
     * @param gravAdd  学到的重力修正（格/拍²，可正可负）
     * @param dragAdd  学到的阻力修正（加在每拍乘子上）
     */
    public static void advance(double[] p, double[] v, Kind k, double gravAdd, double dragAdd) {
        double g = k.gravity + clamp(gravAdd, -GRAV_CORR_MAX, GRAV_CORR_MAX);
        v[1] -= g;
        p[0] += v[0];
        p[1] += v[1];
        p[2] += v[2];
        double d = clamp(k.drag + dragAdd, 0.0, 1.0);
        v[0] *= d;
        v[2] *= d;
    }

    /**
     * 外推最多 {@code maxTicks} 拍，求与「我」的最近接近点。
     *
     * <p>「我」按匀速外推（{@code meExtra} 是<em>假想</em>的躲避速度，用来回答
     * 「往这个方向躲，最近距离会变成多少」）；{@code meExtra} 传 null 表示不动。
     *
     * @param pos    弹当前位置（不被修改）
     * @param vel    弹当前速度（不被修改）
     * @param k      类型
     * @param gravAdd 重力修正
     * @param dragAdd 阻力修正
     * @param mePos  我的当前位置
     * @param meVel  我的当前速度，允许 null
     * @param meExtra 假想的躲避速度，允许 null
     * @param maxTicks 外推拍数
     * @param reuse  结果对象，null 则新建
     */
    public static Approach cpa(double[] pos, double[] vel, Kind k, double gravAdd, double dragAdd,
                               double[] mePos, double[] meVel, double[] meExtra,
                               int maxTicks, Approach reuse) {
        Approach out = reuse == null ? new Approach() : reuse;
        out.reset();

        double[] p = {pos[0], pos[1], pos[2]};
        double[] v = {vel[0], vel[1], vel[2]};
        double mx = mePos[0], my = mePos[1], mz = mePos[2];
        double svx = meVel == null ? 0 : meVel[0];
        double svy = meVel == null ? 0 : meVel[1];
        double svz = meVel == null ? 0 : meVel[2];
        double evx = meExtra == null ? 0 : meExtra[0];
        double evy = meExtra == null ? 0 : meExtra[1];
        double evz = meExtra == null ? 0 : meExtra[2];

        // t=0 先判接近性：不接近的弹，后面再怎么算都不该算威胁。
        double rx0 = p[0] - mx, ry0 = p[1] - my, rz0 = p[2] - mz;
        double vx0 = v[0] - svx, vy0 = v[1] - svy, vz0 = v[2] - svz;
        out.closing = (rx0 * vx0 + ry0 * vy0 + rz0 * vz0) < 0;

        double best = Math.sqrt(rx0 * rx0 + ry0 * ry0 + rz0 * rz0);
        out.cpa = best;
        out.at[0] = p[0];
        out.at[1] = p[1];
        out.at[2] = p[2];

        int limit = Math.min(maxTicks, MAX_TICKS);
        for (int t = 1; t <= limit; t++) {
            advance(p, v, k, gravAdd, dragAdd);
            mx += svx + evx;
            my += svy + evy;
            mz += svz + evz;
            double dx = p[0] - mx, dy = p[1] - my, dz = p[2] - mz;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < out.cpa) {
                out.cpa = d;
                out.cpaTick = t;
                out.at[0] = p[0];
                out.at[1] = p[1];
                out.at[2] = p[2];
            }
            if (out.tti < 0 && d <= HIT_RADIUS) {
                out.tti = t;
            }
            if (t == limit) out.endDist = d;
            // 已经飞过且越拉越远：不必再算满窗口。
            if (out.tti < 0 && t > 2 && !out.closing && d > out.cpa * 2.0 + 4.0) {
                out.endDist = d;
                break;
            }
        }
        return out;
    }

    /** 命中点半径判定：cpa 小于「接触 + 波动带」才算能打到。 */
    public static boolean willHit(double cpa, double band) {
        return cpa <= HIT_RADIUS + Math.max(0.0, band);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
