package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * 走传送门（地狱门 / 末地门）。
 *
 * <p>关键点：门不是"右键"进的，人是<b>站进方块里</b>被传送的（下界门 80 tick，末地门更快）。
 * 所以这里分三段：{@code #goto} 到门附近 → 关掉 Baritone 后按前进键走进去 → 站着等维度变化。
 * 成功判定用维度字符串变化，不用猜时机。</p>
 */
public final class PortalController {
    private static final int SCAN_RADIUS = 24;
    private static final double ENTER_DISTANCE = 4.5;
    private static final long GOTO_COOLDOWN_MS = 3000L;
    private static final long TIMEOUT_MS = 30_000L;

    private static BlockPos target;
    private static Block kind;
    private static String fromDimension = "";
    private static long startedAt;
    private static long lastGoto;
    private static boolean sentGoto;
    private static boolean pushing;
    private static boolean arrived;
    private static boolean failed;          // 上一次走门失败，等 PlanRunner 消费

    private PortalController() {
    }

    public static boolean isBusy() {
        return target != null;
    }

    /** 取走"上次走门失败"的信号（一次性）。 */
    public static boolean consumeFailure() {
        boolean value = failed;
        failed = false;
        return value;
    }

    /**
     * arg 可以留空（最近的门）、"nether"、"end"，或者直接 "nether_portal" / "end_portal"。
     * 返回是否真的找到门并开始走。
     */
    public static boolean start(MinecraftClient mc, String arg) {
        cancel(mc);
        if (mc.player == null || mc.world == null) {
            return false;
        }
        String wanted = arg == null ? "" : arg.strip().toLowerCase(Locale.ROOT);
        Block wantedBlock = null;
        if (wanted.contains("nether") || wanted.contains("下界")) {
            wantedBlock = Blocks.NETHER_PORTAL;
        } else if (wanted.contains("end") || wanted.contains("末地")) {
            wantedBlock = Blocks.END_PORTAL;
        }
        BlockPos found = find(mc, wantedBlock);
        if (found == null) {
            failed = true;
            DeepseekCompanionClient.LOGGER.info("[XiaoD] portal: none found within {} blocks", SCAN_RADIUS);
            return false;
        }
        target = found;
        kind = mc.world.getBlockState(found).getBlock();
        fromDimension = DimensionWatch.current(mc);
        startedAt = System.currentTimeMillis();
        lastGoto = 0L;
        sentGoto = false;
        pushing = false;
        arrived = false;
        failed = false;
        MiningController.cancel(mc);
        TaskTracker.clear();
        DeepseekCompanionClient.LOGGER.info("[XiaoD] portal -> {} at {} (from {})", kindName(kind), target, fromDimension);
        return true;
    }

    public static void tick(MinecraftClient mc) {
        if (target == null) {
            return;
        }
        if (mc.player == null || mc.world == null) {
            releaseKeys(mc);
            target = null;
            return;
        }
        // 传送成功 = 维度变了
        String now = DimensionWatch.current(mc);
        if (!now.isEmpty() && !now.equals(fromDimension)) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] portal done: {} -> {}", fromDimension, now);
            releaseKeys(mc);
            target = null;
            pushing = false;
            arrived = false;
            sentGoto = false;
            return;
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > TIMEOUT_MS) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] portal gave up after {}ms (at {})", elapsed, target);
            failed = true;
            cancel(mc);
            return;
        }
        if (kind == null) {
            cancel(mc);
            return;
        }
        BlockPos feet = mc.player.getBlockPos();
        boolean inside = mc.world.getBlockState(feet).getBlock() == kind
                || mc.world.getBlockState(feet.up()).getBlock() == kind;
        if (inside) {
            if (!arrived) {
                arrived = true;
                if (sentGoto || pushing) {
                    BaritoneBridge.stop();
                }
                releaseKeys(mc);
                DeepseekCompanionClient.LOGGER.info("[XiaoD] standing inside {} - waiting for teleport", kindName(kind));
            }
            return; // 站着等
        }
        double distance = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(target));
        if (distance <= ENTER_DISTANCE) {
            if (sentGoto) {
                BaritoneBridge.stop();
                sentGoto = false;
                lastGoto = System.currentTimeMillis();
            }
            mc.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(target));
            mc.options.forwardKey.setPressed(true);
            pushing = true;
            return;
        }
        if (BaritoneBridge.available()
                && System.currentTimeMillis() - lastGoto > GOTO_COOLDOWN_MS) {
            BaritoneBridge.gotoPos(target.getX(), target.getY(), target.getZ());
            sentGoto = true;
            lastGoto = System.currentTimeMillis();
        }
    }

    public static void cancel(MinecraftClient mc) {
        releaseKeys(mc);
        if (sentGoto || pushing) {
            BaritoneBridge.stop();
        }
        target = null;
        kind = null;
        sentGoto = false;
        pushing = false;
        arrived = false;
    }

    private static void releaseKeys(MinecraftClient mc) {
        if (mc.options != null) {
            mc.options.forwardKey.setPressed(false);
        }
    }

    private static BlockPos find(MinecraftClient mc, Block wanted) {
        BlockPos origin = mc.player.getBlockPos();
        for (BlockPos pos : BlockPos.iterateOutwards(origin, SCAN_RADIUS, 16, SCAN_RADIUS)) {
            Block block = mc.world.getBlockState(pos).getBlock();
            if (wanted == null) {
                if (block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL) {
                    return pos.toImmutable();
                }
            } else if (block == wanted) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private static String kindName(Block block) {
        if (block == Blocks.NETHER_PORTAL) {
            return "下界传送门";
        }
        if (block == Blocks.END_PORTAL) {
            return "末地传送门";
        }
        return "传送门";
    }
}
