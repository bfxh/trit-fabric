package com.bfxh.trit.core;

/**
 * 平衡三进制的一个 trit，以及 Kleene 三值逻辑 K3。
 *
 * <p>与 Rust 侧 {@code bfxh/trit-agent} 的 {@code Trit} 定义逐条对齐：
 * {@code NEG = -1}，{@code ZERO = 0}（未知 / 弃权），{@code POS = +1}。
 *
 * <p>整个状态机的安全底座就在 {@link #and} 和 {@link #or} 上：
 * <strong>未知参与运算时，结论无法确定就返回未知，绝不把 Unknown 猜成 Pos。</strong>
 * 这条规则一旦破坏，AI 就会在感知缺失时凭空做出动作。
 */
public enum Tri {
    /** -1：反对 / 后退 / 明确否定 */
    NEG(-1),
    /** 0：未知 / 弃权 / 保持原状 */
    ZERO(0),
    /** +1：支持 / 前进 / 明确肯定 */
    POS(1);

    private final int v;

    Tri(int v) {
        this.v = v;
    }

    /** 转成 -1 / 0 / 1。 */
    public int toInt() {
        return v;
    }

    public float toFloat() {
        return v;
    }

    /** 从任意整数构造（只看符号）。 */
    public static Tri fromInt(int x) {
        if (x < 0) return NEG;
        if (x > 0) return POS;
        return ZERO;
    }

    /** 从布尔构造：true = POS，false = NEG。只在信息确实已知时用。 */
    public static Tri of(boolean b) {
        return b ? POS : NEG;
    }

    /** 是否已知（不为未知）。 */
    public boolean isKnown() {
        return this != ZERO;
    }

    /** K3 否定：未知的非仍是未知。 */
    public Tri not() {
        return switch (this) {
            case NEG -> POS;
            case ZERO -> ZERO;
            case POS -> NEG;
        };
    }

    /** K3 合取：一方为 NEG 立刻 NEG；否则有一方未知就未知。 */
    public Tri and(Tri o) {
        if (this == NEG || o == NEG) return NEG;
        if (this == ZERO || o == ZERO) return ZERO;
        return POS;
    }

    /** K3 析取：一方为 POS 立刻 POS；否则有一方未知就未知。 */
    public Tri or(Tri o) {
        if (this == POS || o == POS) return POS;
        if (this == ZERO || o == ZERO) return ZERO;
        return NEG;
    }

    /** K3 蕴含：{@code ¬a ∨ b}。 */
    public Tri implies(Tri o) {
        return not().or(o);
    }

    /** 三值多数表决，等价于 {@code (a∧b)∨(b∧c)∨(c∧a)}。 */
    public static Tri majority(Tri a, Tri b, Tri c) {
        return a.and(b).or(b.and(c)).or(c.and(a));
    }

    /** 符号字符，便于日志与回放。 */
    public char ch() {
        return switch (this) {
            case NEG -> '-';
            case ZERO -> '0';
            case POS -> '+';
        };
    }

    @Override
    public String toString() {
        return String.valueOf(ch());
    }
}
