package com.bfxh.trit.core;

/**
 * 环盘菜单选出的「主动模式」。
 *
 * <p>和 {@link Phase}（由威胁/血量推导的瞬时阶段）是两回事：Phase 是状态机内部状态，
 * Mode 是玩家给 AI 下的「总体任务」。两者正交 —— 无论什么 Mode，遇到威胁都会
 * 自动转入战斗/撤退（自保优先），只是平时「没事干的时候该主动干什么」不同。
 */
public enum Mode {
    /** 交还控制权，AI 不主动做任何事（但仍会自保）。 */
    OFF(),
    /** 到处巡逻探索。 */
    PATROL(),
    /** 主动找树/矿采集。 */
    FORAGE(),
    /** 主动猎杀敌对生物。 */
    COMBAT(),
    /** 铺路/填坑/搭桥。 */
    BUILD(),
    /** 跟着锚点（玩家）走。 */
    FOLLOW();

    /** 菜单里展示的中文名。 */
    public String zh() {
        return switch (this) {
            case OFF -> "交还";
            case PATROL -> "巡逻";
            case FORAGE -> "采集";
            case COMBAT -> "战斗";
            case BUILD -> "建造";
            case FOLLOW -> "跟随";
        };
    }

    /** 单字符代号，用于 action bar。 */
    public String id() {
        return switch (this) {
            case OFF -> "off";
            case PATROL -> "pat";
            case FORAGE -> "for";
            case COMBAT -> "cmb";
            case BUILD -> "bld";
            case FOLLOW -> "fol";
        };
    }
}
