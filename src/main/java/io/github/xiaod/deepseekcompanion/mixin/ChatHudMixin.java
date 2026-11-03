package io.github.xiaod.deepseekcompanion.mixin;

import io.github.xiaod.deepseekcompanion.chat.ChatCoordinator;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads chat after the game has formatted it, so the line already looks like
 * {@code <Steve> hello}. This avoids depending on the signed-chat packet shapes
 * that keep changing between versions.
 */
@Mixin(ChatHud.class)
public class ChatHudMixin {
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), require = 0)
    private void xiaod$onAddMessage(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        ChatCoordinator.onIncoming(message);
    }
}
