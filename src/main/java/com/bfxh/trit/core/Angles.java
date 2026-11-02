package com.bfxh.trit.core;

/**
 * 朝向差计算。抽成纯函数以便脱离游戏环境单测 —— 这里的符号约定一旦搞反，
 * AI 会朝反方向转，是那种「跑起来才发现，但看日志很难定位」的错。
 *
 * <p>MC 的朝向约定：
 * <ul>
 *   <li>水平 {@code forward = (-sin(yaw), cos(yaw))}，yaw = 0 面朝 +Z（南）。</li>
 *   <li>俯仰 pitch 为负表示向上看。</li>
 * </ul>
 */
public final class Angles {

    private Angles() {
    }

    /**
     * 目标相对当前朝向的水平角差。
     *
     * @return 度，<strong>正 = 目标在右边</strong>（需要增大 yaw）。
     */
    public static float yawDelta(float myYaw, double myX, double myZ, double tx, double tz) {
        double dx = tx - myX;
        double dz = tz - myZ;
        if (dx == 0.0 && dz == 0.0) return 0f;
        double want = Math.toDegrees(Math.atan2(-dx, dz));
        return (float) normalize(want - myYaw);
    }

    /**
     * 目标相对当前视线的俯仰角差。
     *
     * @return 度，<strong>正 = 目标在下方</strong>（需要增大 pitch，也就是下看）。
     */
    public static float pitchDelta(float myPitch, double myEyeY,
                                   double myX, double myZ,
                                   double tx, double ty, double tz) {
        double dx = tx - myX;
        double dz = tz - myZ;
        double dy = ty - myEyeY;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double want = -Math.toDegrees(Math.atan2(dy, Math.max(horiz, 1e-4)));
        return (float) normalize(want - myPitch);
    }

    /** 归一化到 (-180, 180]。 */
    public static double normalize(double a) {
        while (a > 180.0) a -= 360.0;
        while (a <= -180.0) a += 360.0;
        return a;
    }
}
