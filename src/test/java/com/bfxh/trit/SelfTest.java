package com.bfxh.trit;

import com.bfxh.trit.core.Angles;
import com.bfxh.trit.core.Channel;
import com.bfxh.trit.core.Decision;
import com.bfxh.trit.core.Machine;
import com.bfxh.trit.core.Phase;
import com.bfxh.trit.core.Reason;
import com.bfxh.trit.core.Tri;
import com.bfxh.trit.core.Vote;
import com.bfxh.trit.core.WorldView;

import java.util.ArrayList;
import java.util.List;

/**
 * 状态机内核的自检。不依赖 Minecraft —— core 包是纯逻辑，可以独立跑。
 *
 * <p>对应 Rust 侧 {@code trit.rs} / {@code fsm.rs} 里的单元测试，
 * 逐条对齐，保证两个实现的行为一致。
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        triLogic();
        angles();
        machine();
        encoding();

        System.out.println();
        System.out.println("自检：" + passed + " 通过 / " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------

    private static void triLogic() {
        // K3 否定是对合，且未知的否定仍是未知
        for (Tri t : Tri.values()) {
            eq("not 对合 " + t, t, t.not().not());
        }
        eq("unknown.not", Tri.ZERO, Tri.ZERO.not());

        // and：NEG 吸收
        eq("neg and zero", Tri.NEG, Tri.NEG.and(Tri.ZERO));
        eq("pos and zero", Tri.ZERO, Tri.POS.and(Tri.ZERO));
        // or：POS 吸收
        eq("pos or zero", Tri.POS, Tri.POS.or(Tri.ZERO));
        eq("neg or zero", Tri.ZERO, Tri.NEG.or(Tri.ZERO));
        // 关键：未知不会被猜成 POS
        check("未知 and 未知 != POS", Tri.ZERO.and(Tri.ZERO) != Tri.POS);
        check("未知 or 未知 != POS", Tri.ZERO.or(Tri.ZERO) != Tri.POS);

        // De Morgan
        for (Tri a : Tri.values()) {
            for (Tri b : Tri.values()) {
                eq("demorgan1 " + a + b, a.and(b).not(), a.not().or(b.not()));
                eq("demorgan2 " + a + b, a.or(b).not(), a.not().and(b.not()));
            }
        }

        eq("majority(+,+,-)", Tri.POS, Tri.majority(Tri.POS, Tri.POS, Tri.NEG));
        eq("majority(-,-,+)", Tri.NEG, Tri.majority(Tri.NEG, Tri.NEG, Tri.POS));
        eq("majority(+,-,0)", Tri.ZERO, Tri.majority(Tri.POS, Tri.NEG, Tri.ZERO));

        eq("fromInt(-7)", Tri.NEG, Tri.fromInt(-7));
        eq("fromInt(0)", Tri.ZERO, Tri.fromInt(0));
        eq("fromInt(9)", Tri.POS, Tri.fromInt(9));
    }

    private static void angles() {
        // yaw = 0 表示面朝 +Z（南）。面朝南时：左手边是东（+X），右手边是西（-X）。
        // MC 里向右转 = yaw 增大（南 → 西是 0 → 90），所以「在右边」对应 yawDelta > 0。
        float west = Angles.yawDelta(0f, 0, 0, -5, 0);   // 目标在 -X（西）= 右手边
        check("目标在右（西）→ yawDelta > 0", west > 0);
        float east = Angles.yawDelta(0f, 0, 0, 5, 0);    // 目标在 +X（东）= 左手边
        check("目标在左（东）→ yawDelta < 0", east < 0);
        float ahead = Angles.yawDelta(0f, 0, 0, 0, 5);   // 正前方
        check("目标在正前 → yawDelta ≈ 0", Math.abs(ahead) < 0.001f);
        // 面朝东（yaw=270 时 forward=(-sin270,cos270)=(1,0) 即 +X）
        float aheadEast = Angles.yawDelta(270f, 0, 0, 5, 0);
        check("朝东时正东目标 ≈ 0", Math.abs(aheadEast) < 0.001f);
        // 归一化：跨 ±180 时不应给出接近 360 的值
        float wrap = Angles.yawDelta(170f, 0, 0, 0, -5); // 目标在正北(-Z)，yaw=170
        check("跨边界归一化", Math.abs(wrap) <= 180f);

        // pitch：目标在下方 → 正
        float below = Angles.pitchDelta(0f, 1.6, 0, 0, 3, -2, 0);
        check("目标在下方 → pitchDelta > 0", below > 0);
        float above = Angles.pitchDelta(0f, 1.6, 0, 0, 3, 6, 0);
        check("目标在上方 → pitchDelta < 0", above < 0);
    }

    private static void machine() {
        // 停机：全部松手
        Machine m = new Machine();
        Decision d = m.step(new WorldView(), List.of(), allOn());
        eq("停机阶段", Phase.OFF, d.phase);
        boolean allZero = true;
        for (Tri t : d.dirs) if (t != Tri.ZERO) allZero = false;
        check("停机全部通道为 0", allZero);

        // 未知票压过已知票 → 弃权
        m = armedMachine(1.0f, Tri.NEG);
        List<Vote> v = new ArrayList<>();
        v.add(Vote.of(Channel.ATTACK, Tri.POS, 1, "combat"));
        v.add(Vote.abstain(Channel.ATTACK, 3, "scanner"));
        d = m.step(wv(1.0f, Tri.NEG), v, allOn());
        eq("未知压过已知 → 弃权", Tri.ZERO, d.dirs[Channel.ATTACK.index()]);

        // 迟滞：先稳定拿到 POS，再给一个弱反向票，不应翻转
        m = armedMachine(1.0f, Tri.NEG);
        WorldView w = wv(1.0f, Tri.NEG);
        d = m.step(w, List.of(Vote.of(Channel.FORWARD, Tri.POS, 2, "patrol")), allOn());
        eq("强票拿 POS", Tri.POS, d.dirs[Channel.FORWARD.index()]);
        d = m.step(w, List.of(Vote.of(Channel.FORWARD, Tri.NEG, 1, "noise")), allOn());
        eq("弱反向票不翻转", Tri.POS, d.dirs[Channel.FORWARD.index()]);
        d = m.step(w, List.of(Vote.of(Channel.FORWARD, Tri.NEG, 2, "flee")), allOn());
        eq("强反向票才翻转", Tri.NEG, d.dirs[Channel.FORWARD.index()]);

        // 通道被禁用 → 静默
        m = armedMachine(1.0f, Tri.NEG);
        boolean[] ch = allOn();
        ch[Channel.ATTACK.index()] = false;
        d = m.step(wv(1.0f, Tri.NEG), List.of(Vote.of(Channel.ATTACK, Tri.POS, 5, "combat")), ch);
        eq("禁用通道静默", Tri.ZERO, d.dirs[Channel.ATTACK.index()]);

        // 低血 + 威胁 → 撤退，且强制向后
        m = armedMachine(0.2f, Tri.POS);
        d = m.step(wv(0.2f, Tri.POS), List.of(), allOn());
        eq("低血撤退", Phase.RETREAT, d.phase);
        eq("撤退强制后退", Tri.NEG, d.dirs[Channel.FORWARD.index()]);

        // 死亡 → 重生
        m = armedMachine(1.0f, Tri.NEG);
        WorldView dead = new WorldView();
        dead.dead = true;
        d = m.step(dead, List.of(), allOn());
        eq("死亡重生", Phase.RECOVER, d.phase);
        check("重生点击", d.recoverClick);

        // 未知威胁 → 不交战，站桩
        m = armedMachine(1.0f, Tri.ZERO);
        d = m.step(wv(1.0f, Tri.ZERO), List.of(), allOn());
        eq("未知威胁不交战", Phase.IDLE, d.phase);
        eq("未知威胁理由", Reason.UNKNOWN, d.reason);

        // 交过手会把未知威胁抬成有威胁
        m = armedMachine(1.0f, Tri.ZERO);
        WorldView contact = wv(1.0f, Tri.ZERO);
        contact.contact = true;
        d = m.step(contact, List.of(), allOn());
        eq("交手后转交战", Phase.ENGAGE, d.phase);

        // 交还控制权后立刻停机
        m = armedMachine(1.0f, Tri.POS);
        m.setArmed(false);
        d = m.step(wv(1.0f, Tri.POS), List.of(), allOn());
        eq("交还后停机", Phase.OFF, d.phase);

        // peek 与 step 结果一致
        m = armedMachine(1.0f, Tri.POS);
        WorldView w2 = wv(1.0f, Tri.POS);
        Phase peeked = m.peek(w2);
        Decision stepped = m.step(w2, List.of(), allOn());
        eq("peek 与 step 一致", peeked, stepped.phase);
    }

    private static void encoding() {
        Machine m = armedMachine(1.0f, Tri.POS);
        Decision d = m.step(wv(1.0f, Tri.POS), List.of(), allOn());
        String s = d.encode();
        check("encode 含阶段名", s.startsWith("engage|"));
        check("encode 含 7 个通道", s.length() == "engage|".length() + Channel.NCH);
    }

    // ------------------------------------------------------------------

    private static boolean[] allOn() {
        boolean[] b = new boolean[Channel.NCH];
        java.util.Arrays.fill(b, true);
        return b;
    }

    private static WorldView wv(float hp, Tri threat) {
        WorldView w = new WorldView();
        w.hp = hp;
        w.threat = threat;
        return w;
    }

    private static Machine armedMachine(float hp, Tri threat) {
        Machine m = new Machine();
        m.setArmed(true);
        return m;
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
