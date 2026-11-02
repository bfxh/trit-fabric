package com.bfxh.trit;

import com.bfxh.trit.core.Mode;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 环盘菜单的 HUD 叠层。
 *
 * <p>26.3 把原来的 {@code GuiGraphics} 渲染模型换成了「渲染状态抽取」
 * （{@code extractRenderState(GuiGraphicsExtractor, DeltaTracker)}），
 * Screen 也不再提供 {@code render(GuiGraphics,...)} 重写钩子。所以菜单不再是一个
 * 会暂停游戏的 Screen，而是一个始终参与 HUD 抽取的叠层：只在 {@link TritMod#isMenuOpen()}
 * 为真时画一圈选项，天然不暂停世界，你也能照常操作。
 *
 * <p>输入不走 Screen 的回调，全部在 {@code TritMod.onTick} 里轮询键位（和 AI 接管同一套机制），
 * 因此完全绕开了 26.3 输入事件化的坑。
 *
 * <p>布局：正中是「确认」hub，四周是一圈模式圆点，正上方是「全停」。选中项高亮并朝外伸出楔形。
 * 跟随模式下还会在屏幕边缘画一个指向跟随目标的箭头。
 */
public final class TritHud implements HudElement {

    private final TritMod mod;

    public TritHud(TritMod mod) {
        this.mod = mod;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker dt) {
        if (!mod.isMenuOpen()) {
            // 非菜单时也画跟随指向箭头（轻量，不动其它 UI）
            drawFollowArrow(g);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) return;

        int W = g.guiWidth();
        int H = g.guiHeight();
        int cx = W / 2;
        int cy = H / 2;
        int R = Math.min(W, H) / 4;          // 模式圆点所在半径
        int RH = Math.max(44, R / 3);         // 中心 hub 半径

        Mode[] opts = mod.menuOptions();
        int n = opts.length;
        int sel = mod.menuSelection();

        // 半透明压暗，让环盘更清楚（不暂停游戏）
        g.fill(0, 0, W, H, 0x60000000);

        // 中心到每个圆点的连线
        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + (double) i * 2.0 * Math.PI / n;
            int ox = cx + (int) (R * Math.cos(a));
            int oy = cy + (int) (R * Math.sin(a));
            drawLine(g, cx, cy, ox, oy, i == sel ? 0xFF6A5ACD : 0x55333355, 2);
        }

        // 中心 hub（确认）
        fillCircle(g, cx, cy, RH, 0xFF0C447C);
        fillCircle(g, cx, cy, RH - 3, 0xFF0A6CB0);
        g.centeredText(mc.font, "Y 确认", cx, cy - 14, 0xFFFFFFFF);
        g.centeredText(mc.font, opts[sel].zh(), cx, cy + 6, 0xFFDDEEFF);
        g.centeredText(mc.font, "← → 选择", cx, cy + RH + 16, 0xFFCCCCCC);

        // 四周模式圆点
        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + (double) i * 2.0 * Math.PI / n;
            int ox = cx + (int) (R * Math.cos(a));
            int oy = cy + (int) (R * Math.sin(a));
            int tok = 28;
            boolean isStop = opts[i] == Mode.OFF;       // 全停：红
            boolean isSel = i == sel;

            if (isSel) {
                // 选中：朝外伸出楔形高亮
                int ex = cx + (int) ((R + 26) * Math.cos(a));
                int ey = cy + (int) ((R + 26) * Math.sin(a));
                drawLine(g, ox, oy, ex, ey, 0xFF9FE0FF, 6);
            }

            int fill = isStop ? 0xFF8B2E2E : (isSel ? 0xFF3C3489 : 0xFF20202C);
            int edge = isSel ? 0xFF9FE0FF : (isStop ? 0xFFFF6B6B : 0xFF555566);
            fillCircle(g, ox, oy, tok, edge);
            fillCircle(g, ox, oy, tok - 3, fill);
            g.centeredText(mc.font, opts[i].zh(), ox, oy - 4, 0xFFFFFFFF);
        }

        // 底部提示
        g.centeredText(mc.font, "Y/回车 确认 · 退格 取消 · 数字 1-6 直达", cx, H - 28, 0xFFBBBBBB);
    }

    /** 跟随模式：在屏幕边缘画一个指向跟随目标的箭头。 */
    private void drawFollowArrow(GuiGraphicsExtractor g) {
        if (mod.currentMode() != Mode.FOLLOW) return;
        Float yaw = mod.followYawHint();
        if (yaw == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) return;
        int W = g.guiWidth();
        int H = g.guiHeight();
        int cx = W / 2, cy = H / 2;
        int r = Math.min(W, H) / 3;
        // 0° = 正前(上屏)，正 yaw = 目标在右
        double a = Math.toRadians(yaw);
        int ax = cx + (int) (Math.sin(a) * r);
        int ay = cy - (int) (Math.cos(a) * r);
        // 简单三角箭头
        int s = 10;
        int dx = (int) (Math.cos(a) * s);
        int dy = (int) (Math.sin(a) * s);
        drawLine(g, ax, ay, ax - dy, ay + dx, 0xFF7CFC00, 4);
        drawLine(g, ax, ay, ax + dy, ay - dx, 0xFF7CFC00, 4);
        g.centeredText(mc.font, "跟随目标 →", cx, cy - r - 18, 0xFF7CFC00);
    }

    // ------------------------------------------------------------------
    // 画图辅助（GuiGraphicsExtractor 没有原生画圆/画线）

    private static void fillCircle(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.floor(Math.sqrt((long) r * r - (long) dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private static void drawLine(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color, int w) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps == 0) {
            g.fill(x1 - w / 2, y1 - w / 2, x1 + w / 2 + 1, y1 + w / 2 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            g.fill(x - w / 2, y - w / 2, x + w / 2 + 1, y + w / 2 + 1, color);
        }
    }
}
