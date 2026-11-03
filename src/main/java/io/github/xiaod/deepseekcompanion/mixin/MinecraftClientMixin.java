package io.github.xiaod.deepseekcompanion.mixin;

import io.github.xiaod.deepseekcompanion.bot.BotBrain;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drives the companion brain on the client thread. */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void xiaod$tick(CallbackInfo ci) {
        BotBrain.tick((MinecraftClient) (Object) this);
    }
}
