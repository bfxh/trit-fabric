package com.bfxh.trit.neo;

import java.util.HashMap;
import java.util.Map;

/**
 * 多轴好感度（用户对「好感度机制」的要求）。
 *
 * <p>不是单一好感条。对每个实体维护 4 个独立漂移的轴：
 * <ul>
 *   <li>0 友善 (friend)：喜欢 / 厌恶</li>
 *   <li>1 敬畏 (fear)：害怕 / 无惧</li>
 *   <li>2 尊重 (respect)：佩服 / 轻视</li>
 *   <li>3 信任 (trust)：信赖 / 戒备</li>
 * </ul>
 * 各轴用事件施加增量、随时间衰减、带迟滞（不会因一次小事在正负间反复横跳）。
 * 参考 Scarlet Hollow 多轴关系系统 + 外交声望的阈值/日志做法，但保持零成本。
 */
public final class Affinity {

    /** 轴索引常量。 */
    public static final int FRIEND = 0, FEAR = 1, RESPECT = 2, TRUST = 3;
    public static final int AXES = 4;

    /** 关系阶段（迟滞后的离散状态）。 */
    public enum Bond {
        HOSTILE(-1), WARY(0), NEUTRAL(1), FRIENDLY(2), DEVOTED(3);
        final int level;

        Bond(int level) {
            this.level = level;
        }

        public int level() {
            return level;
        }
    }

    private static final class Record {
        float[] a = new float[AXES];     // 各轴当前值 [-1,1]
        long lastSeen;
        String lastNote;
    }

    private final Map<Integer, Record> map = new HashMap<>();
    private final float decayPerTick;

    /**
     * @param decayPerSec 每轴每秒自然回归 0 的速率（用于「时间长了关系变淡」）。
     */
    public Affinity(float decayPerSec) {
        this.decayPerTick = decayPerSec / 20f;
    }

    private Record get(int id) {
        return map.computeIfAbsent(id, k -> new Record());
    }

    /** 对某个实体施加一个事件：axis 轴 +Δ（负为惩罚），并记录一行日志。 */
    public void event(int id, int axis, float delta, String note) {
        if (axis < 0 || axis >= AXES) throw new IllegalArgumentException("axis");
        Record r = get(id);
        r.a[axis] = clamp(r.a[axis] + delta);
        r.lastSeen = System.currentTimeMillis();
        if (note != null) r.lastNote = note;
    }

    /** 读取某实体某轴的当前值 [-1,1]。 */
    public float axis(int id, int axis) {
        return get(id).a[axis];
    }

    public String lastNote(int id) {
        Record r = map.get(id);
        return r == null ? null : r.lastNote;
    }

    /**
     * 综合「值不值得保护 / 跟随」分：友善 + 信任 − 敬畏。
     * 用来让 AI 在战斗时优先保护「好感高」的目标、避开「怕」的目标。
     */
    public float bond(int id) {
        Record r = get(id);
        return r.a[FRIEND] * 0.5f + r.a[TRUST] * 0.5f - r.a[FEAR] * 0.5f;
    }

    /** 把综合分映射成离散关系阶段（带迟滞：升级难、降级也难）。 */
    public Bond bondStage(int id) {
        float b = bond(id);
        if (b <= -0.5f) return Bond.HOSTILE;
        if (b <= -0.15f) return Bond.WARY;
        if (b < 0.15f) return Bond.NEUTRAL;
        if (b < 0.5f) return Bond.FRIENDLY;
        return Bond.DEVOTED;
    }

    /** 每拍衰减（让关系随时间自然淡化）。 */
    public void decay(long ticks) {
        float d = decayPerTick * ticks;
        for (Record r : map.values()) {
            for (int i = 0; i < AXES; i++) {
                if (r.a[i] > 0f) r.a[i] = Math.max(0f, r.a[i] - d);
                else if (r.a[i] < 0f) r.a[i] = Math.min(0f, r.a[i] + d);
            }
        }
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
