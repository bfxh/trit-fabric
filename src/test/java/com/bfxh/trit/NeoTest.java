package com.bfxh.trit;

import com.bfxh.trit.core.CameraBudget;
import com.bfxh.trit.core.Tri;
import com.bfxh.trit.neo.Ballistics;
import com.bfxh.trit.neo.Ballistics.Approach;
import com.bfxh.trit.neo.Ballistics.Kind;
import com.bfxh.trit.neo.ProjectilePredictor;
import com.bfxh.trit.neo.ProjectilePredictor.Assessment;

/**
 * neo 层离线自检：弹道（算）、预测器（学）、摄像头预算（限制）。
 *
 * <p>这三块全部不碰 Minecraft，所以能在不开游戏的情况下把「走向算得对不对、
 * 波动学没学起来、镜头限制有没有真夹住」逐条钉死。
 */
public final class NeoTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        ballisticStraight();
        ballisticGravity();
        ballisticMiss();
        ballisticReceding();
        bandGrowsWithRange();

        predictorFlagsIncoming();
        predictorUnknownWhenNotScanned();
        predictorOccludedIsBGrade();
        predictorIgnoresParallelPass();
        predictorLearnsGravityError();
        predictorRewardsExpiredDodge();
        predictorPunishesHurtDodge();
        predictorCapsTracks();

        budgetOffBlocksAll();
        budgetClampsAtLimit();
        budgetAbsorbsPlayerCamera();
        budgetKeepsOwnWritesOutOfAnchor();
        budgetRecentersSlowly();
        budgetLevelsGateFreeLook();

        System.out.println();
        System.out.println("neo 层自检：" + passed + " 通过 / " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    // ==================================================================
    // 弹道（算的那一半）
    // ==================================================================

    /** 直线弹（火球）正对着来：TTI = 进入接触半径的那一拍。 */
    private static void ballisticStraight() {
        double[] pos = {10, 0, 0};
        double[] vel = {-0.5, 0, 0};
        Approach a = Ballistics.cpa(pos, vel, Kind.FIREBALL, 0, 0,
                new double[]{0, 0, 0}, null, null, 60, null);
        eq("直线弹：在接近", true, a.closing);
        eq("直线弹：TTI=19", 19, a.tti);
        check("直线弹：CPA≈0", a.cpa < 0.01);
        // 外推不许改调用方传进来的数组（预测器还要拿原始速度继续算）
        check("直线弹：入参未被改写", pos[0] == 10.0 && vel[0] == -0.5);
    }

    /** 有重力的类型每拍掉 g：一拍之后 y 速度与位置都要对上。 */
    private static void ballisticGravity() {
        double[] p = {0, 1, 0};
        double[] v = {0, 0, 0};
        Ballistics.advance(p, v, Kind.ARROW, 0, 0);
        check("箭：一拍掉 0.05", Math.abs(v[1] + 0.05) < 1e-9);
        check("箭：位置跟速度走", Math.abs(p[1] - 0.95) < 1e-9);
        Ballistics.advance(p, v, Kind.ARROW, 0, 0);
        check("箭：重力是累加的", Math.abs(v[1] + 0.10) < 1e-9);
    }

    /** 五格外平行飞过：最近距离 ≈ 5，TTI 不存在。 */
    private static void ballisticMiss() {
        double[] pos = {10, 5, 0};
        double[] vel = {-0.5, 0, 0};
        Approach a = Ballistics.cpa(pos, vel, Kind.FIREBALL, 0, 0,
                new double[]{0, 0, 0}, null, null, 60, null);
        check("平行弹：CPA≈5", Math.abs(a.cpa - 5.0) < 0.02);
        eq("平行弹：打不到（TTI=-1）", -1, a.tti);
        check("平行弹：接触半径外", !Ballistics.willHit(a.cpa, 0.35));
    }

    /** 正在远离的弹不算接近，判据要在几何上先否掉。 */
    private static void ballisticReceding() {
        double[] pos = {2, 0, 0};
        double[] vel = {0.5, 0, 0};
        Approach a = Ballistics.cpa(pos, vel, Kind.FIREBALL, 0, 0,
                new double[]{0, 0, 0}, null, null, 60, null);
        eq("远离弹：closing=false", false, a.closing);
        eq("远离弹：TTI=-1", -1, a.tti);
    }

    /** 波动带随外推距离变宽 —— 「越远越不确定」必须写进判据而不是嘴上说。 */
    private static void bandGrowsWithRange() {
        ProjectilePredictor pr = new ProjectilePredictor();
        double b4 = pr.band(Kind.ARROW, 4);
        double b40 = pr.band(Kind.ARROW, 40);
        check("波动带随拍数变宽", b40 > b4);
        check("波动带有地板（没见过也留 0.35 格）", b4 >= 0.35);
    }

    // ==================================================================
    // 预测器（学的那一半）
    // ==================================================================

    private static final double[] ME = {0, 0, 0};

    /** 正对着一辆火球过来 → A 级、给出方向、余量为正。 */
    private static void predictorFlagsIncoming() {
        ProjectilePredictor pr = new ProjectilePredictor();
        for (long t = 0; t <= 3; t++) {
            pr.observe(100, Kind.FIREBALL, 10 - 0.5 * t, 0, 0, -0.5, 0, 0, t);
        }
        Assessment a = pr.assess(ME, new double[]{0, 0, 0}, 0f, 3, null, true);
        eq("来袭火球：判 A 级", Tri.POS, a.level);
        eq("来袭火球：一颗", 1, a.count);
        eq("来袭火球：A 级计数", 1, a.aGrade);
        check("来袭火球：给出躲避动作", a.dodge != ProjectilePredictor.DODGE_NONE);
        check("来袭火球：横向/跳优于顺着弹道跑", a.dodge == ProjectilePredictor.DODGE_FWD
                || a.dodge == ProjectilePredictor.DODGE_BACK || a.dodge == ProjectilePredictor.DODGE_JUMP);
        check("来袭火球：躲后余量比不躲好 3 格以上",
                a.dodgeMargin > (a.cpa - Ballistics.HIT_RADIUS) + 3.0);
        check("来袭火球：TTI 在判定窗口内", a.tti >= 0 && a.tti <= ProjectilePredictor.TTI_MAX);
    }

    /** 没扫描 = 未知（ZERO），绝不能当成「没有弹」。这是「不猜」硬约束在弹幕上的落点。 */
    private static void predictorUnknownWhenNotScanned() {
        ProjectilePredictor pr = new ProjectilePredictor();
        pr.observe(100, Kind.FIREBALL, 10, 0, 0, -0.5, 0, 0, 0);
        Assessment a = pr.assess(ME, null, 0f, 0, null, false);
        eq("没扫描：判未知", Tri.ZERO, a.level);
        eq("没扫描：不计数", 0, a.count);
        eq("没扫描：跟踪还在", 1, pr.trackCount());
    }

    /** 命中点之前先撞墙 → 降为 B 级，不响应。 */
    private static void predictorOccludedIsBGrade() {
        ProjectilePredictor pr = new ProjectilePredictor();
        for (long t = 0; t <= 2; t++) {
            pr.observe(100, Kind.FIREBALL, 10 - 0.5 * t, 0, 0, -0.5, 0, 0, t);
        }
        Assessment walled = pr.assess(ME, new double[]{0, 0, 0}, 0f, 2, (from, to) -> true, true);
        eq("隔墙弹：判 B 级", Tri.NEG, walled.level);
        eq("隔墙弹：零 A 级", 0, walled.aGrade);
        Assessment openAir = pr.assess(ME, new double[]{0, 0, 0}, 0f, 2, (from, to) -> false, true);
        eq("同一无遮挡弹：判 A 级", Tri.POS, openAir.level);
    }

    /** 擦着五格外飞过去：零票零响应。 */
    private static void predictorIgnoresParallelPass() {
        ProjectilePredictor pr = new ProjectilePredictor();
        for (long t = 0; t <= 2; t++) {
            pr.observe(200, Kind.FIREBALL, 10 - 0.5 * t, 5, 0, -0.5, 0, 0, t);
        }
        Assessment a = pr.assess(ME, new double[]{0, 0, 0}, 0f, 2, null, true);
        eq("平行弹：明确没有威胁", Tri.NEG, a.level);
        eq("平行弹：仍然计数在屏", 1, a.count);
        eq("平行弹：不给动作", ProjectilePredictor.DODGE_NONE, a.dodge);
    }

    /**
     * 学重力：真实世界掉得比常数表多（0.11 vs 0.05），残差单调变负，
     * 学到的修正必须朝「加多重力」的方向走。这条断言钉住「波动靠预测」不是空话。
     */
    private static void predictorLearnsGravityError() {
        ProjectilePredictor pr = new ProjectilePredictor();
        double[] p = {6, 3, 0};
        double[] v = {-0.5, 0, 0};
        double gTrue = 0.11;
        double before = pr.gravCorrOf(Kind.ARROW);
        for (long t = 0; t < 8; t++) {
            pr.observe(300, Kind.ARROW, p[0], p[1], p[2], v[0], v[1], v[2], t);
            // 用「真」物理生成下一拍观测点
            v[1] -= gTrue;
            p[0] += v[0];
            p[1] += v[1];
            p[2] += v[2];
        }
        double after = pr.gravCorrOf(Kind.ARROW);
        check("学重力：样本进了账", pr.samplesOf(Kind.ARROW) >= 6);
        check("学重力：修正在动的方向对（要加多重力）", after > before);
        check("学重力：修正被夹在上限内", Math.abs(after) <= 0.02 + 1e-9);
        // 真实误差 0.06、修正上限只有 0.02：学不满的部分必须由波动带承认下来，
        // 而不是让判据继续假称「精确」。
        check("学重力：残余误差撑开波动带", pr.band(Kind.ARROW, 20) > 1.0);
        check("学重力：σ 不低于地板", pr.sigmaOf(Kind.ARROW) >= 0.05);
    }

    /** A 级弹消失了 = 躲成功 → 奖励当时选的方向。 */
    private static void predictorRewardsExpiredDodge() {
        ProjectilePredictor pr = new ProjectilePredictor();
        int chosen = -1;
        for (long t = 0; t <= 2; t++) {
            pr.observe(400, Kind.FIREBALL, 10 - 0.5 * t, 0, 0, -0.5, 0, 0, t);
            chosen = pr.assess(ME, new double[]{0, 0, 0}, 0f, t, null, true).dodge;
        }
        check("奖励前：先验还是 0", Math.abs(pr.dodgePriorOf(Kind.FIREBALL)[chosen]) < 1e-6);
        Assessment gone = pr.assess(ME, new double[]{0, 0, 0}, 0f, 10, null, true);
        eq("过期：跟踪被回收", 0, pr.trackCount());
        eq("过期：这一拍没有弹", Tri.NEG, gone.level);
        check("奖励：当时那个方向先验上升", pr.dodgePriorOf(Kind.FIREBALL)[chosen] > 0.05f);
    }

    /** 躲了还是挨打 → 惩罚当时选的方向，且不许再被「过期奖励」翻案。 */
    private static void predictorPunishesHurtDodge() {
        ProjectilePredictor pr = new ProjectilePredictor();
        int chosen = -1;
        for (long t = 0; t <= 2; t++) {
            pr.observe(500, Kind.FIREBALL, 10 - 0.5 * t, 0, 0, -0.5, 0, 0, t);
            chosen = pr.assess(ME, new double[]{0, 0, 0}, 0f, t, null, true).dodge;
        }
        pr.onPlayerHurt(Kind.FIREBALL, 3);
        float punished = pr.dodgePriorOf(Kind.FIREBALL)[chosen];
        check("惩罚：先验转负", punished < -0.05f);
        pr.assess(ME, new double[]{0, 0, 0}, 0f, 12, null, true);   // 过期
        check("惩罚已结算：过期不再翻案奖励", pr.dodgePriorOf(Kind.FIREBALL)[chosen] < 0f);
    }

    /** 弹幕海：跟踪条数封顶，不做无上限的算力。 */
    private static void predictorCapsTracks() {
        ProjectilePredictor pr = new ProjectilePredictor();
        for (int i = 0; i < 60; i++) {
            pr.observe(1000 + i, Kind.ARROW, 20 + i, 1, 1, -0.4, 0, 0, 0);
        }
        Assessment a = pr.assess(ME, new double[]{0, 0, 0}, 0f, 0, null, true);
        check("弹幕海：跟踪不超上限", pr.trackCount() <= ProjectilePredictor.MAX_TRACKS);
        check("弹幕海：计数不超上限", a.count <= ProjectilePredictor.MAX_TRACKS);
    }

    // ==================================================================
    // 摄像头预算（限制那一半）
    // ==================================================================

    private static void budgetOffBlocksAll() {
        CameraBudget b = new CameraBudget();
        b.observeCamera(0f, 0f, 0f, 0f);       // 建立锚点
        b.setLevel(CameraBudget.Level.OFF);
        eq("关档：AI 一个度都写不了", 0f, b.clampYaw(0f, 12f));
        eq("关档：不许自由扫视", false, b.allowsFreeLook());
        eq("关档：不许借机位", false, b.allowsPerspectiveSwitch());
    }

    private static void budgetClampsAtLimit() {
        CameraBudget b = new CameraBudget();
        b.setLevel(CameraBudget.Level.NUDGE);
        b.observeCamera(0f, 0f, 0f, 0f);
        eq("微调：第一拍给满 15°", 15f, b.clampYaw(0f, 15f));
        b.observeCamera(15f, 0f, 15f, 0f);
        eq("微调：到顶后不再给", 0f, b.clampYaw(15f, 12f));
        eq("微调：夹过的量记了账", 1L, b.clippedTicks());
        check("微调：累计被夹度数 > 0", b.clippedTotal() > 0);
        eq("微调：反方向也一样夹", 0f, b.clampYaw(15f, -8f));
    }

    /** 玩家自己转镜头：锚点跟着走，AI 的预算重新起算 —— 「我调摄像头不影响它」。 */
    private static void budgetAbsorbsPlayerCamera() {
        CameraBudget b = new CameraBudget();
        b.setLevel(CameraBudget.Level.NUDGE);
        b.observeCamera(0f, 0f, 0f, 0f);
        b.observeCamera(90f, 0f, 0f, 0f);      // 玩家转了 90°，AI 一分没写
        check("玩家转镜头后偏差归零", Math.abs(b.deviationYaw()) < 0.01f);
        eq("玩家转镜头后预算重新给满", 15f, b.clampYaw(90f, 15f));
    }

    /** AI 自己写的量不能混进锚点，否则「限制」会被它自己挪走。 */
    private static void budgetKeepsOwnWritesOutOfAnchor() {
        CameraBudget b = new CameraBudget();
        b.setLevel(CameraBudget.Level.NUDGE);
        b.observeCamera(0f, 0f, 0f, 0f);
        float wrote = b.clampYaw(0f, 12f);
        eq("AI 写入 12°", 12f, wrote);
        b.observeCamera(12f, 0f, wrote, 0f);
        eq("锚点不动：偏差就是 AI 写的量", 12f, b.deviationYaw());
        eq("只剩 3° 余量", 3f, b.clampYaw(12f, 10f));
    }

    /** 用完就还：回中速率受限，死区内不动。 */
    private static void budgetRecentersSlowly() {
        CameraBudget b = new CameraBudget();
        b.setLevel(CameraBudget.Level.NUDGE);
        b.observeCamera(0f, 0f, 0f, 0f);
        b.observeCamera(12f, 0f, 12f, 0f);
        check("回中：往锚点收", b.recenterYaw(12f) < 0f);
        check("回中：速率 ≤2°/拍（守住 R5 的视角验收线）", Math.abs(b.recenterYaw(12f)) <= 2f);
        b.observeCamera(1f, 0f, -11f, 0f);      // 已经回到 1°
        eq("回中：死区内停手", 0f, b.recenterYaw(1f));
        b.setLevel(CameraBudget.Level.FREE);
        eq("自由档不强行回中", 0f, b.recenterYaw(1f));
    }

    private static void budgetLevelsGateFreeLook() {
        CameraBudget b = new CameraBudget();
        b.setLevel(CameraBudget.Level.NUDGE);
        eq("微调：不许自由扫视", false, b.allowsFreeLook());
        b.setLevel(CameraBudget.Level.TRACK);
        eq("跟踪：允许", true, b.allowsFreeLook());
        eq("跟踪：允许借机位", true, b.allowsPerspectiveSwitch());
        eq("默认档：跟踪（±45°）", 2, b.level().ordinal());
        b.reanchor();
        eq("重新锚定后本拍不写视角", 0f, b.clampYaw(30f, 10f));
        b.observeCamera(30f, 0f, 0f, 0f);
        eq("锚点重建后恢复写入", 12f, b.clampYaw(30f, 12f));
    }

    // ------------------------------------------------------------------

    private static void eq(String what, Object expect, Object actual) {
        if (expect == null ? actual == null : expect.equals(actual)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + "：期望 " + expect + "，实际 " + actual);
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }
}
