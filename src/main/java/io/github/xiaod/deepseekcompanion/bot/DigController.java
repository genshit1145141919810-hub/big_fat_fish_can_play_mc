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
 * 往正下方挖：挖开脚下一块，人自然落一层，再挖下一块。
 *
 * <p>垂直挖掘是最省事的往下方式，重力帮我们"走"完这一格。
 * 只做一件保险：下面是液体、基岩，或者下面两格都是空气（洞穴）时停手。</p>
 */
public final class DigController {
    private static final long STEP_TIMEOUT_MS = 20_000L;

    private static int remaining;
    private static boolean started;
    private static long lastProgressAt;
    private static int levelY;
    private static int lastLoggedLevel;
    /** 遇到大落差/液体时挪窝的次数，避免来回横跳。 */
    private static int relocations;

    private DigController() {
    }

    public static boolean isBusy() {
        return remaining > 0;
    }

    public static int remaining() {
        return remaining;
    }

    public static boolean start(MinecraftClient mc, int levels) {
        cancel(mc);
        if (mc.player == null || mc.world == null) {
            return false;
        }
        MiningController.equipPickaxe(mc);
        remaining = Math.max(1, Math.min(levels, 128));
        started = false;
        levelY = mc.player.getBlockY();
        lastLoggedLevel = levelY;
        lastProgressAt = System.currentTimeMillis();
        relocations = 0;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] dig: 往下挖 {} 格（从 y={}）", remaining, levelY);
        return true;
    }

    public static void cancel(MinecraftClient mc) {
        if (mc != null && mc.interactionManager != null) {
            mc.interactionManager.cancelBlockBreaking();
        }
        remaining = 0;
        started = false;
    }

    public static void tick(MinecraftClient mc) {
        if (remaining <= 0 || mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }
        if (!mc.player.isOnGround()) {
            return; // 还在下落，等落地
        }
        int y = mc.player.getBlockY();
        if (y < levelY) {
            remaining -= (levelY - y);
            levelY = y;
            started = false;
            lastProgressAt = System.currentTimeMillis();
            if (y != lastLoggedLevel) {
                lastLoggedLevel = y;
            relocations = 0;
                DeepseekCompanionClient.LOGGER.info("[XiaoD] dig: 到 y={}，还差 {} 格", y, Math.max(0, remaining));
            }
            if (remaining <= 0) {
                cancel(mc);
                return;
            }
        }
        if (System.currentTimeMillis() - lastProgressAt > STEP_TIMEOUT_MS) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] dig: 挖不动（y={}），收工", y);
            cancel(mc);
            return;
        }

        BlockPos below = mc.player.getBlockPos().down();
        BlockState state = mc.world.getBlockState(below);
        if (state.isAir()) {
            return; // 已经在往下掉
        }
        // 往下连着的空气格数 = 挖穿后会掉多少格；掉 3 格以内无所谓，再多就换个地方
        int drop = 0;
        while (drop < 6 && mc.world.getBlockState(below.down(drop + 1)).isAir()) {
            drop++;
        }
        if (state.isOf(Blocks.BEDROCK) || !state.getFluidState().isEmpty() || drop > 3) {
            // 下面挖不动或者是个深坑：别硬挖，往旁边挪几格重新找竖井
            if (relocations < 5 && BaritoneBridge.available() && relocate(mc)) {
                relocations++;
                DeepseekCompanionClient.LOGGER.info("[XiaoD] dig: 这里下面是 {} / 落差 {} 格，换个地方挖",
                        state.getBlock(), drop);
                return;
            }
            DeepseekCompanionClient.LOGGER.info("[XiaoD] dig: 下面不适合继续（{} / 落差 {} 格），停",
                    state.getBlock(), drop);
            cancel(mc);
            return;
        }
        mineBlock(mc, below);
    }

    /** 找一个下面够结实、能继续往下挖的落脚点，走过去。 */
    private static boolean relocate(MinecraftClient mc) {
        BlockPos origin = mc.player.getBlockPos();
        for (int r = 3; r <= 10; r++) {
            for (BlockPos pos : BlockPos.iterateOutwards(origin, r, 2, r)) {
                if (pos.equals(origin) || !standable(mc, pos)) {
                    continue;
                }
                BlockPos below = pos.down();
                BlockState state = mc.world.getBlockState(below);
                if (state.isOf(Blocks.BEDROCK) || !state.getFluidState().isEmpty() || state.isAir()) {
                    continue;
                }
                if (mc.world.getBlockState(below.down()).isAir()) {
                    continue; // 再下面也是空腔，还是别去
                }
                BaritoneBridge.gotoPos(pos.getX(), pos.getY(), pos.getZ());
                DeepseekCompanionClient.LOGGER.info("[XiaoD] dig: 挪到 {} 继续往下", pos);
                return true;
            }
        }
        return false;
    }

    private static boolean standable(MinecraftClient mc, BlockPos pos) {
        return !mc.world.getBlockState(pos.down()).getCollisionShape(mc.world, pos.down()).isEmpty()
                && mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty()
                && mc.world.getBlockState(pos.up()).getCollisionShape(mc.world, pos.up()).isEmpty();
    }

    private static void mineBlock(MinecraftClient mc, BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        mc.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, center);
        Vec3d eye = mc.player.getEyePos();
        Direction face = Direction.getFacing(center.x - eye.x, center.y - eye.y, center.z - eye.z);
        if (!started) {
            mc.interactionManager.attackBlock(pos, face);
            started = true;
        }
        BlockState before = mc.world.getBlockState(pos);
        mc.interactionManager.updateBlockBreakingProgress(pos, face);
        if (!mc.world.getBlockState(pos).equals(before)) {
            lastProgressAt = System.currentTimeMillis();
        }
    }
}
