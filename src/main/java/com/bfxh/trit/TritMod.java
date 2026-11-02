package com.bfxh.trit;

import com.bfxh.trit.act.Actuator;
import com.bfxh.trit.behave.Behavior;
import com.bfxh.trit.behave.Behaviors;
import com.bfxh.trit.core.Channel;
import com.bfxh.trit.core.Decision;
import com.bfxh.trit.core.Machine;
import com.bfxh.trit.core.Phase;
import com.bfxh.trit.core.Vote;
import com.bfxh.trit.core.WorldView;
import com.bfxh.trit.sense.Sensor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 入口：三值状态机 AI 的客户端 mod。
 *
 * <p>和之前的方案（Rust 外部进程 + 数据包）的区别：
 * <ul>
 *   <li>感知直接从游戏世界读，不走聊天栏文本、没有解析延迟。</li>
 *   <li>操控直接写 {@code player.input} 和 {@code gameMode}，不伪造物理按键，
 *       不需要窗口焦点，也不会在进程崩溃后把按键卡住。</li>
 *   <li>状态机逻辑本身（K3 三值 + 投票 + 裁决 + 迟滞）原样保留。</li>
 * </ul>
 */
public class TritMod implements ClientModInitializer {

    public static final String MOD_ID = "trit";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static TritMod instance;

    private final Machine machine = new Machine();
    /** 懒加载：Sensor 建表要碰游戏注册表，别在 mod 初始化阶段就拽进来。 */
    private Sensor sensor;
    private final Actuator actuator = new Actuator();
    private final Behaviors.Anchor anchor = new Behaviors.Anchor();
    private final Behavior[] behaviors = Behaviors.defaults(anchor);

    private KeyMapping kToggle;
    private KeyMapping kHalt;
    private KeyMapping kView;
    private KeyMapping kAttack;
    private KeyMapping kMove;

    private long tick;
    private boolean moveEnabled = true;
    private boolean attackEnabled = true;
    private boolean viewEnabled = true;

    private WorldView lastView;
    private Decision lastDecision;
    private String lastVotes = "";

    @Override
    public void onInitializeClient() {
        instance = this;

        KeyMapping.Category cat = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        kToggle = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".toggle", InputConstants.KEY_F8, cat));
        kHalt = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".halt", InputConstants.KEY_F9, cat));
        kView = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".view", InputConstants.KEY_F10, cat));
        kAttack = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".attack", InputConstants.KEY_F6, cat));
        kMove = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".move", InputConstants.KEY_F7, cat));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        LOG.info("[TRIT] 已加载。F8 接管/交还，F9 急停，F10 视角，F6 攻击，F7 移动");
    }

    public static TritMod get() {
        return instance;
    }

    // ------------------------------------------------------------------

    private void onTick(Minecraft mc) {
        tick++;

        handleHotkeys(mc);

        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            // 不在世界里：确保松手，别把按键留在按下状态
            if (actuator.isAttached()) actuator.detach(p);
            lastView = null;
            return;
        }

        if (!machine.armed()) {
            if (actuator.isAttached()) actuator.detach(p);
            return;
        }

        actuator.attach(p);

        if (sensor == null) sensor = new Sensor();

        // 1) 感知
        WorldView w = sensor.sense(mc, tick, true);
        lastView = w;

        // 2) 先 peek 阶段，行为模块才知道该投什么票
        Phase phase = machine.peek(w);

        // 3) 投票
        Behavior.Ctx ctx = new Behavior.Ctx(w, phase, machine, tick);
        List<Vote> votes = new ArrayList<>(24);
        for (Behavior b : behaviors) {
            try {
                b.vote(ctx, votes);
            } catch (Throwable t) {
                // 单个模块炸了不能拖垮整条链：这一拍它就没意见
                LOG.warn("[TRIT] 行为模块 {} 抛异常: {}", b.name(), t.toString());
            }
        }
        // 拼票串只给 debug 用，每拍都拼会在 20 TPS 下持续产生垃圾；20 拍存一次足够回放
        if (tick % 20 == 0) {
            if (votes.isEmpty()) {
                lastVotes = "";
            } else {
                StringBuilder sb = new StringBuilder(votes.size() * 16);
                for (Vote v : votes) sb.append(v).append(' ');
                lastVotes = sb.toString();
            }
        }

        // 4) 裁决
        boolean[] enabled = new boolean[Channel.NCH];
        enabled[Channel.FORWARD.index()] = moveEnabled;
        enabled[Channel.STRAFE.index()] = moveEnabled;
        enabled[Channel.JUMP.index()] = moveEnabled;
        enabled[Channel.YAW.index()] = viewEnabled;
        enabled[Channel.PITCH.index()] = viewEnabled;
        enabled[Channel.ATTACK.index()] = attackEnabled;
        enabled[Channel.USE.index()] = true;

        Decision d = machine.step(w, votes, enabled);
        lastDecision = d;

        // 5) 执行
        actuator.apply(mc, d, w, moveEnabled, attackEnabled);

        // 6) 状态显示（action bar，不刷聊天栏）
        if (tick % 5 == 0) {
            p.sendOverlayMessage(Component.literal(statusLine()).withColor(0xFFCC55));
        }
    }

    private void handleHotkeys(Minecraft mc) {
        if (kToggle == null) return;
        if (kToggle.consumeClick()) {
            setArmed(mc, !machine.armed(), null);
        }
        if (kHalt.consumeClick()) {
            setArmed(mc, false, "§c[TRIT] 急停：全部松手");
        }
        if (kView.consumeClick()) {
            viewEnabled = !viewEnabled;
            notify(mc, (viewEnabled ? "§a[TRIT] 视角控制：开" : "§c[TRIT] 视角控制：关"));
        }
        if (kAttack.consumeClick()) {
            attackEnabled = !attackEnabled;
            notify(mc, (attackEnabled ? "§a[TRIT] 攻击：开" : "§c[TRIT] 攻击：关"));
        }
        if (kMove.consumeClick()) {
            moveEnabled = !moveEnabled;
            notify(mc, (moveEnabled ? "§a[TRIT] 移动：开" : "§c[TRIT] 移动：关"));
        }
    }

    /**
     * @param message 自定义提示；null 用默认文案。急停和交还是同一件事，
     *                不给自定义就会连着弹两条重复消息。
     */
    private void setArmed(Minecraft mc, boolean on, String message) {
        machine.setArmed(on);
        LocalPlayer p = mc.player;
        if (on) {
            if (sensor != null) sensor.reset();
            if (p != null) {
                anchor.setAnchor(new double[]{p.getX(), p.getY(), p.getZ()});
                actuator.attach(p);
            }
            notify(mc, message != null ? message : "§a[TRIT] 已接管 —— 再按 F8 交还");
        } else {
            if (p != null) actuator.detach(p);
            notify(mc, message != null ? message : "§c[TRIT] 已交还控制权");
        }
    }

    private static void notify(Minecraft mc, String msg) {
        LocalPlayer p = mc.player;
        if (p != null) {
            p.sendSystemMessage(Component.literal(msg));
        }
        LOG.info("[TRIT] {}", msg.replaceAll("§.", ""));
    }

    /** action bar 上的一行状态。 */
    private String statusLine() {
        if (lastView == null || lastDecision == null) return "[TRIT] --";
        return String.format("[TRIT] %s %s | %s | %s",
                lastDecision.phase.zh(),
                lastDecision.encode(),
                lastView.summary(),
                lastDecision.reason.zh());
    }

    // ---- 调试用：把当前内部状态导出成一行，供日志或命令查看 ----
    public String debugLine() {
        if (lastView == null || lastDecision == null) return "no data";
        return statusLine() + " | votes: " + lastVotes
                + " | tick " + tick
                + " | move=" + moveEnabled + " attack=" + attackEnabled + " view=" + viewEnabled;
    }
}
