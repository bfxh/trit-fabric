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
    /** 准星所指方块相对朝向的水平角差（度，正 = 在右边）。null = 无。 */
    public Float yawToFront;
    /** 准星所指方块相对视线的俯仰角差（度，正 = 在下方）。null = 无。 */
    public Float pitchToFront;
    /**
     * 准星所指方块是否可挖（非空气、非危险、非液体、非原木/矿石）。
     * 用于「卡住时挖开前面的土/石头开路」，与采集（logs/ores）互不抢。
     */
    public Boolean canDigFront;

    // ---- 交互 / 装备 / 地形（灵性操作需要的信号）----

    /**
     * 玩家自己正在操作（按移动/攻击/使用键，或正在破坏方块）。
     * 这个为真时 AI 必须<strong>全部让权</strong>，绝不抢控制权。
     */
    public boolean humanActing;
    /** 采集目标被遮挡（视线被方块挡住，挖不到也瞄不准）。null = 未判定。 */
    public Boolean resourceOccluded;
    /** 战斗目标被遮挡（隔着墙打不到）。null = 未判定。 */
    public Boolean targetOccluded;
    /** 背包里有弓/弩。null = 未知。 */
    public Boolean hasBow;
    /** 背包里有箭（或弩已上膛）。null = 未知。 */
    public Boolean hasAmmo;
    /** 背包里有可放置的方块（用于填坑/搭桥）。null = 未知。 */
    public Boolean hasBlocks;
    /** 脚下的方块坐标，放方块时作为落点参考。null = 无。 */
    public int[] footPos;
    /** 是否站在地面上（用于判断是否要落地 / 掉坑）。 */
    public Boolean onGround;
    /** 脚下是否泡在液体里。 */
    public Boolean inLiquid;
    /** 正前方一格是坑或液体（需要搭桥）。null = 未判定。 */
    public Boolean gapAhead;

    // ---- 跟随目标（FOLLOW 模式用）----

    /** 当前跟随的实体 id，-1 = 无。 */
    public int followId = -1;
    /** 跟随目标坐标。null = 无。 */
    public double[] followXyz;
    /** 跟随目标相对当前朝向的水平角差（度，正 = 在右边）。null = 无。 */
    public Float followYaw;
    /** 跟随目标相对当前视线的俯仰角差（度，正 = 在下方）。null = 无。 */
    public Float followPitch;

    // ---- 运动（弹幕外推要把「我自己往哪走」一起算进去）----

    /** 我的速度向量 {@code [vx,vy,vz]}（格/拍）。null = 还没读到，<strong>不是「静止」</strong>。 */
    public double[] vel;
    /** 水平速度（格/拍）。null = 未知。 */
    public Float horizSpeed;

    // ---- 弹幕（R4 / R5：内部算完直接躲，不看、不找、不说）----

    /** 弹幕威胁级：POS = 有 A 级来袭；NEG = 扫过了、明确没有；ZERO = 没扫描 = 未知。 */
    public Tri projThreat = Tri.ZERO;
    /** 窗口内在飞的弹射物数量。null = 没扫描。 */
    public Integer projectileCount;
    /** 其中 A 级（需响应）的数量。null = 没扫描。 */
    public Integer projectileAGrade;
    /** 最危险那颗的命中时间（拍）。null = 打不到我。 */
    public Float projTti;
    /** 最危险那颗的最近接近距离（格）。 */
    public Float projCpa;
    /** 那颗的预测波动带（格）= 判据里「预测」那一半的宽度。 */
    public Float projBand;
    /** 那颗类型的每拍残差波动（格）。学到的，不是设的。 */
    public Float projSigma;
    /** 那颗的类型名（arrow / trident / fireball / thrown / ...）。 */
    public String projKind;
    /** 那颗相对当前朝向的水平角差（度，正 = 在右边）。null = 无 A 级弹。 */
    public Float yawToProj;
    /** 那颗相对当前视线的俯仰角差（度，正 = 在下方）。null = 无 A 级弹。 */
    public Float pitchToProj;
    /** 建议躲避动作：0 左 1 右 2 后退 3 前进 4 跳，-1 = 无。 */
    public int dodgeAction = -1;
    /** 按建议动作躲之后的最近余量（格）。 */
    public Float dodgeMargin;
    /** 累计学习样本数，用来在指标里看「它到底学会没有」。 */
    public Long projLearned;

    // ---- 摄像头所有权 / 预算 / 独立指针 ----

    /** 相机模式：0 = 第一人称，1 = 第三人称背，2 = 第三人称前。null = 未知。 */
    public Integer camMode;
    /** 摄像头预算档位：0 关 / 1 微调 / 2 跟踪 / 3 自由。 */
    public int camBudgetLevel = 2;
    /** 本拍 AI 写出去的水平视角增量（度，绝对值）。验收「弹幕来袭 ≤2°/拍」看它。 */
    public float camAppliedYaw;
    /** 本拍 AI 写出去的俯仰增量（度，绝对值）。 */
    public float camAppliedPitch;
    /** AI 视角偏离锚点的度数（带符号，正 = 右/下）。 */
    public float camDeviation;
    /** 被预算夹掉的累计度数：限制真起作用的证据。 */
    public float camClippedTotal;
    /** 独立指针（AI 自己的准星）朝向。 */
    public Float pointerYaw;
    /** 独立指针俯仰。 */
    public Float pointerPitch;
    /** 独立指针与真实视线的夹角（度）。 */
    public Float pointerToCamera;

    public long tick;

    /**
     * 某个方位上有没有障碍。
     *
     * @param dir 0 = 正前，顺时针每 45°，取值 0..7（自动取模）
     * @return true = 该方位有墙。<strong>掩码为 null（没扫描过）时返回 false</strong>，
     *         行为模块拿到的就是「不知道」—— 想要区分请直接读 {@link #wall}。
     */
    public boolean wallAt(int dir) {
        if (wall == null) return false;
        return (wall & (1 << (dir & 7))) != 0;
    }

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
        c.yawToFront = yawToFront;
        c.pitchToFront = pitchToFront;
        c.canDigFront = canDigFront;
        c.humanActing = humanActing;
        c.resourceOccluded = resourceOccluded;
        c.targetOccluded = targetOccluded;
        c.hasBow = hasBow;
        c.hasAmmo = hasAmmo;
        c.hasBlocks = hasBlocks;
        c.footPos = footPos == null ? null : footPos.clone();
        c.onGround = onGround;
        c.inLiquid = inLiquid;
        c.gapAhead = gapAhead;
        c.followId = followId;
        c.followXyz = followXyz == null ? null : followXyz.clone();
        c.followYaw = followYaw;
        c.followPitch = followPitch;
        c.vel = vel == null ? null : vel.clone();
        c.horizSpeed = horizSpeed;
        c.projThreat = projThreat;
        c.projectileCount = projectileCount;
        c.projectileAGrade = projectileAGrade;
        c.projTti = projTti;
        c.projCpa = projCpa;
        c.projBand = projBand;
        c.projSigma = projSigma;
        c.projKind = projKind;
        c.yawToProj = yawToProj;
        c.pitchToProj = pitchToProj;
        c.dodgeAction = dodgeAction;
        c.dodgeMargin = dodgeMargin;
        c.projLearned = projLearned;
        c.camMode = camMode;
        c.camBudgetLevel = camBudgetLevel;
        c.camAppliedYaw = camAppliedYaw;
        c.camAppliedPitch = camAppliedPitch;
        c.camDeviation = camDeviation;
        c.camClippedTotal = camClippedTotal;
        c.pointerYaw = pointerYaw;
        c.pointerPitch = pointerPitch;
        c.pointerToCamera = pointerToCamera;
        c.tick = tick;
        return c;
    }

    /** 一行摘要，用于 action bar 与日志。 */
    public String summary() {
        StringBuilder s = new StringBuilder(96);
        s.append(hp == null ? "hp?" : String.format("hp%.0f%%", hp * 100f));
        s.append(' ').append(threat.ch()).append("威胁");
        if (dist != null) s.append(String.format(" d%.1f", dist));
        if (enemies != null) s.append(" x").append(enemies);
        if (hazard == Tri.POS) s.append(" !危险");
        if (stuck) s.append(" 卡住");
        // 弹幕：只在 A 级时展开细节，B 级只报个数（用户要求「不说」——不播报，指标行不算播报）
        if (projThreat == Tri.POS) {
            s.append(" !弹").append(projKind == null ? "?" : projKind);
            s.append(projTti == null ? "" : String.format(" tti%.0f", projTti));
            s.append(projCpa == null ? "" : String.format(" cpa%.1f", projCpa));
            s.append(projBand == null ? "" : String.format("/带%.1f", projBand));
        } else if (projectileCount != null && projectileCount > 0) {
            s.append(" 弹").append(projectileCount).append("(B)");
        }
        // 视角归属指标：现在是第几视角 + 预算档位
        s.append(" 镜").append(camModeName(camMode)).append("/档").append(camBudgetLevel);
        return s.toString();
    }

    /** 相机模式的人类可读名：一 / 三后 / 三前 / ?（未知）。 */
    public static String camModeName(Integer mode) {
        if (mode == null) return "?";
        return switch (mode) {
            case 0 -> "一";
            case 1 -> "三后";
            case 2 -> "三前";
            default -> String.valueOf(mode + 1);
        };
    }
}
