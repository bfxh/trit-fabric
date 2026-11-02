package com.bfxh.trit.core;

/**
 * 三值决策状态机（移植自 {@code bfxh/trit-agent} 的 {@code fsm.rs}）。
 *
 * <p>结构分三层，全部以 {@link Tri} 为载体：
 * <ol>
 *   <li><strong>投票</strong>：每个次操作由若干行为模块独立投票。</li>
 *   <li><strong>裁决</strong>：按通道汇总，三条硬规则 —— 没人投有效票就松手；
 *       未知票权重压过已知票就弃权（这是「不猜」的具体实现）；迟滞防抖。</li>
 *   <li><strong>阶段机</strong>：转移条件用 K3 运算写，结果落在 ZERO 时保守站桩而不是莽。</li>
 * </ol>
 */
public final class Machine {

    /** 血量「临界带」：低于 LO 视为危险，LO..HI 视为不确定。 */
    private static final float LOW_BAND_LO = 0.35f;
    private static final float LOW_BAND_HI = 0.55f;

    /** 单通道裁决器（带迟滞与弃权）。 */
    private static final class ChannelCell {
        Tri last = Tri.ZERO;

        Tri decide(java.util.List<Vote> votes, Channel ch, boolean gate) {
            int knownW = 0;
            int unknownW = 0;
            int score = 0;
            for (Vote v : votes) {
                if (v.channel != ch) continue;
                if (v.dir == Tri.ZERO) {
                    unknownW += v.weight;
                } else {
                    knownW += v.weight;
                    score += v.dir.toInt() * v.weight;
                }
            }
            Tri out;
            if (!gate || knownW == 0) {
                out = Tri.ZERO;
            } else if (unknownW > knownW) {
                // 关键规则：信息不足时弃权，绝不把 Unknown 猜成动作
                out = Tri.ZERO;
            } else {
                int need = last.isKnown() ? ch.threshold() + ch.hysteresis() : ch.threshold();
                if (score >= need) {
                    out = Tri.POS;
                } else if (score <= -need) {
                    out = Tri.NEG;
                } else {
                    out = last;
                }
            }
            last = out;
            return out;
        }
    }

    private Phase phase = Phase.OFF;
    private boolean armed;
    private final ChannelCell[] cells = new ChannelCell[Channel.NCH];
    private long ticks;

    public Machine() {
        for (int i = 0; i < Channel.NCH; i++) cells[i] = new ChannelCell();
    }

    public Phase phase() {
        return phase;
    }

    public boolean armed() {
        return armed;
    }

    public long ticks() {
        return ticks;
    }

    public void setArmed(boolean a) {
        armed = a;
        if (!a) {
            phase = Phase.OFF;
            for (ChannelCell c : cells) c.last = Tri.ZERO;
        }
    }

    /** 是否交战中（供行为模块参考）。 */
    public boolean engaging() {
        return phase == Phase.ENGAGE;
    }

    /**
     * 推进一个决策周期。
     *
     * @param channelsEnabled 与 {@link Channel} 一一对应；false = 该次操作被人为关掉，通道强制 ZERO。
     */
    public Decision step(WorldView w, java.util.List<Vote> votes, boolean[] channelsEnabled) {
        ticks++;

        Phase[] ph = new Phase[1];
        Reason[] rs = new Reason[1];
        boolean[] rc = new boolean[1];
        nextPhase(w, ph, rs, rc);
        Phase cur = ph[0];

        // 除 OFF / RECOVER 外都允许行动；RECOVER 只点重生不按键
        boolean gate = cur == Phase.IDLE || cur == Phase.ENGAGE || cur == Phase.RETREAT;

        Decision d = new Decision();
        d.phase = cur;
        d.reason = rs[0];
        d.recoverClick = rc[0];

        java.util.List<Vote> used = new java.util.ArrayList<>(votes.size());
        for (Vote v : votes) {
            if (channelsEnabled[v.channel.index()]) used.add(v);
        }

        for (Channel ch : Channel.ALL) {
            d.dirs[ch.index()] = cells[ch.index()].decide(used, ch, gate);
        }

        // 撤退阶段：强制向后，且不接受前进投票
        if (cur == Phase.RETREAT) {
            d.dirs[Channel.FORWARD.index()] = Tri.NEG;
        }
        if (cur == Phase.RECOVER) {
            for (int i = 0; i < Channel.NCH; i++) d.dirs[i] = Tri.ZERO;
        }

        this.phase = cur;

        // 「按住」只看本拍实际投票，不看裁决后的 dirs。
        // 裁决器有迟滞，本拍没人投 POS 时 dirs 可能停在上一拍的 POS 上；
        // 如果照着 dirs 算 hold，扫视期间就会一直按住左键不放。
        boolean hold = false;
        if (gate && d.dirs[Channel.ATTACK.index()] == Tri.POS) {
            for (Vote v : used) {
                if (v.channel == Channel.ATTACK && v.dir == Tri.POS && v.hold) {
                    hold = true;
                    break;
                }
            }
        }
        d.attackHold = hold;
        return d;
    }

    /**
     * 只读地预判本周期阶段。行为模块需要「当前」阶段来决定投什么票，
     * 但阶段又由投票结果决定 —— 所以先 peek 再 step，同一套输入算两遍，
     * 结果必然一致（纯函数，无副作用），也顺带消掉了行为落后一拍的问题。
     */
    public Phase peek(WorldView w) {
        Phase[] ph = new Phase[1];
        Reason[] rs = new Reason[1];
        boolean[] rc = new boolean[1];
        nextPhase(w, ph, rs, rc);
        return ph[0];
    }

    private void nextPhase(WorldView w, Phase[] outPhase, Reason[] outReason, boolean[] outRecover) {
        if (w.humanTakeover || !armed) {
            outPhase[0] = Phase.OFF;
            outReason[0] = Reason.OFF;
            outRecover[0] = false;
            return;
        }
        if (w.dead) {
            outPhase[0] = Phase.RECOVER;
            outReason[0] = Reason.DEAD;
            outRecover[0] = true;
            return;
        }

        // 血量三值化：NEG = 危险，ZERO = 临界/未知，POS = 安全
        Tri hp;
        if (w.hp == null) {
            hp = Tri.ZERO;
        } else if (w.hp <= LOW_BAND_LO) {
            hp = Tri.NEG;
        } else if (w.hp < LOW_BAND_HI) {
            hp = Tri.ZERO;
        } else {
            hp = Tri.POS;
        }

        // 刚交过手会把「未知威胁」抬成「有威胁」；K3 的 or 正好是这么算的
        Tri threat = w.contact ? w.threat.or(Tri.POS) : w.threat;

        // 转移表本身就是个 3x3 的三值函数。写成表而不是布尔表达式，
        // 是因为 K3 的 and 有 NEG 吸收性，直接套会得出「健康 → 不交战」这种错结论。
        if (threat == Tri.POS) {
            if (hp == Tri.NEG) {
                outPhase[0] = Phase.RETREAT;
                outReason[0] = Reason.LOW_HP;
                outRecover[0] = false;
            } else {
                outPhase[0] = Phase.ENGAGE;
                outReason[0] = w.contact ? Reason.CONTACT : Reason.THREAT;
                outRecover[0] = false;
            }
            return;
        }
        if (threat == Tri.ZERO) {
            outPhase[0] = Phase.IDLE;
            outReason[0] = Reason.UNKNOWN;
            outRecover[0] = false;
            return;
        }
        outPhase[0] = Phase.IDLE;
        outReason[0] = Reason.NO_THREAT;
        outRecover[0] = false;
    }
}
