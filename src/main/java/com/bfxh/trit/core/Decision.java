package com.bfxh.trit.core;

/** 一拍的裁决结果。 */
public final class Decision {
    /** 每个通道的方向。 */
    public final Tri[] dirs = new Tri[Channel.NCH];
    public Phase phase = Phase.OFF;
    /** 需要点一次重生按钮。 */
    public boolean recoverClick;
    /**
     * 左键要<strong>一直按住</strong>（砍树 / 挖矿），而不是点一下。
     *
     * <p>单独一个字段而不是塞进 {@link #dirs}：{@code dirs} 是三值的「方向」，
     * 「按住」是执行层的时序问题，两者正交 —— 打怪是 POS + 点一下，
     * 砍树是 POS + 按住，方向一样。
     */
    public boolean attackHold;
    /** 本帧阶段判定的理由。 */
    public Reason reason = Reason.OFF;

    public Decision() {
        for (int i = 0; i < Channel.NCH; i++) dirs[i] = Tri.ZERO;
    }

    /** 紧凑编码，用于回放：{@code engage|+0+000-}。 */
    public String encode() {
        StringBuilder s = new StringBuilder(24);
        s.append(phase.id()).append('|');
        for (Channel c : Channel.ALL) s.append(dirs[c.index()].ch());
        return s.toString();
    }

    @Override
    public String toString() {
        return encode() + " " + reason.id();
    }
}
