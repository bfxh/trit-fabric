package com.bfxh.trit.core;

/**
 * 裁决通道：每个「次操作」一条。行为模块独立往这些通道上投票，
 * 由 {@link Machine} 汇总裁决。
 */
public enum Channel {
    /** 前后：POS = W，NEG = S */
    FORWARD(0, "move", 1, 1),
    /** 左右：POS = D，NEG = A */
    STRAFE(1, "strafe", 1, 1),
    /** 水平视角：POS = 右转，NEG = 左转 */
    YAW(2, "yaw", 2, 1),
    /** 垂直视角：POS = 下看，NEG = 上看 */
    PITCH(3, "pitch", 2, 2),
    /** 左键 */
    ATTACK(4, "attack", 1, 1),
    /** 空格 */
    JUMP(5, "jump", 1, 2),
    /** 右键 / 使用 */
    USE(6, "use", 2, 2);

    public static final int NCH = 7;

    private final int index;
    private final String name;
    private final int threshold;
    private final int hysteresis;

    Channel(int index, String name, int threshold, int hysteresis) {
        this.index = index;
        this.name = name;
        this.threshold = threshold;
        this.hysteresis = hysteresis;
    }

    public int index() {
        return index;
    }

    public String key() {
        return name;
    }

    /** 进入阈值。 */
    public int threshold() {
        return threshold;
    }

    /**
     * 迟滞量。
     *
     * <p>注意：迟滞会把「需要翻过去的总分」抬高到 {@code 阈值 + 迟滞}（当上一拍已是已知值）。
     * 如果某个通道每拍都有固定权重的票，而该权重小于这个和，这个通道就会永远停在
     * 上一拍的值上 —— 表现为「左右换边失效」「视角不转」。所以每拍都有人投票的通道
     * （FORWARD / STRAFE / YAW），迟滞不能比票权重大。
     */
    public int hysteresis() {
        return hysteresis;
    }

    public static final Channel[] ALL = values();
}
