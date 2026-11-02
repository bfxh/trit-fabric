package com.bfxh.trit.neo;

import com.bfxh.trit.core.Tri;
import com.bfxh.trit.neo.Ballistics.Approach;
import com.bfxh.trit.neo.Ballistics.Kind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 弹幕预测：<strong>骨架是算的，波动是预测的</strong>。
 *
 * <ul>
 *   <li><strong>算</strong>：{@link Ballistics} 逐拍积分弹道，得出 CPA（最近接近点）与
 *       TTI（命中时间）。闭式、可复算。</li>
 *   <li><strong>预测</strong>：真实游戏和这套常数之间的差 —— 客户端读不到重力和阻力、
 *       位置同步带一拍噪声、潜影贝会转弯 —— 全部按<em>类型</em>在线学出来：
 *       系统偏差 {@code bias}、随机波动 {@code sigma}、重力修正 {@code gravCorr}、
 *       阻力修正 {@code dragCorr}。判据于是是「算出来的 CPA」对比「预测出来的波动带」，
 *       而不是拿一个假精确的常数硬判。</li>
 *   <li><strong>躲哪边</strong>：先用模型重算「往这个方向躲，最近距离变成多少」（算），
 *       再用 {@link LearningAutomaton} 记住「上次往这边躲有没有挨打」（预测）。
 *       先验只在算出来的优势不到一个波动带时才拍板。</li>
 * </ul>
 *
 * <p>整个类不碰 Minecraft：{@code Sensor} 喂实体数据，遮挡判定以回调注入。
 * 零渲染、零视角、零播报 —— 产出只是一张躲避票。
 */
public final class ProjectilePredictor {

    // 躲避动作（与 Channel 的语义一一对应）
    public static final int DODGE_LEFT = 0;
    public static final int DODGE_RIGHT = 1;
    public static final int DODGE_BACK = 2;
    public static final int DODGE_FWD = 3;
    public static final int DODGE_JUMP = 4;
    public static final int DODGE_N = 5;
    public static final int DODGE_NONE = -1;

    /** TTI 上限（拍）：2 秒。超过归 B 级，不值得为它改变计划。 */
    public static final int TTI_MAX = 40;
    /** 外推窗口比判定窗口多留几拍，好判断「它到底飞过去了没有」。 */
    private static final int LOOKAHEAD = TTI_MAX + 6;
    /** 连续多少拍没再被观测到就认为这颗弹消失了。 */
    private static final int STALE = 3;
    /** 最多同时跟踪多少颗（超出按更近的优先，其余只计数不外推）。 */
    public static final int MAX_TRACKS = 24;
    /** 玩家水平躲避速度（格/拍）：原版走位约 0.3，疾跑约 0.5，取中。 */
    private static final double DODGE_SPEED = 0.42;
    /** 跳跃的上升初速（格/拍）。 */
    private static final double JUMP_SPEED = 0.42;

    /** 波动带：地板 + σ·√t + |bias|·t，封顶。 */
    private static final double BAND_BASE = 0.35;
    private static final double BAND_K = 1.8;
    private static final double BAND_CAP = 3.0;
    /** 没见过任何样本时的 σ 地板 —— 别把「还没见过」当成「绝对精确」。 */
    private static final double SIGMA_FLOOR = 0.05;

    /** EWMA 系数：一拍 25% 权重，约 8 拍收敛。 */
    private static final double ALPHA = 0.25;
    /** 修正量的学习率与上限（格/拍²，格/拍）。 */
    private static final double CORR_LR = 0.35;
    private static final double CORR_MAX = 0.02;

    /** 命中点之前会不会先撞墙 —— 由 Sensor 用方块查询实现。 */
    public interface PathCheck {
        boolean blocked(double[] from, double[] to);
    }

    /** 一拍的评估结果（对象复用，字段每拍重写）。 */
    public static final class Assessment {
        /** POS = 有 A 级；NEG = 扫过了、明确没有；ZERO = 没扫描，未知。 */
        public Tri level = Tri.ZERO;
        /** 本拍在飞的弹射物数量。 */
        public int count;
        /** 其中 A 级（需响应）的数量。 */
        public int aGrade;
        /** 最危险那颗。 */
        public int worstId = -1;
        public double tti = -1;
        public double cpa = Double.MAX_VALUE;
        /** 那颗的预测波动带（格）。 */
        public double band;
        /** 那颗类型的每拍残差波动（格）。 */
        public double sigma;
        public Kind kind = Kind.UNKNOWN;
        /** 那颗当前位置：给 HUD 画来袭方位用，不用转头去看。 */
        public final double[] worstPos = new double[3];
        public boolean worstPosKnown;
        /** 建议躲避动作。 */
        public int dodge = DODGE_NONE;
        /** 按该动作躲之后的最近距离余量（格）。 */
        public double dodgeMargin;
        /** 累计学习样本数，指标里用来看「到底学会没有」。 */
        public long learned;
    }

    /** 一颗弹的跟踪状态。 */
    private static final class Track {
        final int id;
        Kind kind = Kind.UNKNOWN;
        final double[] pos = new double[3];
        final double[] vel = new double[3];
        /** 上一拍外推出的「这一拍应该在哪」，与真实观测求残差。 */
        final double[] pred = new double[3];
        boolean hasPred;
        long lastSeen = Long.MIN_VALUE / 4;
        boolean aGrade;
        boolean settled;
        int chosenDodge = DODGE_NONE;

        Track(int id) {
            this.id = id;
        }
    }

    private final Map<Integer, Track> tracks = new HashMap<>();
    private final List<Integer> expired = new ArrayList<>();

    // ---- 按类型学的参数 ----
    private final double[][] bias = new double[Ballistics.KIND_N][3];
    private final double[] sigma = new double[Ballistics.KIND_N];
    private final double[] gravCorr = new double[Ballistics.KIND_N];
    private final double[] dragCorr = new double[Ballistics.KIND_N];
    private final long[] samples = new long[Ballistics.KIND_N];
    private final LearningAutomaton[] dodgePrior = new LearningAutomaton[Ballistics.KIND_N];

    private final Assessment result = new Assessment();
    private final float[] oneHot = new float[DODGE_N];
    private final double[] extra = new double[3];
    private final double[] margins = new double[DODGE_N];
    /** 候选方向各用一个，避免每拍 new。 */
    private final Approach[] cand = new Approach[DODGE_N];
    /** 逐弹外推用的对象池：一拍的借用量有上限（MAX_TRACKS），复用掉全部垃圾。 */
    private final Approach[] pool = new Approach[MAX_TRACKS];
    private int poolIdx;
    /** 最危险那颗的判决快照（pickDodge 要用，且不能被池里的后续借用覆盖）。 */
    private final Approach worstA = new Approach();

    private long learnedTotal;

    public ProjectilePredictor() {
        for (int i = 0; i < Ballistics.KIND_N; i++) {
            sigma[i] = SIGMA_FLOOR;
            dodgePrior[i] = new LearningAutomaton(DODGE_N, 0.1f);
        }
        for (int i = 0; i < DODGE_N; i++) cand[i] = new Approach();
        for (int i = 0; i < pool.length; i++) pool[i] = new Approach();
    }

    private Approach borrow() {
        return pool[poolIdx++ % pool.length];
    }

    /**
     * 上报一颗还活着的弹射物。同一颗每拍都要报；不再上报的会在 {@link #assess} 里过期结算。
     *
     * @param tick 当前拍号
     */
    public void observe(int id, Kind kind, double px, double py, double pz,
                        double vx, double vy, double vz, long tick) {
        Track t = tracks.get(id);
        if (t == null) {
            if (tracks.size() >= MAX_TRACKS) return;   // 弹幕海：不外推，只计数
            t = new Track(id);
            tracks.put(id, t);
        }
        long prevTick = t.lastSeen;
        t.kind = kind;
        t.lastSeen = tick;

        // 残差 = 实际 - 上一拍外推。这是「算」与「真」之间唯一的差，也是学习的原料。
        // 只在连续拍之间学：跳拍会让量纲不对（残差里混进了没外推的那几拍）。
        if (t.hasPred && hasPrevObservation(prevTick, tick)) {
            learn(kind, t, px - t.pred[0], py - t.pred[1], pz - t.pred[2]);
        }

        t.pos[0] = px;
        t.pos[1] = py;
        t.pos[2] = pz;
        t.vel[0] = vx;
        t.vel[1] = vy;
        t.vel[2] = vz;

        // 为下一拍准备预测位置：抄一份，推进一拍
        double[] p = {px, py, pz};
        double[] v = {vx, vy, vz};
        int k = kind.ordinal();
        Ballistics.advance(p, v, kind, gravCorr[k], dragCorr[k]);
        t.pred[0] = p[0];
        t.pred[1] = p[1];
        t.pred[2] = p[2];
        t.hasPred = true;
    }

    private static boolean hasPrevObservation(long prevTick, long tick) {
        return tick - prevTick >= 1 && tick - prevTick <= 2;
    }

    /**
     * 一拍的残差 → 类型级参数更新。
     *
     * <p>上报的速度是引擎给的「当前真速度」，所以<strong>一步位置残差本身就等于参数误差</strong>：
     * <pre>
     *   竖向  pred_y = y + (vy - g - gravCorr)
     *         obs_y  = y + (vy - g真)
     *         ⇒ residual_y = gravCorr + (g - g真)    → gravCorr -= residual_y
     *   横向  residual_x = vx · (drag + dragCorr - drag真)  → dragCorr -= residual_x / vx
     * </pre>
     * 所以这里用 bias（残差的系统分量）直接反解，而不是对残差做逐拍差分 —— 后者只有在
     * 「上报的是累积误差」时才成立，客户端不是这种口径。
     * 修正夹在 ±{@link #CORR_MAX} 学不满的部分，由 bias 项继续撑开波动带：
     * 算不出来就承认不确定，而不是硬判成精确。
     */
    private void learn(Kind kind, Track t, double gx, double gy, double gz) {
        int k = kind.ordinal();
        bias[k][0] = lerp(bias[k][0], gx, ALPHA);
        bias[k][1] = lerp(bias[k][1], gy, ALPHA);
        bias[k][2] = lerp(bias[k][2], gz, ALPHA);
        double dev = Math.abs(gx - bias[k][0]) + Math.abs(gy - bias[k][1]) + Math.abs(gz - bias[k][2]);
        sigma[k] = Math.max(SIGMA_FLOOR, lerp(sigma[k], dev, ALPHA));

        gravCorr[k] = clamp(gravCorr[k] - CORR_LR * bias[k][1], -CORR_MAX, CORR_MAX);

        // t.vel 还是上一拍那份 —— 正是生成这次预测所用、应当用来除的速度
        double dEst = 0;
        int axes = 0;
        if (Math.abs(t.vel[0]) > 0.05) {
            dEst += bias[k][0] / t.vel[0];
            axes++;
        }
        if (Math.abs(t.vel[2]) > 0.05) {
            dEst += bias[k][2] / t.vel[2];
            axes++;
        }
        if (axes > 0) {
            dragCorr[k] = clamp(dragCorr[k] - CORR_LR * dEst / axes, -CORR_MAX, CORR_MAX);
        }
        samples[k]++;
        learnedTotal++;
    }

    /**
     * 外推 + 分级 + 选躲避方向。
     *
     * @param selfPos 玩家位置 [x,y,z]（脚底或胸口中点都可，只要与弹的坐标同系）
     * @param selfVel 玩家速度，允许 null（当静止处理）
     * @param yawDeg  玩家当前水平朝向，用来把「左/右/前/后」翻成世界向量
     * @param scanned 本拍是否真的扫过世界。false ⇒ 判未知，绝不把没扫当成没有
     */
    public Assessment assess(double[] selfPos, double[] selfVel, float yawDeg,
                             long tick, PathCheck occlusion, boolean scanned) {
        result.level = Tri.ZERO;
        result.count = 0;
        result.aGrade = 0;
        result.worstId = -1;
        result.tti = -1;
        result.cpa = Double.MAX_VALUE;
        result.band = 0;
        result.sigma = 0;
        result.kind = Kind.UNKNOWN;
        result.worstPosKnown = false;
        result.dodge = DODGE_NONE;
        result.dodgeMargin = 0;
        result.learned = learnedTotal;
        poolIdx = 0;

        int bestTti = Integer.MAX_VALUE;
        double bestCpa = Double.MAX_VALUE;
        Track worst = null;

        for (Track t : tracks.values()) {
            long age = tick - t.lastSeen;
            if (age > STALE) {
                settle(t);
                expired.add(t.id);
                continue;
            }
            if (age != 0) continue;              // 本拍没被观测到的不外推、不计数
            if (!scanned) continue;              // 没扫过：保留跟踪，但这一拍不下结论

            result.count++;
            int k = t.kind.ordinal();
            Approach a = Ballistics.cpa(t.pos, t.vel, t.kind, gravCorr[k], dragCorr[k],
                    selfPos, selfVel, null, LOOKAHEAD, borrow());
            double band = band(t.kind, a.tti > 0 ? a.tti : Math.max(1, a.cpaTick));

            boolean gradeA = a.closing
                    && a.tti >= 0 && a.tti <= TTI_MAX
                    && Ballistics.willHit(a.cpa, band)
                    && !hitsWallFirst(t, a, occlusion);
            t.aGrade = gradeA;
            if (gradeA) result.aGrade++;

            if (gradeA && (a.tti < bestTti || (a.tti == bestTti && a.cpa < bestCpa))) {
                bestTti = a.tti;
                bestCpa = a.cpa;
                worst = t;
                copyInto(a, worstA);
            }
        }
        for (Integer id : expired) tracks.remove(id);
        expired.clear();

        if (!scanned) return result;
        result.level = worst != null ? Tri.POS : Tri.NEG;
        if (worst == null) return result;

        int k = worst.kind.ordinal();
        result.worstId = worst.id;
        result.kind = worst.kind;
        result.worstPos[0] = worst.pos[0];
        result.worstPos[1] = worst.pos[1];
        result.worstPos[2] = worst.pos[2];
        result.worstPosKnown = true;
        result.tti = worstA.tti;
        result.cpa = worstA.cpa;
        result.band = band(worst.kind, worstA.tti);
        result.sigma = sigma[k];

        pickDodge(worst, selfPos, selfVel, yawDeg, k);
        return result;
    }

    /**
     * 选躲避方向：对 5 个候选各跑一遍外推，看「我按这个方向挪」之后弹到我的最近距离
     * 还剩多少（算）；先验（预测）只在算出来的差距小于一个波动带时才影响选择。
     */
    private void pickDodge(Track t, double[] selfPos, double[] selfVel, float yawDeg, int k) {
        double bandNow = result.band;
        double bestScore = -Double.MAX_VALUE;
        int best = DODGE_NONE;

        for (int act = 0; act < DODGE_N; act++) {
            dodgeVector(act, yawDeg, extra);
            Approach r = Ballistics.cpa(t.pos, t.vel, t.kind, gravCorr[k], dragCorr[k],
                    selfPos, selfVel, extra, LOOKAHEAD, cand[act]);
            double margin = r.cpa - Ballistics.HIT_RADIUS;
            margins[act] = margin;
            // 先验的发言权被限制在「一个波动带」以内：模型分不清好坏时才由经验拍板
            double prior = dodgePrior[k].weights()[act] * bandNow;
            double score = margin + prior;
            if (score > bestScore) {
                bestScore = score;
                best = act;
            }
        }
        t.chosenDodge = best;
        result.dodge = best;
        result.dodgeMargin = best >= 0 ? margins[best] : 0;
    }

    /**
     * 动作 → 世界坐标下的假想额外速度。
     * MC 约定：forward = (-sin yaw, cos yaw)，正 yaw 转向使朝向从 +z 转向 -x，
     * 所以玩家的右方 = forward 再转 90° = (-cos yaw, -sin yaw)。
     */
    static void dodgeVector(int act, float yawDeg, double[] out) {
        double rad = Math.toRadians(yawDeg);
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        double rx = -Math.cos(rad), rz = -Math.sin(rad);
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        switch (act) {
            case DODGE_LEFT -> {
                out[0] = -rx * DODGE_SPEED;
                out[2] = -rz * DODGE_SPEED;
            }
            case DODGE_RIGHT -> {
                out[0] = rx * DODGE_SPEED;
                out[2] = rz * DODGE_SPEED;
            }
            case DODGE_FWD -> {
                out[0] = fx * DODGE_SPEED;
                out[2] = fz * DODGE_SPEED;
            }
            case DODGE_BACK -> {
                out[0] = -fx * DODGE_SPEED;
                out[2] = -fz * DODGE_SPEED;
            }
            case DODGE_JUMP -> out[1] = JUMP_SPEED;
            default -> { }
        }
    }

    /** 一颗弹消失了：A 级却没打中 = 躲成功 → 奖励当时选的方向。 */
    private void settle(Track t) {
        if (t.settled || !t.aGrade) return;
        t.settled = true;
        trainPrior(t.kind, t.chosenDodge, true);
    }

    /**
     * 玩家被弹射物打到了：惩罚当时选的方向。当时没做选择（DODGE_NONE）不学 ——
     * 没做过决定谈不上选错。
     */
    public void onPlayerHurt(Kind kind, long tick) {
        if (kind == null) return;
        for (Track t : tracks.values()) {
            if (t.kind != kind || t.settled || !t.aGrade) continue;
            if (t.chosenDodge == DODGE_NONE) continue;
            t.settled = true;
            trainPrior(kind, t.chosenDodge, false);
        }
    }

    private void trainPrior(Kind kind, int action, boolean reward) {
        if (action < 0 || action >= DODGE_N) return;
        java.util.Arrays.fill(oneHot, 0f);
        oneHot[action] = 1f;
        dodgePrior[kind.ordinal()].train(oneHot, reward);
    }

    /** 弹在打到我之前会不会先撞墙：路径 = 当前位置 → 预测命中点。 */
    private boolean hitsWallFirst(Track t, Approach a, PathCheck occlusion) {
        if (occlusion == null || a.tti < 0) return false;
        double[] from = {t.pos[0], t.pos[1], t.pos[2]};
        return occlusion.blocked(from, a.at);
    }

    /** 某类型的预测波动带（格）。{@code ticks} 越远，累积的不确定越大。 */
    public double band(Kind kind, int ticks) {
        int k = kind.ordinal();
        int t = Math.max(1, Math.min(LOOKAHEAD, ticks));
        double b = Math.abs(bias[k][0]) + Math.abs(bias[k][1]) + Math.abs(bias[k][2]);
        double v = BAND_BASE + BAND_K * (sigma[k] * Math.sqrt(t) + b * t);
        return Math.min(BAND_CAP, v);
    }

    /** 还在跟踪的弹数量（指标用）。 */
    public int trackCount() {
        return tracks.size();
    }

    public double sigmaOf(Kind kind) {
        return sigma[kind.ordinal()];
    }

    public double gravCorrOf(Kind kind) {
        return gravCorr[kind.ordinal()];
    }

    public double dragCorrOf(Kind kind) {
        return dragCorr[kind.ordinal()];
    }

    public long samplesOf(Kind kind) {
        return samples[kind.ordinal()];
    }

    /** 躲避先验（只读拷贝），供指标与测试断言。 */
    public float[] dodgePriorOf(Kind kind) {
        return dodgePrior[kind.ordinal()].weights();
    }

    /** 清空全部跟踪与学习状态（换世界 / 重新接管）。 */
    public void reset() {
        tracks.clear();
        expired.clear();
        for (int i = 0; i < Ballistics.KIND_N; i++) {
            java.util.Arrays.fill(bias[i], 0.0);
            sigma[i] = SIGMA_FLOOR;
            gravCorr[i] = 0.0;
            dragCorr[i] = 0.0;
            samples[i] = 0;
            dodgePrior[i] = new LearningAutomaton(DODGE_N, 0.1f);
        }
        learnedTotal = 0;
    }

    private static void copyInto(Approach from, Approach to) {
        to.tti = from.tti;
        to.cpa = from.cpa;
        to.cpaTick = from.cpaTick;
        to.at[0] = from.at[0];
        to.at[1] = from.at[1];
        to.at[2] = from.at[2];
        to.closing = from.closing;
        to.endDist = from.endDist;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
