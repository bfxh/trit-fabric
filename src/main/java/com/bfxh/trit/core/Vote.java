package com.bfxh.trit.core;

/**
 * 行为模块投出的一票。
 *
 * <p>{@link #weight} 表达「这条意见有多重要」，而不是「有多确定」。
 * 确定性由 {@link #dir} 是否为 {@link Tri#ZERO} 表达。
 */
public final class Vote {
    public final Channel channel;
    public final Tri dir;
    /** 权重，&gt;= 0。 */
    public final int weight;
    /** 来源模块名，用于回放排查。 */
    public final String src;
    /**
     * 要求<strong>按住</strong>这个通道，而不是点一下。
     *
     * <p>只有 {@link Channel#ATTACK} 用得上：打怪要脉冲，砍树要一直按住 —— 松手就白砍了。
     */
    public final boolean hold;

    private Vote(Channel channel, Tri dir, int weight, String src, boolean hold) {
        this.channel = channel;
        this.dir = dir;
        this.weight = Math.max(0, weight);
        this.src = src;
        this.hold = hold;
    }

    public static Vote of(Channel channel, Tri dir, int weight, String src) {
        return new Vote(channel, dir, weight, src, false);
    }

    /** 按住不放的票。裁决结果仍是 POS，只是执行层不松手。 */
    public static Vote hold(Channel channel, int weight, String src) {
        return new Vote(channel, Tri.POS, weight, src, true);
    }

    /** 弃权票：把我这边的「信息不足」计入未知权重。 */
    public static Vote abstain(Channel channel, int weight, String src) {
        return new Vote(channel, Tri.ZERO, weight, src, false);
    }

    @Override
    public String toString() {
        return src + ":" + channel.key() + "=" + dir.ch() + "x" + weight + (hold ? "(hold)" : "");
    }
}
