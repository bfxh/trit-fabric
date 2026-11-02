package com.bfxh.trit.neo;

/**
 * 极小的「神经元」：固定拓扑、无反向传播。
 *
 * <p>一次前向 = 加权求和 + 激活，O(n) 次浮点运算，零额外依赖、零 GPU。
 * 把很多这种单元并联/串联起来，就构成用户要的「很多小神经元」层 —— 它们把多路感官
 * 特征融成一个连续的置信度/激活值，再喂给 {@code Machine} 的投票通道做软决策。
 *
 * <p>这是 neuro-symbolic 混合里「神经」那一半：纯数值、可解释（权重就是贡献方向）、
 * 运行时零成本。它<strong>不</strong>参与训练，权重要么写死、要么由
 * {@link LearningAutomaton} 在线用奖励/惩罚更新。
 */
public final class Neuron {

    /** 激活函数。 */
    public enum Activation {
        /** 恒等，用于回归/门控。 */
        LINEAR,
        /** 双曲正切，输出 [-1,1]，最适合三值友好的软信号。 */
        TANH,
        /** 逻辑，输出 [0,1]。 */
        SIGMOID,
        /** 整流，用于非负增益。 */
        RELU
    }

    private final float[] w;
    private final float bias;
    private final float gain;
    private final Activation act;

    public Neuron(float[] weights, float bias, float gain, Activation act) {
        this.w = weights.clone();
        this.bias = bias;
        this.gain = gain;
        this.act = act == null ? Activation.TANH : act;
    }

    /** 前向。x 维度必须与权重一致。 */
    public float forward(float[] x) {
        if (x.length != w.length) {
            throw new IllegalArgumentException("dim mismatch: in=" + x.length + " w=" + w.length);
        }
        float s = bias;
        for (int i = 0; i < w.length; i++) {
            s += w[i] * x[i];
        }
        s *= gain;
        return activate(s);
    }

    private float activate(float s) {
        return switch (act) {
            case LINEAR -> s;
            case TANH -> (float) Math.tanh(s);
            case SIGMOID -> (float) (1f / (1f + Math.exp(-s)));
            case RELU -> Math.max(0f, s);
        };
    }

    public int dim() {
        return w.length;
    }

    /** 第 i 个权重的贡献方向（只读）。 */
    public float weight(int i) {
        return w[i];
    }
}
