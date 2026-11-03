package io.github.xiaod.deepseekcompanion.mixin;

import io.github.xiaod.deepseekcompanion.bot.HomeZone;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 家的保护区：在发往服务端的最后一个出口拦下破坏方块。
 *
 * <p>手动挖、{@code ClientPlayerInteractionManager}、Baritone 的破坏动作
 * 最终都会变成 {@link PlayerActionC2SPacket}，所以这里是必经之路。
 * 只拦 START / STOP / ABORT_DESTROY_BLOCK，别的一概不动。</p>
 */
@Mixin(ClientCommonNetworkHandler.class)
public class ClientCommonNetworkHandlerMixin {
    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true, require = 1)
    private void xiaod$guardBlockBreaking(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof PlayerActionC2SPacket action)) {
            return;
        }
        PlayerActionC2SPacket.Action kind = action.getAction();
        if (kind != PlayerActionC2SPacket.Action.START_DESTROY_BLOCK
                && kind != PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK
                && kind != PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK) {
            return;
        }
        if (HomeZone.isProtected(action.getPos())) {
            HomeZone.onBlockedBreak(action.getPos());
            ci.cancel();
        }
    }
}
