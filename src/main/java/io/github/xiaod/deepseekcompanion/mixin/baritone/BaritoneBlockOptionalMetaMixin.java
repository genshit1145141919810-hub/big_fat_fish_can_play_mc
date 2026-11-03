package io.github.xiaod.deepseekcompanion.mixin.baritone;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.DynamicRegistryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes a Baritone 1.11.x deadlock.
 *
 * <p>{@code BlockOptionalMeta$a} is a fake {@code ServerWorld} used to resolve
 * block properties. Its static initializer builds a vanilla registry manager on
 * Minecraft's own executor, and {@code method_30349()} (getRegistryManager) does
 * {@code future.join()}. When a {@code #mine} command runs on the render thread,
 * that future can never run - the render thread is the one that would have to
 * execute it - so the game freezes.</p>
 *
 * <p>Answering with the live client world's registry manager is both cheaper and
 * semantically what Baritone wants; no join is needed.</p>
 */
@Mixin(targets = "baritone.api.utils.BlockOptionalMeta$a", remap = false)
public class BaritoneBlockOptionalMetaMixin {
    @Inject(method = "method_30349", at = @At("HEAD"), cancellable = true, require = 0)
    private void xiaod$registryManager(CallbackInfoReturnable<DynamicRegistryManager> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) {
            cir.setReturnValue(mc.world.getRegistryManager());
            DeepseekCompanionClient.LOGGER.debug("[XiaoD] baritone BOM registry join bypassed");
        }
    }
}
