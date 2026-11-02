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
