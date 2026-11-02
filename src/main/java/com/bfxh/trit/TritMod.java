package com.bfxh.trit;

import com.bfxh.trit.act.Actuator;
import com.bfxh.trit.behave.Behavior;
import com.bfxh.trit.behave.Behaviors;
import com.bfxh.trit.core.Channel;
import com.bfxh.trit.core.Decision;
import com.bfxh.trit.core.Machine;
import com.bfxh.trit.core.Mode;
import com.bfxh.trit.core.Phase;
import com.bfxh.trit.core.Vote;
import com.bfxh.trit.core.WorldView;
import com.bfxh.trit.sense.Sensor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
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
 *
 * <p>26.3 适配要点：
 * <ul>
 *   <li>「环盘菜单」不再用会暂停游戏的 {@code Screen}（26.3 的 Screen 渲染模型大改），
 *       而是注册成一个始终参与 HUD 抽取的 {@code HudElement} 叠层；菜单开着世界照样跑，
 *       你也能照常操作。</li>
 *   <li>26.3 没有 {@code mc.screen} 访问器，检测「你是不是在开背包/聊天」改走
 *       Fabric 的 {@link ScreenEvents}；一旦原生界面开着，AI 立刻让权，绝不抢控制权。</li>
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

    /** 环盘菜单选出的主动模式（默认交还控制权）。 */
    private Mode mode = Mode.OFF;

    /** 环盘菜单的选项，顺序即方位。 */
    private static final Mode[] MENU_OPTIONS = {
            Mode.PATROL, Mode.FORAGE, Mode.COMBAT, Mode.BUILD, Mode.FOLLOW, Mode.OFF
    };
    /** 环盘菜单是否打开（HUD 叠层，不暂停游戏）。 */
    private boolean menuOpen;
    /** 当前高亮选项下标。 */
    private int menuSel;
    /** 是否打开了原生界面（背包/聊天/任务栏等）：打开时 AI 必须让权。 */
    private boolean nativeScreenOpen;

    private KeyMapping kMenu;
    private KeyMapping kHalt;
    private KeyMapping kView;
    private KeyMapping kAttack;
    private KeyMapping kMove;
    // 环盘菜单导航键
    private KeyMapping kMenuPrev;
    private KeyMapping kMenuNext;
    private KeyMapping kMenuConfirm;
    private KeyMapping kMenuCancel;
    private KeyMapping[] kMenuDigit = new KeyMapping[6];

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

        kMenu = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".menu", InputConstants.KEY_Y, cat));
        kHalt = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".halt", InputConstants.KEY_F9, cat));
        kView = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".view", InputConstants.KEY_F10, cat));
        kAttack = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".attack", InputConstants.KEY_F6, cat));
        kMove = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".move", InputConstants.KEY_F7, cat));

        // 菜单导航键（与游戏默认移动键不冲突：方向键 / 数字键 / 回车 / 退格）
        kMenuPrev = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".menu.prev", InputConstants.KEY_LEFT, cat));
        kMenuNext = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".menu.next", InputConstants.KEY_RIGHT, cat));
        kMenuConfirm = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".menu.confirm", InputConstants.KEY_RETURN, cat));
        kMenuCancel = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".menu.cancel", InputConstants.KEY_BACKSPACE, cat));
        for (int i = 0; i < 6; i++) {
            kMenuDigit[i] = KeyMappingHelper.registerKeyMapping(
                    new KeyMapping("key." + MOD_ID + ".menu.d" + i, InputConstants.KEY_1 + i, cat));
        }

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // 环盘菜单：注册成 HUD 叠层（26.3 不再有 GuiGraphics，改用 HudElement 抽取模型）
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "menu"), new TritHud(this));

        // 跟踪原生界面开关：打开时 AI 让权，绝不抢你的操作（26.3 没有 mc.screen 访问器）
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            nativeScreenOpen = true;
            ScreenEvents.remove(screen).register((s) -> nativeScreenOpen = false);
        });

        LOG.info("[TRIT] 已加载。按 Y 打开环盘菜单选择模式，F9 急停，F10 视角，F6 攻击，F7 移动");
    }

    public static TritMod get() {
        return instance;
    }

    // ---- 给 HUD 叠层读的状态 ----

    public boolean isMenuOpen() {
        return menuOpen;
    }

    public int menuSelection() {
        return menuSel;
    }

    public Mode[] menuOptions() {
        return MENU_OPTIONS;
    }

    // ------------------------------------------------------------------

    private void onTick(Minecraft mc) {
        tick++;

        // 原生界面开着时顺手关掉自己的菜单，避免两套 UI 叠在一起
        if (nativeScreenOpen && menuOpen) menuOpen = false;

        if (menuOpen) {
            handleMenu(mc);
        } else if (!nativeScreenOpen) {
            handleHotkeys(mc);
        }

        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            // 不在世界里：确保松手，别把按键留在按下状态
            if (actuator.isAttached()) actuator.detach(p);
            lastView = null;
            return;
        }

        // 菜单开着：AI 完全让权，玩家自由操作选模式
        if (menuOpen) {
            if (actuator.isAttached()) actuator.detach(p);
            return;
        }

        if (!machine.armed()) {
            if (actuator.isAttached()) actuator.detach(p);
            return;
        }

        if (sensor == null) sensor = new Sensor();
        // 跟随模式：锚点始终跟在玩家身上，表现为「跟着你走」
        if (mode == Mode.FOLLOW) {
            anchor.setAnchor(new double[]{p.getX(), p.getY(), p.getZ()});
        }

        // 1) 感知
        WorldView w = sensor.sense(mc, tick, true);
        lastView = w;

        // 玩家自己操作时（按移动/攻击/使用键，或正在破坏方块，或开了原生界面）→ 全部让权
        boolean yieldAll = w.humanActing || nativeScreenOpen;
        if (yieldAll) {
            actuator.detach(p); // 交还物理输入给玩家（幂等）
            return;
        }

        actuator.attach(p);

        // 2) 先 peek 阶段，行为模块才知道该投什么票
        Phase phase = machine.peek(w);

        // 3) 投票
        Behavior.Ctx ctx = new Behavior.Ctx(w, phase, machine, tick, mode);
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
        java.util.Arrays.fill(enabled, true);
        enabled[Channel.FORWARD.index()] = moveEnabled;
        enabled[Channel.STRAFE.index()] = moveEnabled;
        enabled[Channel.JUMP.index()] = moveEnabled;
        enabled[Channel.YAW.index()] = viewEnabled;
        enabled[Channel.PITCH.index()] = viewEnabled;
        enabled[Channel.ATTACK.index()] = attackEnabled;
        enabled[Channel.RANGED.index()] = attackEnabled;
        // USE / CROUCH / PLACE 默认开，由行为层按需投票

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
        if (kMenu == null) return;
        if (kMenu.consumeClick()) {
            openMenu(mc);
        }
        if (kHalt.consumeClick()) {
            applyMode(Mode.OFF);
            notify(mc, "§c[TRIT] 急停：全部松手");
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

    /** 菜单打开时的导航：方向键选、Y/回车确认、退格取消、数字键直达。 */
    private void handleMenu(Minecraft mc) {
        if (kMenuPrev.consumeClick()) {
            menuSel = (menuSel - 1 + MENU_OPTIONS.length) % MENU_OPTIONS.length;
        }
        if (kMenuNext.consumeClick()) {
            menuSel = (menuSel + 1) % MENU_OPTIONS.length;
        }
        for (int i = 0; i < kMenuDigit.length; i++) {
            if (kMenuDigit[i].consumeClick()) {
                menuSel = i;
                confirmMenu(mc);
                return;
            }
        }
        if (kMenu.consumeClick() || kMenuConfirm.consumeClick()) {
            confirmMenu(mc);
            return;
        }
        if (kMenuCancel.consumeClick()) {
            menuOpen = false;
        }
    }

    /** 打开环盘菜单（HUD 叠层，不暂停游戏）。 */
    private void openMenu(Minecraft mc) {
        if (nativeScreenOpen) return;
        menuSel = 0;
        for (int i = 0; i < MENU_OPTIONS.length; i++) {
            if (MENU_OPTIONS[i] == mode) {
                menuSel = i;
                break;
            }
        }
        menuOpen = true;
    }

    private void confirmMenu(Minecraft mc) {
        applyMode(MENU_OPTIONS[menuSel]);
        menuOpen = false;
    }

    /** 由环盘菜单调用：选定一个模式并接管（OFF = 交还）。 */
    public void applyMode(Mode m) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (m == Mode.OFF) {
            machine.setArmed(false);
            mode = Mode.OFF;
            if (p != null) actuator.detach(p);
            notify(mc, "§c[TRIT] 已交还控制权");
        } else {
            machine.setArmed(true);
            mode = m;
            if (sensor != null) sensor.reset();
            if (p != null) {
                anchor.setAnchor(new double[]{p.getX(), p.getY(), p.getZ()});
                actuator.attach(p);
            }
            notify(mc, "§a[TRIT] 模式：" + m.zh() + " —— 按 Y 再打开环盘菜单切换");
        }
    }

    public Mode currentMode() {
        return mode;
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
        return String.format("[TRIT] <%s> %s %s | %s | %s",
                mode.id(),
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
