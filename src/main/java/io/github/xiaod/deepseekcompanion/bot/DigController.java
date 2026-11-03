package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 往下挖：Baritone 的 mine 只会"走过去挖"，深层矿它找不到路。
 * 这里自己挖一条竖直井道，一块一块往下，遇到水/岩浆/基岩就停。
 */
public final class DigController {
    private static final int MAX_BLOCKS = 96;

    private static BlockPos target;
    private static int remaining;
    private static boolean started;
    private static long lastProgressAt;

    private DigController() {
    }

    public static boolean isBusy() {
        return target != null;
    }

    public static int remaining() {
        return remaining;
    }

    public static boolean start(MinecraftClient mc, int blocks) {
        cancel(mc);
        if (mc.player == null || mc.world == null) {
            return false;
        }
        remaining = Math.max(1, Math.min(blocks, MAX_BLOCKS));
        started = false;
        target = mc.player.getBlockPos().down();
        lastProgressAt = System.currentTimeMillis();
        DeepseekCompanionClient.LOGGER.info("[XiaoD] dig: 往下挖 {} 格，从 {} 开始", remaining, target);
        return true;
    }

    public static void cancel(MinecraftClient mc) {
        if (target != null && mc.interactionManager != null) {
            mc.interactionManager.cancelBlockBreaking();
        }
        target = null;
        started = false;
    }

    public static void tick(MinecraftClient mc) {
        if (target == null || mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }
        if (remaining <= 0) {
            cancel(mc);
            return;
        }
        BlockState below = mc.world.getBlockState(target);
        if (below.isAir()) {
            // 已经空了：直接往下一格
            step(mc);
            return;
        }
        if (below.isOf(Blocks.BEDROCK) || !below.getFluidState().isEmpty()) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] dig: 下面是 {}，停", below.getBlock());
            cancel(mc);
            return;
        }
        if (System.currentTimeMillis() - lastProgressAt > 20_000L) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] dig: 挖不动，停");
            cancel(mc);
            return;
        }

        Vec3d center = Vec3d.ofCenter(target);
        mc.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, center);
        Vec3d eye = mc.player.getEyePos();
        Direction face = Direction.getFacing(center.x - eye.x, center.y - eye.y, center.z - eye.z);
        if (!started) {
            mc.interactionManager.attackBlock(target, face);
            started = true;
        }
        BlockState before = mc.world.getBlockState(target);
        mc.interactionManager.updateBlockBreakingProgress(target, face);
        if (!mc.world.getBlockState(target).equals(before)) {
            lastProgressAt = System.currentTimeMillis();
        }
        if (mc.world.getBlockState(target).isAir()) {
            step(mc);
        }
    }

    private static void step(MinecraftClient mc) {
        remaining--;
        started = false;
        lastProgressAt = System.currentTimeMillis();
        target = mc.player.getBlockPos().down();
        if (remaining <= 0) {
            cancel(mc);
        }
    }
}
