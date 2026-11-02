package com.bfxh.trit.act;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * AI 专用的输入源。
 *
 * <p>做法是直接把 {@code LocalPlayer.input} 换成这个实例，而不是去伪造物理按键。
 * 好处：不依赖玩家的按键绑定（改了 W/A/S/D 也能用），不受窗口焦点影响，
 * 也不会像 {@code SendInput} 那样在进程崩溃后把按键卡在按下状态。
 *
 * <p>{@code moveVector} 的坐标约定与 {@code KeyboardInput} 一致：
 * x = 平移（左为正），z = 前后（前为正）。
 */
public final class AiInput extends ClientInput {

    private boolean forward, backward, left, right, jump, shift, sprint;

    @Override
    public void tick() {
        keyPresses = new Input(forward, backward, left, right, jump, shift, sprint);

        float x = 0f;
        float z = 0f;
        if (forward) z += 1f;
        if (backward) z -= 1f;
        if (left) x += 1f;
        if (right) x -= 1f;
        float len = (float) Math.sqrt(x * x + z * z);
        if (len > 1f) {
            x /= len;
            z /= len;
        }
        moveVector = new Vec2(x, z);
    }

    /** 一次性设置全部按键状态。 */
    public void set(boolean f, boolean b, boolean l, boolean r, boolean j, boolean sh, boolean spr) {
        forward = f;
        backward = b;
        left = l;
        right = r;
        jump = j;
        shift = sh;
        sprint = spr;
    }

    /** 全部松开。交还控制权、停机、掉线时必须调用。 */
    public void release() {
        set(false, false, false, false, false, false, false);
    }
}
