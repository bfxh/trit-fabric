package com.bfxh.trit.neo;

/**
 * 独立虚拟指针：AI 自己的「视角 / 准星」，<strong>完全不碰玩家的鼠标</strong>。
 *
 * <p>用户明确要求「收集信息不能影响我的鼠标指针，它必须有自己的独立指针」。这就是那个指针：
 * <ul>
 *   <li>战斗中 AI 用它持续旋转跟踪目标（玩家拉弓时 AI 帮着瞄）；</li>
 *   <li>落地后它自行扫视四周收集信息，玩家的鼠标纹丝不动；</li>
 *   <li>跟随锁定时记录「指针所指」作为跟随目标候选。</li>
 * </ul>
 *
 * <p>它是虚拟的：不直接改 {@code player.yRot/xRot}（那是玩家鼠标的所有权）。只有在玩家
 * 明确「把视角交给 AI」（如拉弓期间）时，执行层才会把它的目标角度应用到真实相机。
 */
public final class AiPointer {

    private float yaw;        // 当前水平朝向（度）
    private float pitch;      // 当前俯仰（度，负 = 上）
    private float targetYaw;
    private float targetPitch;

    public AiPointer() {
        this(0f, 0f);
    }

    public AiPointer(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = clampP(pitch);
        this.targetYaw = yaw;
        this.targetPitch = this.pitch;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    /** 直接设定当前与目标朝向（例如对齐到玩家视角起点）。 */
    public void set(float yaw, float pitch) {
        this.yaw = wrap(yaw);
        this.pitch = clampP(pitch);
        this.targetYaw = this.yaw;
        this.targetPitch = this.pitch;
    }

    /** 以「相对角差」设定目标（如某实体相对当前指针的 yaw/pitch 差）。 */
    public void aimRelative(float yawDelta, float pitchDelta) {
        this.targetYaw = wrap(this.yaw + yawDelta);
        this.targetPitch = clampP(this.pitch + pitchDelta);
    }

    /** 朝目标平滑转动 rate 度/拍，返回是否基本到位。 */
    public boolean stepToward(float rate) {
        this.yaw = wrap(this.yaw + clampAbs(targetYaw - this.yaw, rate));
        this.pitch = clampP(this.pitch + clampAbs(targetPitch - this.pitch, rate));
        return Math.abs(wrap(targetYaw - this.yaw)) < rate * 0.5f
                && Math.abs(targetPitch - this.pitch) < rate * 0.5f;
    }

    /** 在当前目标上再叠加一个旋转（用于扫视：连续左右转）。 */
    public void rotateBy(float dyaw, float dpitch) {
        this.targetYaw = wrap(this.targetYaw + dyaw);
        this.targetPitch = clampP(this.targetPitch + dpitch);
    }

    /** 相对某一中心做圆周扫视（落地收集信息用）。center=起点角度，sweep=半幅，phase=0..1。 */
    public void scanAround(float center, float sweep, float phase) {
        this.targetYaw = wrap(center + (float) Math.sin(phase * 2 * Math.PI) * sweep);
    }

    private static float wrap(float a) {
        while (a > 180f) a -= 360f;
        while (a <= -180f) a += 360f;
        return a;
    }

    private static float clampP(float p) {
        return Math.max(-89f, Math.min(89f, p));
    }

    private static float clampAbs(float v, float m) {
        return Math.max(-m, Math.min(m, v));
    }
}
