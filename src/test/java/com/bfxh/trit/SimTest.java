package com.bfxh.trit;

import com.bfxh.trit.behave.Behavior;
import com.bfxh.trit.behave.Behaviors;
import com.bfxh.trit.core.Channel;
import com.bfxh.trit.core.Decision;
import com.bfxh.trit.core.Machine;
import com.bfxh.trit.core.Mode;
import com.bfxh.trit.core.Phase;
import com.bfxh.trit.core.Tri;
import com.bfxh.trit.core.Vote;
import com.bfxh.trit.core.WorldView;
import com.bfxh.trit.neo.ProjectilePredictor;

import java.util.ArrayList;
import java.util.List;

/**
 * 行为层仿真：不进游戏，喂构造好的感知快照，看 AI 会做什么。
 *
 * <p>这类测试专门防一种看不见的死法：票权和迟滞配错，某个通道永远停在 0，
 * 表现就是「AI 站着不动」或者「视角死活不转」。纯看代码不容易发现，
 * 因为每一条票单独看都是对的。
 */
public final class SimTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        patrolWalks();
        forageWalksToTree();
        forageStandsAndMines();
        combatClosesIn();
        combatStrikesWhenInRange();
        retreatBacksOff();
        wallStopsForward();
        hazardOverridesPatrol();
        hungryEats();
        unknownThreatStandsStill();

        dodgeMovesLegsNotHead();
        dodgeBraceWhenPointBlank();
        bGradeIsSilent();
        unknownProjectileDoesNotMoveAnything();
        dodgeKeepsForageVotes();
        humanTakeoverYieldsMachine();

        System.out.println();
        System.out.println("行为仿真：" + passed + " 通过 / " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------

    /** 空旷地带、没有资源、没有敌人 → 应该往前走。 */
    private static void patrolWalks() {
        WorldView w = base();
        Decision d = run(w);
        eq("巡逻阶段", Phase.IDLE, d.phase);
        eq("巡逻前进", Tri.POS, d.dirs[Channel.FORWARD.index()]);
    }

    /** 5 格外的树 → 走过去（采集模式）。 */
    private static void forageWalksToTree() {
        WorldView w = base();
        w.resourcePos = new int[]{10, 64, 0};
        w.resourceDist = 5f;
        w.wood = 5f;
        w.yawToResource = 0f;
        w.pitchToResource = 0f;
        Decision d = run(w, Mode.FORAGE);
        eq("采集：走向树", Tri.POS, d.dirs[Channel.FORWARD.index()]);
    }

    /** 已经贴着树 → 站住不动，按住左键挖（采集模式）。 */
    private static void forageStandsAndMines() {
        WorldView w = base();
        w.resourcePos = new int[]{6, 64, 0};
        w.resourceDist = 1.5f;
        w.wood = 1.5f;
        w.yawToResource = 0f;
        w.pitchToResource = 0f;
        Decision d = run(w, Mode.FORAGE);
        eq("贴着树：不再前进", Tri.ZERO, d.dirs[Channel.FORWARD.index()]);
        eq("贴着树：挖", Tri.POS, d.dirs[Channel.ATTACK.index()]);
        check("贴着树：按住不放", d.attackHold);
    }

    /** 4 格外的敌人 → 接近，但还够不着不打。 */
    private static void combatClosesIn() {
        WorldView w = base();
        w.threat = Tri.POS;
        w.targetId = 7;
        w.dist = 4f;
        w.enemies = 1;
        w.yawToTarget = 0f;
        w.pitchToTarget = 0f;
        Decision d = run(w);
        eq("交战阶段", Phase.ENGAGE, d.phase);
        eq("4 格：接近", Tri.POS, d.dirs[Channel.FORWARD.index()]);
        eq("4 格：够不着不出手", Tri.ZERO, d.dirs[Channel.ATTACK.index()]);
    }

    /** 2 格内的敌人 → 打。 */
    private static void combatStrikesWhenInRange() {
        WorldView w = base();
        w.threat = Tri.POS;
        w.targetId = 7;
        w.dist = 2f;
        w.enemies = 1;
        w.yawToTarget = 0f;
        w.pitchToTarget = 0f;
        Decision d = run(w);
        eq("2 格：出手", Tri.POS, d.dirs[Channel.ATTACK.index()]);
        // 打怪是脉冲，不能是「按住」
        check("打怪不是按住", !d.attackHold);
    }

    /** 残血遇敌 → 头也不回地退。 */
    private static void retreatBacksOff() {
        WorldView w = base();
        w.hp = 0.2f;
        w.threat = Tri.POS;
        w.targetId = 7;
        w.dist = 3f;
        w.enemies = 2;
        Decision d = run(w);
        eq("低血撤退", Phase.RETREAT, d.phase);
        eq("撤退后退", Tri.NEG, d.dirs[Channel.FORWARD.index()]);
    }

    /** 正前方是墙 → 不能继续往前顶。 */
    private static void wallStopsForward() {
        WorldView w = base();
        w.wall = 1; // bit0 = 正前
        Decision d = run(w);
        check("墙前不前进", d.dirs[Channel.FORWARD.index()] != Tri.POS);
    }

    /** 脚下有岩浆 → 避险权重压过巡逻。 */
    private static void hazardOverridesPatrol() {
        WorldView w = base();
        w.hazard = Tri.POS;
        Decision d = run(w);
        eq("危险：后退", Tri.NEG, d.dirs[Channel.FORWARD.index()]);
    }

    /** 饿了 → 右键进食。 */
    private static void hungryEats() {
        WorldView w = base();
        w.food = 6;
        Decision d = run(w);
        eq("饿了进食", Tri.POS, d.dirs[Channel.USE.index()]);
    }

    /** 威胁未知（没扫描到）→ 保守站桩，不能乱跑。 */
    private static void unknownThreatStandsStill() {
        WorldView w = base();
        w.threat = Tri.ZERO;
        w.wall = null;
        Decision d = run(w);
        eq("未知威胁：待命", Phase.IDLE, d.phase);
    }

    // ------------------------------------------------------------------
    // 弹幕（R4 / R5 的验收）：动腿不动头，B 级静默，未知不动，且不顶掉原任务

    /** 一份「A 级弹正对着来」的快照。 */
    private static WorldView incoming(int dodgeAction, float tti) {
        WorldView w = base();
        w.projThreat = Tri.POS;
        w.projectileCount = 1;
        w.projectileAGrade = 1;
        w.projTti = tti;
        w.projCpa = 0.4f;
        w.projBand = 1.0f;
        w.projSigma = 0.08f;
        w.projKind = "arrow";
        w.dodgeAction = dodgeAction;
        // 微调档：待命扫视这时不该动真实镜头，测试才能把「视角零变化」钉死
        w.camBudgetLevel = 1;
        return w;
    }

    /** 验收 1：朝玩家飞的箭 → 出躲避票；真实视角一拍都不动（比「≤2°/拍」更严）。 */
    private static void dodgeMovesLegsNotHead() {
        WorldView w = incoming(ProjectilePredictor.DODGE_RIGHT, 12f);
        Decision d = run(w);
        eq("弹幕来袭：横移", Tri.POS, d.dirs[Channel.STRAFE.index()]);
        eq("弹幕来袭：不转头（YAW 零票）", Tri.ZERO, d.dirs[Channel.YAW.index()]);
        eq("弹幕来袭：不点头（PITCH 零票）", Tri.ZERO, d.dirs[Channel.PITCH.index()]);
        eq("弹幕来袭：阶段不变", Phase.IDLE, d.phase);
    }

    /** 贴脸弹（TTI ≤ 4 拍）额外来一跳：让开判定线 / 卸力。 */
    private static void dodgeBraceWhenPointBlank() {
        WorldView w = incoming(ProjectilePredictor.DODGE_RIGHT, 3f);
        Decision d = run(w);
        eq("贴脸弹：跳", Tri.POS, d.dirs[Channel.JUMP.index()]);
    }

    /** 验收 2：平行飞过 / 打不到的弹 → 零票、零视角，巡逻照常。 */
    private static void bGradeIsSilent() {
        WorldView w = base();
        w.projThreat = Tri.NEG;
        w.projectileCount = 3;
        w.projectileAGrade = 0;
        w.dodgeAction = ProjectilePredictor.DODGE_NONE;
        Decision d = run(w);
        eq("B 级：不横移", Tri.ZERO, d.dirs[Channel.STRAFE.index()]);
        eq("B 级：不跳", Tri.ZERO, d.dirs[Channel.JUMP.index()]);
        eq("B 级：巡逻照常", Tri.POS, d.dirs[Channel.FORWARD.index()]);
    }

    /** 验收 3：没扫描 = 未知，绝不能当成「没有弹」而起跳。 */
    private static void unknownProjectileDoesNotMoveAnything() {
        WorldView w = base();
        w.projThreat = Tri.ZERO;
        w.projectileCount = null;
        Decision d = run(w);
        eq("未扫描：不因弹幕动腿", Tri.ZERO, d.dirs[Channel.STRAFE.index()]);
        eq("未扫描：不因弹幕跳", Tri.ZERO, d.dirs[Channel.JUMP.index()]);
        eq("未扫描：巡逻照常", Tri.POS, d.dirs[Channel.FORWARD.index()]);
    }

    /** 验收 4：躲避期间采集票不清空，躲完接着干活。 */
    private static void dodgeKeepsForageVotes() {
        WorldView w = incoming(ProjectilePredictor.DODGE_RIGHT, 10f);
        w.resourcePos = new int[]{5, 64, 12};
        w.resourceDist = 6f;
        w.wood = 6f;
        w.yawToResource = 0f;
        w.pitchToResource = 0f;
        List<Vote> votes = votesOf(w, Mode.FORAGE);
        check("躲避时采集票还在", hasSrc(votes, "forage_walk"));
        check("躲避时躲避票也在", hasSrc(votes, "dodge_right"));
        Decision d = run(w, Mode.FORAGE);
        eq("躲避方向压过侧移", Tri.POS, d.dirs[Channel.STRAFE.index()]);
        eq("前进没被清空（继续走向树）", Tri.POS, d.dirs[Channel.FORWARD.index()]);
    }

    /**
     * 验收 5：玩家操作时让权。
     * 真实路径是 {@code TritMod} 里 {@code humanActing → detach}；这里钉的是机器侧的等价闸门
     * （{@code humanTakeover}），保证即使调用方漏判，状态机也不会在这拍写出任何动作。
     */
    private static void humanTakeoverYieldsMachine() {
        WorldView w = incoming(ProjectilePredictor.DODGE_RIGHT, 8f);
        w.humanTakeover = true;
        Decision d = run(w);
        eq("让权：阶段交还", Phase.OFF, d.phase);
        eq("让权：不前进", Tri.ZERO, d.dirs[Channel.FORWARD.index()]);
        eq("让权：不横移", Tri.ZERO, d.dirs[Channel.STRAFE.index()]);
        eq("让权：不动视角", Tri.ZERO, d.dirs[Channel.YAW.index()]);
    }

    // ------------------------------------------------------------------

    /** 一份「安全、无目标」的基础快照。 */
    private static WorldView base() {
        WorldView w = new WorldView();
        w.hp = 1.0f;
        w.threat = Tri.NEG;   // 明确没有敌人
        w.hazard = Tri.NEG;   // 明确没有危险
        w.food = 20;
        w.dead = false;
        w.wall = 0;           // 八个方位都没墙
        w.step = false;
        w.pit = false;
        w.stuck = false;
        w.pos = new double[]{5, 64, 0};
        w.yaw = 0f;
        w.pitch = 0f;
        w.night = false;
        return w;
    }

    /** 跑一整拍：peek → 投票 → 裁决。 */
    private static Decision run(WorldView w) {
        return run(w, Mode.PATROL);
    }

    private static Decision run(WorldView w, Mode mode) {
        Machine m = new Machine();
        m.setArmed(true);
        Phase phase = m.peek(w);
        Behavior.Ctx ctx = new Behavior.Ctx(w, phase, m, 1L, mode);
        List<Vote> votes = new ArrayList<>();
        for (Behavior b : Behaviors.defaults(new Behaviors.Anchor())) {
            b.vote(ctx, votes);
        }
        boolean[] on = new boolean[Channel.NCH];
        java.util.Arrays.fill(on, true);
        return m.step(w, votes, on);
    }

    /** 只跑投票、不裁决：用来断言「某个模块的票在不在」。 */
    private static List<Vote> votesOf(WorldView w, Mode mode) {
        Machine m = new Machine();
        m.setArmed(true);
        Phase phase = m.peek(w);
        Behavior.Ctx ctx = new Behavior.Ctx(w, phase, m, 1L, mode);
        List<Vote> votes = new ArrayList<>();
        for (Behavior b : Behaviors.defaults(new Behaviors.Anchor())) {
            b.vote(ctx, votes);
        }
        return votes;
    }

    private static boolean hasSrc(List<Vote> votes, String src) {
        for (Vote v : votes) {
            if (src.equals(v.src)) return true;
        }
        return false;
    }

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
