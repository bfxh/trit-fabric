package com.bfxh.trit;

import com.bfxh.trit.act.Actuator;
import com.bfxh.trit.behave.Behavior;
import com.bfxh.trit.behave.Behaviors;
import com.bfxh.trit.core.CameraBudget;
import com.bfxh.trit.core.Channel;
import com.bfxh.trit.core.Decision;
import com.bfxh.trit.core.Machine;
import com.bfxh.trit.core.Mode;
import com.bfxh.trit.core.Phase;
import com.bfxh.trit.core.Vote;
import com.bfxh.trit.core.WorldView;
import com.bfxh.trit.neo.AiPointer;
import com.bfxh.trit.sense.Sensor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
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

    /** 环盘菜单的选项，顺序即方位：index 0 在正上方（全停）。 */
    private static final Mode[] MENU_OPTIONS = {
            Mode.OFF, Mode.PATROL, Mode.FORAGE, Mode.COMBAT, Mode.BUILD, Mode.FOLLOW
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
    /** 摄像头预算档位循环键（默认 F8）。 */
    private KeyMapping kCam;
    // 环盘菜单导航键
    private KeyMapping kMenuPrev;
    private KeyMapping kMenuNext;
    private KeyMapping kMenuConfirm;
    private KeyMapping kMenuCancel;
    private KeyMapping[] kMenuDigit = new KeyMapping[6];

    /** 摄像头预算：AI 自己选择调不调，但幅度与归还由这一层判。 */
    private final CameraBudget camBudget = new CameraBudget();
    /** AI 的独立指针：预算不够时「看」这件事发生在它身上，不碰真实镜头。 */
    private final AiPointer pointer = new AiPointer();
    /** 借用的机位还剩几拍（第三人称看清大概范围后自动归还）。 */
    private int perspectiveBorrowLeft;
    /** AI 请求设置过的机位；玩家自己按 F5 改了就立刻放弃借用。 */
    private int aiSetCamMode = -1;
    private static final int PERSPECTIVE_TICKS = 60;

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
        kCam = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + MOD_ID + ".camera", InputConstants.KEY_F8, cat));

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
        actuator.setCameraBudget(camBudget);

        // 环盘菜单：注册成 HUD 叠层（26.3 不再有 GuiGraphics，改用 HudElement 抽取模型）
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "menu"), new TritHud(this));

        // 跟踪原生界面开关：打开时 AI 让权，绝不抢你的操作（26.3 没有 mc.screen 访问器）
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            nativeScreenOpen = true;
            ScreenEvents.remove(screen).register((s) -> nativeScreenOpen = false);
        });

        LOG.info("[TRIT] 已加载。按 Y 打开环盘菜单选择模式，F9 急停，F10 视角，F6 攻击，F7 移动，F8 摄像头预算");
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
        w.camBudgetLevel = camBudget.level().ordinal();
        updatePointer(w);

        // 玩家自己操作时（按移动/攻击/使用/蹲下键，或正在破坏方块）→ 全部让权，绝不抢。
        // 注意：开原生界面（背包/聊天）不再整体让权——AI 决策照常跑，只锁住视角与动作，
        // 这样「你开背包它不会傻掉、关掉立刻恢复」，也不会在你读菜单时乱转视角。
        boolean yieldAll = w.humanActing;
        // 机位借用（「一调三」看清大概范围）：到期归还、玩家手动改了立刻放弃，
        // 这两件事即使在让权期间也要做，否则镜头会一直停在 AI 借的机位上。
        handlePerspective(mc, w, !yieldAll);
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

        // 5) 执行（nativeScreenOpen 时 locked=true：锁视角/动作，但不冻结 AI）
        actuator.apply(mc, d, w, moveEnabled, attackEnabled, nativeScreenOpen);

        // 5b) 摄像头读数：本拍 AI 真写了多少度、偏离锚点多少、被预算夹掉多少
        w.camAppliedYaw = camBudget.appliedYaw();
        w.camAppliedPitch = camBudget.appliedPitch();
        w.camDeviation = camBudget.deviationYaw();
        w.camClippedTotal = (float) camBudget.clippedTotal();

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
        if (kCam != null && kCam.consumeClick()) {
            CameraBudget.Level lv = camBudget.cycle();
            notify(mc, "§a[TRIT] 摄像头预算：" + lv.zh + "（±" + (int) lv.limit + "°）");
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
            return;
        }
        machine.setArmed(true);
        mode = m;
        if (sensor == null) sensor = new Sensor();
        sensor.reset();
        // 跟随：优先跟「准星所指的实体」，否则跟最近的玩家
        if (m == Mode.FOLLOW) {
            Entity e = mc.crosshairPickEntity;
            if (e != null && e.isAlive() && (p == null || e != p)) {
                sensor.setForcedFollow(e.getId());
                notify(mc, "§a[TRIT] 跟随：" + e.getName().getString() + "（准星所指）");
            } else {
                sensor.setForcedFollow(null);
                notify(mc, "§a[TRIT] 跟随：最近的玩家");
            }
        } else {
            sensor.setForcedFollow(null);
        }
        if (p != null) {
            anchor.setAnchor(new double[]{p.getX(), p.getY(), p.getZ()});
            actuator.attach(p);
        }
        notify(mc, "§a[TRIT] 模式：" + m.zh() + " —— 按 Y 再打开环盘菜单切换");
    }

    public Mode currentMode() {
        return mode;
    }

    /** 当前跟随目标相对朝向的水平角差（度，正 = 在右边），无目标返回 null。供 HUD 画指向箭头。 */
    public Float followYawHint() {
        return lastView != null ? lastView.followYaw : null;
    }

    /** 当前跟随目标 id，-1 = 无。 */
    public int followIdHint() {
        return lastView != null ? lastView.followId : -1;
    }

    // ------------------------------------------------------------------
    // 独立指针 / 机位借用（摄像头所有权见 docs/需求与设计.md §3）

    /**
     * 维护 AI 的独立指针。
     *
     * <p>档位允许自由看（跟踪/自由）时，指针就贴着真实镜头 —— 不必多花一份算力；
     * 档位收紧（关/微调）时，待命扫视改在这根指针上转，真实镜头一动不动。
     */
    private void updatePointer(WorldView w) {
        if (w.yaw == null || w.pitch == null) {
            w.pointerYaw = null;
            w.pointerPitch = null;
            w.pointerToCamera = null;
            return;
        }
        if (camBudget.allowsFreeLook()) {
            pointer.set(w.yaw, w.pitch);
        } else {
            float center = w.yaw;
            float phase = (float) (0.5 + 0.5 * Math.cos(tick * 0.02));
            pointer.scanAround(center, 150f, phase);
            pointer.stepToward(9f);
        }
        w.pointerYaw = pointer.yaw();
        w.pointerPitch = pointer.pitch();
        float dy = wrap180(pointer.yaw() - w.yaw);
        float dp = pointer.pitch() - w.pitch;
        w.pointerToCamera = (float) Math.sqrt((double) dy * dy + (double) dp * dp);
    }

    /**
     * 机位借用：AI 觉得「第一人称看不清大概范围」时，自己把镜头切到第三人称背后，
     * {@value #PERSPECTIVE_TICKS} 拍后自动换回来。玩家自己按过 F5 就立刻放弃借用。
     *
     * @param canAcquire false = 玩家正在操作，只允许到期归还，不允许新借
     */
    private void handlePerspective(Minecraft mc, WorldView w, boolean canAcquire) {
        LocalPlayer p = mc.player;
        if (p == null || w.camMode == null) return;
        int mode = w.camMode;

        if (perspectiveBorrowLeft > 0) {
            if (aiSetCamMode >= 0 && mode != aiSetCamMode) {
                // 玩家手动改时机位所有权立刻作废
                perspectiveBorrowLeft = 0;
                aiSetCamMode = -1;
                return;
            }
            if (--perspectiveBorrowLeft == 0) {
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                aiSetCamMode = -1;
            }
            return;
        }
        if (!canAcquire || !camBudget.allowsPerspectiveSwitch()) return;
        // 借机位的条件：A 级弹幕扎堆（≥2）且当前是第一人称 —— 单颗弹不需要看清范围
        Integer aGrade = w.projectileAGrade;
        if (aGrade == null || aGrade < 2) return;
        if (mode != 0) return;
        if (actuator.requestPerspective(mc, CameraType.THIRD_PERSON_BACK)) {
            perspectiveBorrowLeft = PERSPECTIVE_TICKS;
            aiSetCamMode = 1;
        }
    }

    private static float wrap180(float a) {
        while (a > 180f) a -= 360f;
        while (a <= -180f) a += 360f;
        return a;
    }

    // ---- HUD 读数 ----

    /** 相机模式序号（0/1/2），null = 未知。 */
    public Integer camModeHint() {
        return lastView != null ? lastView.camMode : null;
    }

    /** 来袭方位（相对朝向的水平角差，度），null = 没有 A 级弹。 */
    public Float incomingYawHint() {
        return lastView != null ? lastView.yawToProj : null;
    }

    /** AI 是否处于接管状态（HUD 用它决定要不要画指标）。 */
    public boolean isArmed() {
        return machine.armed();
    }

    /**
     * 视角归属一行读数：现在是第几视角 + 预算档位 + 本拍 AI 真写了多少度。
     * 用户要的「有指标，知道是看第三还是第一」就是这一行。
     */
    public String cameraHint() {
        WorldView w = lastView;
        String mode = WorldView.camModeName(w == null ? null : w.camMode);
        float wrote = w == null ? 0f : Math.max(w.camAppliedYaw, w.camAppliedPitch);
        String owner = wrote < 0.05f ? "归你" : ("AI动" + String.format("%.1f°", wrote));
        return "镜" + mode + " · 预算" + camBudget.level().zh + " · " + owner;
    }

    /** 弹幕一行读数，供 HUD 与调试用。 */
    public String projectileHint() {
        WorldView w = lastView;
        if (w == null || w.projectileCount == null) return "弹:?";
        if (w.projThreat == com.bfxh.trit.core.Tri.POS) {
            return String.format("弹%d A%d %s tti%.0f cpa%.1f 带%.1f σ%.2f 躲%s",
                    w.projectileCount, w.projectileAGrade == null ? 0 : w.projectileAGrade,
                    w.projKind == null ? "?" : w.projKind,
                    w.projTti == null ? -1 : w.projTti,
                    w.projCpa == null ? -1 : w.projCpa,
                    w.projBand == null ? 0 : w.projBand,
                    w.projSigma == null ? 0 : w.projSigma,
                    dodgeName(w.dodgeAction));
        }
        return "弹" + w.projectileCount + " 无A级";
    }

    /** 躲避动作名（与 ProjectilePredictor 的下标约定一致）。 */
    private static final String[] DODGE_NAMES = {"左", "右", "后", "前", "跳"};

    private static String dodgeName(int action) {
        return (action >= 0 && action < DODGE_NAMES.length) ? DODGE_NAMES[action] : "无";
    }

    /**
     * 已学的躲避先验一行读数：只有 A 级弹的那一类才有意义。
     * 实机判据要看「挨打后同一类弹的某个方向转负」，就得能从日志里读出来。
     */
    private String priorHint() {
        if (sensor == null || lastView == null || lastView.projKind == null) return "先验:无";
        com.bfxh.trit.neo.Ballistics.Kind kind;
        try {
            kind = com.bfxh.trit.neo.Ballistics.Kind.valueOf(
                    lastView.projKind.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "先验:无";
        }
        float[] w = sensor.projectiles().dodgePriorOf(kind);
        StringBuilder sb = new StringBuilder(48);
        sb.append("先验 ").append(lastView.projKind).append(':');
        for (int i = 0; i < w.length && i < DODGE_NAMES.length; i++) {
            sb.append(DODGE_NAMES[i]).append(String.format("%+.2f ", w[i]));
        }
        return sb.toString();
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
        WorldView w = lastView;
        return statusLine() + " | votes: " + lastVotes
                + " | tick " + tick
                + " | move=" + moveEnabled + " attack=" + attackEnabled + " view=" + viewEnabled
                + " | cam mode=" + w.camMode + " budget=" + w.camBudgetLevel
                + " wrote=" + String.format("%.1f/%.1f", w.camAppliedYaw, w.camAppliedPitch)
                + " dev=" + String.format("%.1f", w.camDeviation)
                + " clipped=" + String.format("%.0f", w.camClippedTotal)
                + " | " + projectileHint()
                + " | " + priorHint()
                + " | ptr=" + String.format("%.0f/%.0f Δ%.0f",
                w.pointerYaw == null ? 0 : w.pointerYaw,
                w.pointerPitch == null ? 0 : w.pointerPitch,
                w.pointerToCamera == null ? 0 : w.pointerToCamera);
    }
}
