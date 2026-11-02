package com.bfxh.trit.behave;

import com.bfxh.trit.core.Machine;
import com.bfxh.trit.core.Phase;
import com.bfxh.trit.core.Vote;
import com.bfxh.trit.core.WorldView;

import java.util.List;

/** 一个行为模块：看感知快照，往通道上投票。 */
public interface Behavior {

    String name();

    /** 把本拍的票写进 {@code out}。不投票就等于「我这边没意见」。 */
    void vote(Ctx c, List<Vote> out);

    /** 投票时的上下文。 */
    final class Ctx {
        public final WorldView w;
        public final Phase phase;
        public final Machine machine;
        public final long tick;

        public Ctx(WorldView w, Phase phase, Machine machine, long tick) {
            this.w = w;
            this.phase = phase;
            this.machine = machine;
            this.tick = tick;
        }
    }
}
