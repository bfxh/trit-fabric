package com.bfxh.trit.core;

/** 状态机的阶段。 */
public enum Phase {
    /** AI 关闭，全部松手 */
    OFF("off", "停机"),
    /** 无事可做，原地待命 */
    IDLE("idle", "待命"),
    /** 交战 */
    ENGAGE("engage", "交战"),
    /** 撤退 */
    RETREAT("retreat", "撤退"),
    /** 死亡 → 点重生 */
    RECOVER("recover", "重生");

    private final String id;
    private final String zh;

    Phase(String id, String zh) {
        this.id = id;
        this.zh = zh;
    }

    /**
     * 写进日志的段名。固定宽度 ASCII，不随语言变化。
     */
    public String id() {
        return id;
    }

    /** 给人看的中文名。 */
    public String zh() {
        return zh;
    }
}
