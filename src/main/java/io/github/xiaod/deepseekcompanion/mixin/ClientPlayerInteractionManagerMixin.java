package io.github.xiaod.deepseekcompanion.mixin;

import io.github.xiaod.deepseekcompanion.bot.HomeZone;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 保护区第一道闸：客户端交互管理器。破坏开始 / 进度 / 直接破坏全部挡掉，
 * 这样画面也不会出现「方块在裂」的错觉。
 *
 * <p>第三道（最终）保护是 {@code ClientCommonNetworkHandlerMixin} 的发包拦截，
 * 两者任一命中都拦得住。</p>
 */
@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {
    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void xiaod$attackBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (HomeZone.isProtected(pos)) {
            HomeZone.onBlockedBreak(pos);
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true, require = 0)
    private void xiaod$updateBlockBreakingProgress(BlockPos pos, Direction direction,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (HomeZone.isProtected(pos)) {
            HomeZone.onBlockedBreak(pos);
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @Inject(method = "breakBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void xiaod$breakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (HomeZone.isProtected(pos)) {
            HomeZone.onBlockedBreak(pos);
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    /** 右键成功 = 允许的交互；点到床就把家设在那儿。 */
    @Inject(method = "interactBlock", at = @At("RETURN"), require = 0)
    private void xiaod$interactBlock(net.minecraft.client.network.ClientPlayerEntity player,
                                     net.minecraft.util.Hand hand,
                                     net.minecraft.util.hit.BlockHitResult hitResult,
                                     CallbackInfoReturnable<net.minecraft.util.ActionResult> cir) {
        net.minecraft.util.ActionResult result = cir.getReturnValue();
        if (result != null && result.isAccepted()) {
            HomeZone.onUsedBlock(net.minecraft.client.MinecraftClient.getInstance(), hitResult.getBlockPos());
        }
    }
}
