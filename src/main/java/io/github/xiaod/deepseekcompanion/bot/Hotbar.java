package io.github.xiaod.deepseekcompanion.bot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;

/**
 * 换手上的物品（快捷栏槽位）时，把"我换槽了"这件事立刻告诉服务器。
 *
 * <p>原版是等客户端下一 tick 才把 {@code UpdateSelectedSlotC2SPacket} 发出去；
 * 我们如果在同一 tick 里"换槽 + 右键/使用/丢弃"，服务器还在用旧槽位处理这个动作：
 * 于是摆下去的是上一个东西（实测：熔炉换到手上，服务器却拿旧物品右键，熔炉放不下去），
 * 吃东西也可能白点。</p>
 */
public final class Hotbar {
    private Hotbar() {
    }

    /** 选中某个快捷栏槽位，并立刻同步给服务器。 */
    public static void select(MinecraftClient mc, int slot) {
        ClientPlayerEntity p = mc.player;
        if (p == null) {
            return;
        }
        p.getInventory().setSelectedSlot(slot);
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }
}
