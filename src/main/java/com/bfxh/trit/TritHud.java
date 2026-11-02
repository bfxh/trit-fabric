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
 */
public final class TritHud implements HudElement {

    private final TritMod mod;

    public TritHud(TritMod mod) {
        this.mod = mod;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker dt) {
        if (!mod.isMenuOpen()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) return;

        int W = g.guiWidth();
        int H = g.guiHeight();
        int cx = W / 2;
        int cy = H / 2;
        int R = Math.min(W, H) / 4;

        Mode[] opts = mod.menuOptions();
        int n = opts.length;
        int sel = mod.menuSelection();

        // 半透明压暗，让环盘更清楚（不暂停游戏）
        g.fill(0, 0, W, H, 0x70000000);

        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + (double) i * 2.0 * Math.PI / n;
            int ox = cx + (int) (R * Math.cos(a));
            int oy = cy + (int) (R * Math.sin(a));
            int bw = 96, bh = 26;
            int bx = ox - bw / 2, by = oy - bh / 2;
            int color = (i == sel) ? 0xFF3C3489 : 0xFF20202C;
            g.fill(bx - 2, by - 2, bx + bw + 2, by + bh + 2, 0xFF000000);
            g.fill(bx, by, bx + bw, by + bh, color);
            g.centeredText(mc.font, opts[i].zh(), ox, oy - 4, 0xFFFFFFFF);
        }

        // 中心 hub
        int hw = 160, hh = 30;
        g.fill(cx - hw / 2 - 2, cy - hh / 2 - 2, cx + hw / 2 + 2, cy + hh / 2 + 2, 0xFF000000);
        g.fill(cx - hw / 2, cy - hh / 2, cx + hw / 2, cy + hh / 2, 0xFF0C447C);
        g.centeredText(mc.font, "Y 环盘 · 选模式", cx, cy - 4, 0xFFFFFFFF);
        g.centeredText(mc.font, "← → 选择 · Y/回车确认 · 退格取消", cx, cy + H / 2 + 8, 0xFFCCCCCC);
    }
}
