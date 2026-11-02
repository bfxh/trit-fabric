package com.bfxh.trit.core;

/**
 * 摄像头预算闸门：AI 自己选择调不调，但<strong>每一次视角写入都要过这里</strong>。
 *
 * <p>用户口径：「我调摄像头不影响它，你自己选择调与不调；可以加限制，看限制到哪一步。」
 * 所以这里不是「有/无」开关，而是一档一档的<em>幅度上限</em>，并且锚在玩家当前的视角上：
 *
 * <ul>
 *   <li>{@code OFF} —— 视角永远归玩家，AI 一个度都不写。</li>
 *   <li>{@code NUDGE} —— 允许 ±15° 的瞬时修正（「上调一调摄像头看清大概范围」），
 *       用完自己回到锚点，不长期占用。</li>
 *   <li>{@code TRACK} —— 允许 ±45° 的持续跟踪（拉弓/交战瞄准够用），超出预算就停手，
 *       改用独立指针与记忆，而不是硬把镜头甩过去。</li>
 *   <li>{@code FREE} —— 不限幅（改造前的旧行为）。</li>
 * </ul>
 *
 * <p><strong>玩家动镜头不影响 AI 的判断</strong>：{@link #observeCamera} 每拍把相机变化
 * 拆成「AI 自己写的」和「玩家动的」两部分，只有后者会把锚点带走。于是你转到哪，
 * AI 的预算就从哪开始算，既不会跟你抢，也不会因为你转身就失去参照。
 */
public final class CameraBudget {

    /** 限制档位。 */
    public enum Level {
        OFF(0f, "不还视角"),
        NUDGE(15f, "微调"),
        TRACK(45f, "跟踪"),
        FREE(360f, "自由");

        public final float limit;
        public final String zh;

        Level(float limit, String zh) {
            this.limit = limit;
            this.zh = zh;
        }

        public Level next() {
            return switch (this) {
                case OFF -> NUDGE;
                case NUDGE -> TRACK;
                case TRACK -> FREE;
                case FREE -> OFF;
            };
        }
    }

    /** 无归位需求时的回中速率（度/拍）。取 2 是为了守住 R5 验收的「视角变化 ≤2°/拍」。 */
    private static final float RECENTER_RATE = 2f;
    /** 小于这个偏差就不回中了，避免在死区里来回抖。 */
    private static final float RECENTER_DEADZONE = 2f;

    private Level level = Level.TRACK;
    private float anchorYaw;
    private float anchorPitch;
    private float lastYaw;
    private float lastPitch;
    private boolean anchored;
    /** 本拍 AI 实际写出去的视角增量（绝对值），供指标与测试断言「≤2°/拍」。 */
    private float appliedYaw;
    private float appliedPitch;
    /** 累计被预算夹掉的度数：限制真起作用了才有证据，不能只看「没转」。 */
    private double clippedTotal;
    private long clippedTicks;

    /** 当前档位。 */
    public Level level() {
        return level;
    }

    /** 切到下一档（循环），返回新档位。 */
    public Level cycle() {
        level = level.next();
        return level;
    }

    public void setLevel(Level l) {
        level = l == null ? Level.TRACK : l;
    }

    /** 视角是否已建立锚点（没锚点时先吸收一次，不写入）。 */
    public boolean isAnchored() {
        return anchored;
    }

    /**
     * 夹住一次水平写入。
     *
     * @param curYaw      当前真实视角
     * @param wantedDelta AI 想转的增量（度）
     * @return 实际允许写入的增量；0 = 预算用完或被关掉
     */
    public float clampYaw(float curYaw, float wantedDelta) {
        if (!anchored || level == Level.OFF || wantedDelta == 0f) {
            return 0f;
        }
        float dev = wrap(curYaw - anchorYaw);
        float room = level.limit - Math.abs(dev);
        if (room <= 0f) {
            clippedTotal += Math.abs(wantedDelta);
            clippedTicks++;
            return 0f;
        }
        float out = Math.max(-room, Math.min(room, wantedDelta));
        if (Math.abs(out) < Math.abs(wantedDelta)) {
            clippedTotal += Math.abs(wantedDelta) - Math.abs(out);
            clippedTicks++;
        }
        return out;
    }

    /** 夹住一次俯仰写入。俯仰有 ±90 硬边界，由调用方自己夹。 */
    public float clampPitch(float curPitch, float wantedDelta) {
        if (!anchored || level == Level.OFF || wantedDelta == 0f) {
            return 0f;
        }
        float dev = curPitch - anchorPitch;
        float room = level.limit - Math.abs(dev);
        if (room <= 0f) {
            clippedTotal += Math.abs(wantedDelta);
            clippedTicks++;
            return 0f;
        }
        float out = Math.max(-room, Math.min(room, wantedDelta));
        if (Math.abs(out) < Math.abs(wantedDelta)) {
            clippedTotal += Math.abs(wantedDelta) - Math.abs(out);
            clippedTicks++;
        }
        return out;
    }

    /**
     * AI 本拍没有任何视角意图时，把镜头往锚点收（「用完就还」）。
     *
     * @return 建议写入的增量；0 = 已经在锚点上、或档位不允许
     */
    public float recenterYaw(float curYaw) {
        if (!anchored || level == Level.OFF || level == Level.FREE) return 0f;
        float dev = wrap(curYaw - anchorYaw);
        if (Math.abs(dev) <= RECENTER_DEADZONE) return 0f;
        return clampAbs(-dev, RECENTER_RATE);
    }

    public float recenterPitch(float curPitch) {
        if (!anchored || level == Level.OFF || level == Level.FREE) return 0f;
        float dev = curPitch - anchorPitch;
        if (Math.abs(dev) <= RECENTER_DEADZONE) return 0f;
        return clampAbs(-dev, RECENTER_RATE);
    }

    /**
     * 每拍喂一次真实相机与 AI 本拍写出去的量。
     *
     * <p>拆法：相机总变化 = AI 写的 + 玩家写的。玩家那部分进锚点，AI 那部分不进。
     * 所以玩家转镜头 ⇒ 锚点跟着走（AI 的预算重新起算，不跟你抢）；
     * AI 转镜头 ⇒ 锚点不动（偏差累计，撞到上限就被夹掉）。
     */
    public void observeCamera(float yaw, float pitch, float aiYawDelta, float aiPitchDelta) {
        if (!anchored) {
            anchorYaw = yaw;
            anchorPitch = pitch;
            lastYaw = yaw;
            lastPitch = pitch;
            anchored = true;
            return;
        }
        float fromPlayerYaw = wrap(yaw - lastYaw) - aiYawDelta;
        float fromPlayerPitch = (pitch - lastPitch) - aiPitchDelta;
        anchorYaw = wrap(anchorYaw + fromPlayerYaw);
        anchorPitch = clampP(anchorPitch + fromPlayerPitch);
        lastYaw = yaw;
        lastPitch = pitch;
        appliedYaw = aiYawDelta;
        appliedPitch = aiPitchDelta;
    }

    /** 重新接管 / 换世界：丢弃锚点，下一拍从玩家当前视角起算。 */
    public void reanchor() {
        anchored = false;
        clippedTotal = 0;
        clippedTicks = 0;
    }

    /** 当前偏差（度，带符号）：正 = 在锚点右侧 / 下方。 */
    public float deviationYaw() {
        return anchored ? wrap(lastYaw - anchorYaw) : 0f;
    }

    public float deviationPitch() {
        return anchored ? lastPitch - anchorPitch : 0f;
    }

    /** 本拍 AI 写出去的视角增量（绝对值，度）。 */
    public float appliedYaw() {
        return Math.abs(appliedYaw);
    }

    public float appliedPitch() {
        return Math.abs(appliedPitch);
    }

    /** 被预算夹掉的累计度数与被夹的拍数（证明限制真在起作用）。 */
    public double clippedTotal() {
        return clippedTotal;
    }

    public long clippedTicks() {
        return clippedTicks;
    }

    /** 是否允许「自由扫视」这类非瞄准的转动（只有 TRACK/FREE 允许）。 */
    public boolean allowsFreeLook() {
        return level == Level.TRACK || level == Level.FREE;
    }

    /** 是否允许请求切第三人称换更大范围（第一↔第三）。 */
    public boolean allowsPerspectiveSwitch() {
        return level == Level.TRACK || level == Level.FREE;
    }

    private static float wrap(float a) {
        while (a > 180f) a -= 360f;
        while (a <= -180f) a += 360f;
        return a;
    }

    private static float clampAbs(float v, float m) {
        return Math.max(-m, Math.min(m, v));
    }

    private static float clampP(float p) {
        return Math.max(-89f, Math.min(89f, p));
    }
}
