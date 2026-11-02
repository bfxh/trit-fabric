package com.bfxh.trit.core;

/**
 * 一拍感知快照。
 *
 * <p><strong>所有字段都允许「未知」</strong>，用 {@code null} 表示（对应 Rust 侧的
 * {@code Option}）。这个区分是整个 AI 不犯傻的关键：把「不知道」当成「没有」，
 * AI 就会以全速撞墙，或者无视看不见的敌人。
 *
 * <p>和 Rust 侧最大的差别：这一版感知<strong>直接从游戏世界读</strong>，
 * 不走数据包、不走聊天栏文本解析，所以距离和方位是连续值，不是 8 方向 3 档的量化结果。
 */
public final class WorldView {

    /** 血量比例 0..1，null = 未知。 */
    public Float hp;
    /** 感知到的威胁：POS 明确有 / ZERO 不确定 / NEG 明确没有。 */
    public Tri threat = Tri.ZERO;
    /** 最近是否交过手（带衰减）。 */
    public boolean contact;
    /** 最近敌对生物的距离（格）。null = 没感知到距离，<strong>不是「没有敌人」</strong>。 */
    public Float dist;
    /** 爆炸 / 火焰危险：POS 必须躲，NEG 明确安全，ZERO 边缘或未知。 */
    public Tri hazard = Tri.ZERO;
    /** 附近敌对生物数量。null = 不知道有几只。 */
    public Integer enemies;
    /** 饱食度 0..20。null = 未知。 */
    public Integer food;
    /** 最近原木的距离（格）。null = 视野内没有。 */
    public Float wood;
    /** 最近矿石的距离（格）。null = 视野内没有。 */
    public Float ore;
    /**
     * 八方位墙体掩码，bit i = 第 i 个方位（0=正前，顺时针 45° 一格）有障碍。
     * null = 没扫描过，<strong>不知道有没有墙</strong>。
     */
    public Integer wall;
    /** 准星正对方块的种类名（挖矿挖的就是它）。null = 没指向方块。 */
    public String blkFront;
    /** 脚下踩着的方块种类名。挖矿最怕的就是把这块挖了。 */
    public String blkBelow;
    /** 正前方是一格高的台阶（能跳过去）吗？null = 不知道。 */
    public Boolean step;
    /** 前方是否有深坑（掉下去会摔）。null = 不知道。 */
    public Boolean pit;
    /** 视线俯仰角（度，负 = 下看）。null = 没读到。 */
    public Float pitch;
    /** 当前水平朝向（度）。null = 没读到。 */
    public Float yaw;
    /** 是否卡住（位移长时间为 0）。 */
    public boolean stuck;
    /** 当前坐标。null = 还没读到过。 */
    public double[] pos;
    public boolean dead;
    /** 人类按下 F8 关掉 AI。 */
    public boolean humanTakeover;
    /** 是否夜晚。null = 不知道游戏内时间。 */
    public Boolean night;

    // ---- 精确目标信息（mod 直读世界才有，数据包版没有）----

    /** 当前锁定目标的实体 id，-1 = 无目标。 */
    public int targetId = -1;
    /** 目标相对当前朝向的水平角差（度，正 = 在右边）。null = 无目标。 */
    public Float yawToTarget;
    /** 目标相对当前视线的俯仰角差（度，正 = 在下方）。null = 无目标。 */
    public Float pitchToTarget;
    /** 采集目标（原木 / 矿石）相对朝向的水平角差（度）。null = 无采集目标。 */
    public Float yawToResource;
    /** 采集目标相对视线的俯仰角差（度）。null = 无采集目标。 */
    public Float pitchToResource;
    /**
     * 采集目标方块坐标 {@code [x,y,z]}。null = 无采集目标。
     * 执行层拿它去 {@code gameMode.startDestroyBlock}。
     */
    public int[] resourcePos;
    /**
     * 采集目标到玩家的距离（格）。null = 无采集目标。
     *
     * <p>必须单独存一个字段，不能让下游拿 {@link #wood} 和 {@link #ore} 去
     * {@code Math.min}：目标是木头时最近矿石可能更近，取 min 会得到错误的距离，
     * 表现为「明明已经贴着树了还在往前冲」。
     */
    public Float resourceDist;
    /** 准星指向的方块坐标 {@code [x,y,z]}。null = 没指向方块。 */
    public int[] frontPos;
    /** 准星命中面的方向序号（0..5），对应 Direction.values()。null = 无。 */
    public Integer frontFace;

    public long tick;

    /** 深拷贝一份，行为模块需要在上面做「上一拍」对比。 */
    public WorldView copy() {
        WorldView c = new WorldView();
        c.hp = hp;
        c.threat = threat;
        c.contact = contact;
        c.dist = dist;
        c.hazard = hazard;
        c.enemies = enemies;
        c.food = food;
        c.wood = wood;
        c.ore = ore;
        c.wall = wall;
        c.blkFront = blkFront;
        c.blkBelow = blkBelow;
        c.step = step;
        c.pit = pit;
        c.pitch = pitch;
        c.yaw = yaw;
        c.stuck = stuck;
        c.pos = pos == null ? null : pos.clone();
        c.dead = dead;
        c.humanTakeover = humanTakeover;
        c.night = night;
        c.targetId = targetId;
        c.yawToTarget = yawToTarget;
        c.pitchToTarget = pitchToTarget;
        c.yawToResource = yawToResource;
        c.pitchToResource = pitchToResource;
        c.resourcePos = resourcePos == null ? null : resourcePos.clone();
        c.resourceDist = resourceDist;
        c.frontPos = frontPos == null ? null : frontPos.clone();
        c.frontFace = frontFace;
        c.tick = tick;
        return c;
    }

    /** 一行摘要，用于 action bar 与日志。 */
    public String summary() {
        StringBuilder s = new StringBuilder(64);
        s.append(hp == null ? "hp?" : String.format("hp%.0f%%", hp * 100f));
        s.append(' ').append(threat.ch()).append("威胁");
        if (dist != null) s.append(String.format(" d%.1f", dist));
        if (enemies != null) s.append(" x").append(enemies);
        if (hazard == Tri.POS) s.append(" !危险");
        if (stuck) s.append(" 卡住");
        return s.toString();
    }
}
