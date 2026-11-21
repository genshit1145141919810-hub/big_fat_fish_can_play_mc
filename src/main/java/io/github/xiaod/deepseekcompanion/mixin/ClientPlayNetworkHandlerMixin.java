package io.github.xiaod.deepseekcompanion.mixin;

import io.github.xiaod.deepseekcompanion.command.LocalCommandHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Intercepts outgoing chat so {@code .xd ...} stays local (never reaches the server). */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true, require = 0)
    private void xiaod$sendChatMessage(String content, CallbackInfo ci) {
        try {
            if (LocalCommandHandler.handle(content)) {
                ci.cancel();
            }
        } catch (Throwable error) {
            // 命令处理器崩了也不能把这条命令当聊天发到服务器上，更不能炸掉聊天
            io.github.xiaod.deepseekcompanion.DeepseekCompanionClient.LOGGER
                    .error("[XiaoD] command failed: {}", content, error);
            ci.cancel();
        }
    }
}
