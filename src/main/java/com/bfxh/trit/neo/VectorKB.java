package com.bfxh.trit.neo;

import java.util.Arrays;

/**
 * 极简向量知识库：存若干「经验」特征向量，按余弦相似度召回最近的一条。
 *
 * <p>固定容量的环形缓冲，只在写入时分配新数组，热点召回路径零分配。用于表达
 * 「熟悉度 / 是否见过此类情况 / 这次落地和哪次类似」。它是 neuro-symbolic 混合里
 * 「向量记忆」那一半，配合 {@link Affinity} 与状态机一起做分类决策。
 *
 * <p>零成本：召回是 O(容量 × 维度) 的浮点运算，容量取几十到几百足够，不掉帧。
 */
public final class VectorKB {

    private final int dim;
    private final float[][] bank;
    private final int[] tag;
    private int head;
    private int size;

    /**
     * @param dim      向量维度（特征数）
     * @param capacity 最多记住多少条
     */
    public VectorKB(int dim, int capacity) {
        if (dim <= 0 || capacity <= 0) throw new IllegalArgumentException("dim/capacity must be > 0");
        this.dim = dim;
        this.bank = new float[capacity][dim];
        this.tag = new int[capacity];
        this.head = 0;
        this.size = 0;
    }

    /** 存入一条经验，带分类标签（例如实体类型 id、地形类别）。 */
    public void store(float[] v, int tag) {
        if (v.length != dim) throw new IllegalArgumentException("dim mismatch");
        float[] slot = bank[head];
        System.arraycopy(v, 0, slot, 0, dim);
        this.tag[head] = tag;
        head = (head + 1) % bank.length;
        if (size < bank.length) size++;
    }

    /**
     * 召回最相似的经验。
     *
     * @return {@code [tag, similarity×100]}，空库返回 {@code [-1, 0]}。
     */
    public int[] recall(float[] v) {
        if (size == 0) return new int[]{-1, 0};
        float best = -2f;
        int bestTag = -1;
        for (int i = 0; i < size; i++) {
            float c = cosine(bank[i], v);
            if (c > best) {
                best = c;
                bestTag = tag[i];
            }
        }
        return new int[]{bestTag, (int) (best * 100f)};
    }

    public int size() {
        return size;
    }

    public int dim() {
        return dim;
    }

    private static float cosine(float[] a, float[] b) {
        float dot = 0f, na = 0f, nb = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        float d = (float) Math.sqrt(na) * (float) Math.sqrt(nb);
        return d < 1e-6f ? 0f : dot / d;
    }

    /** 仅供测试：导出一条向量。 */
    float[] peek(int i) {
        return bank[i].clone();
    }

    /** 仅供测试：比较两个向量是否近似相等。 */
    static boolean approx(float[] a, float[] b, float eps) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > eps) return false;
        }
        return true;
    }
}
