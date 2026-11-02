package com.bfxh.trit.core;

/** 本帧阶段判定的理由，便于回放排查。 */
public enum Reason {
    OFF("off", "未接管"),
    DEAD("dead", "已死亡"),
    LOW_HP("low_hp", "血量过低"),
    CONTACT("contact", "刚交手"),
    THREAT("threat", "有威胁"),
    NO_THREAT("no_threat", "无威胁"),
    UNKNOWN("unknown", "信息不足");

    private final String id;
    private final String zh;

    Reason(String id, String zh) {
        this.id = id;
        this.zh = zh;
    }

    public String id() {
        return id;
    }

    public String zh() {
        return zh;
    }
}
