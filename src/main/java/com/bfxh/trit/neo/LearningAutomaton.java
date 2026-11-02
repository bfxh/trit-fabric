package com.bfxh.trit.neo;

/**
 * 学习自动机（Tsetlin 风格的奖励/惩罚更新，<strong>没有反向传播</strong>）。
 *
 * <p>每个特征一个权重 ∈ [-1, 1]，根据「奖励 / 惩罚」做<strong>符号级</strong>更新：
 * 奖励时朝该特征输入的符号方向推一点，惩罚时反推。单次更新 O(n) 且只用符号判断，
 * 运行时零成本 —— 这正是用户要的「零成本、不是离谱 LLM」的学习方式。
 *
 * <p>用途：让某些软决策随时间自适应（例如「哪种走位更容易躲开这次攻击」），
 * 但永远落在可解释的有限权重空间里，不会变成不可控的黑箱。
 */
public final class LearningAutomaton {

    private final float[] w;
    private final float lr;
    private long updates;

    public LearningAutomaton(int n, float lr) {
        this.w = new float[n];
        this.lr = (lr > 0f && lr <= 1f) ? lr : 0.05f;
    }

    public LearningAutomaton(int n) {
        this(n, 0.05f);
    }

    /** 评一个输入 → 当前离散输出（+1 偏正 / -1 偏负 / 0 不确定）。 */
    public int eval(float[] x) {
        float s = 0f;
        for (int i = 0; i < w.length; i++) {
            s += w[i] * x[i];
        }
        return s > 0f ? 1 : (s < 0f ? -1 : 0);
    }

    /** 用一条样本更新：reward=true 奖励，false 惩罚。正输入加强、负输入削弱。 */
    public void train(float[] x, boolean reward) {
        float dir = reward ? 1f : -1f;
        for (int i = 0; i < w.length; i++) {
            float sign = Math.signum(x[i]);
            if (sign == 0f) continue;
            w[i] = clamp(w[i] + dir * sign * lr);
        }
        updates++;
    }

    /** 当前权重（拷贝）。 */
    public float[] weights() {
        return w.clone();
    }

    public long updates() {
        return updates;
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    // ==================================================================
    // 3c「收集信息分支」要记的那一行：判断一个候选「能不能成为状态机」。
    // 明确无神经元参与 —— 纯启发式记录，便于回放 / 调试。
    // ==================================================================

    /**
     * 记录一行候选是否适合用状态机表达。
     *
     * @param distinctInputs       可枚举的离散输入种类数（状态机擅长有限输入）
     * @param observableTransition 转移是否可观测（能确定「从 A 到 B 因为 X」）
     * @param repeatable           是否能重复触发（非一次性事件）
     * @return 是否适合状态机。不适合的就留给 向量知识库 / 小神经元。
     */
    public static boolean stateMachineEligible(int distinctInputs, boolean observableTransition, boolean repeatable) {
        // REC: candidate stateMachineEligible = (distinctInputs>0 && observableTransition && repeatable)  [neuron-free heuristic]
        return distinctInputs > 0 && observableTransition && repeatable;
    }
}
