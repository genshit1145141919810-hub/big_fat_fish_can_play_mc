package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * 家 = 小D 自己的床（出生点）。
 *
 * <p>家周围是一个正方形保护区：边心距 {@code protectApothem}（64 就是 128x128），
 * 上下各 {@code protectHeight} 格。区内只允许交互（右键、开箱子、用站点、睡觉……），
 * 不允许破坏方块。真正拦在发往服务端的 {@code PlayerActionC2S} 包上，
 * 所以手动挖、Baritone 挖、本地挖掘都跑不掉。</p>
 *
 * <p>保护只约束小D这个客户端（{@link BotBrain#isActiveAccount}），
 * 主人自己的客户端是休眠的，所以主人想拆家还是能拆。</p>
 */
public final class HomeZone {
    private static final int MAX_SCAN_STEPS = 8000;
    private static final long NOTICE_THROTTLE_MS = 4000L;
    private static final long BLOCKED_REMEMBER_MS = 45_000L;

    private static long lastNoticeAt;
    private static long blockedUntil;
    private static boolean wasSleeping;

    private HomeZone() {
    }

    // ------------------------------------------------------------ config view

    public static boolean isSet() {
        return CompanionConfig.get().homeSet;
    }

    public static boolean protectionEnabled() {
        CompanionConfig cfg = CompanionConfig.get();
        return cfg.homeSet && cfg.enableHomeProtection;
    }

    public static BlockPos center() {
        CompanionConfig cfg = CompanionConfig.get();
        return new BlockPos(cfg.homeX, cfg.homeY, cfg.homeZ);
    }

    public static String dimensionOf(MinecraftClient mc) {
        return mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString();
    }

    /**
     * 正方形（边心距）柱体判定，四个条件全中才算保护：
     * 同一个维度、|dx| <= apothem、|dz| <= apothem、|dy| <= protectHeight。
     */
    public static boolean isProtected(BlockPos pos) {
        if (pos == null || !protectionEnabled()) {
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            return false;
        }
        if (!BotBrain.isActiveAccount(mc)) {
            return false; // 只约束小D，别的账号照常能挖
        }
        CompanionConfig cfg = CompanionConfig.get();
        if (!dimensionOf(mc).equals(cfg.homeDimension)) {
            return false;
        }
        if (Math.abs(pos.getX() - cfg.homeX) > cfg.protectApothem) {
            return false;
        }
        if (Math.abs(pos.getZ() - cfg.homeZ) > cfg.protectApothem) {
            return false;
        }
        return Math.abs(pos.getY() - cfg.homeY) <= cfg.protectHeight;
    }

    public static boolean protectedHere() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && isProtected(mc.player.getBlockPos());
    }

    // ------------------------------------------------------------------- set

    /** 记下家。位置没变就只保存不刷屏。 */
    public static void setHome(MinecraftClient mc, BlockPos pos) {
        if (pos == null || mc.player == null || mc.world == null) {
            return;
        }
        CompanionConfig cfg = CompanionConfig.get();
        String dim = dimensionOf(mc);
        boolean same = cfg.homeSet && cfg.homeX == pos.getX() && cfg.homeY == pos.getY()
                && cfg.homeZ == pos.getZ() && dim.equals(cfg.homeDimension);
        cfg.homeSet = true;
        cfg.homeDimension = dim;
        cfg.homeX = pos.getX();
        cfg.homeY = pos.getY();
        cfg.homeZ = pos.getZ();
        CompanionConfig.save();
        if (!same) {
            blockedUntil = 0L;
            DeepseekCompanionClient.LOGGER.info("[XiaoD] home set to {} in {}", pos, dim);
            local(mc, "§a家已设为 " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                    + "（" + shortDimension(dim) + "）");
            local(mc, "§7保护区 " + (cfg.protectApothem * 2) + "x" + (cfg.protectApothem * 2)
                    + "（边心距 " + cfg.protectApothem + "）：只准交互，不准挖方块");
        }
    }

    public static void clear(MinecraftClient mc) {
        CompanionConfig cfg = CompanionConfig.get();
        cfg.homeSet = false;
        cfg.homeDimension = "";
        CompanionConfig.save();
        blockedUntil = 0L;
        local(mc, "§7家已清除，保护区一起关了");
    }

    /** 右键成功交互一个方块后调用：是床就当家。 */
    public static void onUsedBlock(MinecraftClient mc, BlockPos pos) {
        if (pos == null || mc.world == null) {
            return;
        }
        if (mc.world.getBlockState(pos).getBlock() instanceof BedBlock) {
            setHome(mc, pos);
        }
    }

    /** 睡进床里也算设家（右击可能被别的原因挡掉，睡觉是更硬的信号）。 */
    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            wasSleeping = false;
            return;
        }
        boolean sleeping = mc.player.isSleeping();
        if (sleeping && !wasSleeping) {
            BlockPos bed = nearestBed(mc, mc.player.getBlockPos(), 2);
            if (bed != null) {
                setHome(mc, bed);
            }
        }
        wasSleeping = sleeping;
    }

    public static BlockPos nearestBed(MinecraftClient mc, BlockPos origin, int radius) {
        if (mc.world == null || origin == null) {
            return null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -radius, -radius),
                origin.add(radius, radius, radius))) {
            if (!(mc.world.getBlockState(pos).getBlock() instanceof BedBlock)) {
                continue;
            }
            double distance = pos.getSquaredDistance(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    // --------------------------------------------------------------- blocking

    /** 破坏动作被拦下来了：记住一会儿（喂给模型），并停掉卡住的挖掘任务。 */
    public static void onBlockedBreak(BlockPos pos) {
        long now = System.currentTimeMillis();
        blockedUntil = now + BLOCKED_REMEMBER_MS;
        if (now - lastNoticeAt < NOTICE_THROTTLE_MS) {
            return;
        }
        lastNoticeAt = now;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] refused to break {} (home protection zone)", pos);
        MinecraftClient mc = MinecraftClient.getInstance();
        // 推迟一 tick 再收尾，避免在交互管理器/发包路径里递归
        mc.execute(() -> {
            if (MiningController.isBusy()) {
                MiningController.cancel(mc);
            }
            if (TaskTracker.isBusy()) {
                BaritoneBridge.stop();
                TaskTracker.clear();
            }
        });
    }

    /** 最近有没有在保护区里试图挖东西（给 snapshot / 提示用）。 */
    public static boolean recentlyBlocked() {
        return System.currentTimeMillis() < blockedUntil;
    }

    // -------------------------------------------------------------- pre-checks

    /**
     * 半径内最近一块「在保护区里」且符合条件的目标。有的话说明 Baritone 的
     * {@code #mine} 会去啃保护区，应该改走本地挖掘（它会跳过保护区）。
     */
    public static BlockPos protectedTargetNear(MinecraftClient mc, String blockId, boolean logs, int radius) {
        if (!protectionEnabled() || mc.player == null || mc.world == null) {
            return null;
        }
        BlockPos origin = mc.player.getBlockPos();
        int steps = 0;
        for (BlockPos pos : BlockPos.iterateOutwards(origin, radius, 16, radius)) {
            if (++steps > MAX_SCAN_STEPS) {
                break;
            }
            if (!isProtected(pos)) {
                continue;
            }
            BlockState state = mc.world.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            boolean match = logs
                    ? state.isIn(BlockTags.LOGS)
                    : Registries.BLOCK.getId(state.getBlock()).getPath().equals(blockId);
            if (match) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    // -------------------------------------------------------------- text bits

    public static String describe() {
        CompanionConfig cfg = CompanionConfig.get();
        if (!cfg.homeSet) {
            return "没设家（在床上睡一次会自动设）";
        }
        String head = "家 " + cfg.homeX + " " + cfg.homeY + " " + cfg.homeZ
                + "（" + shortDimension(cfg.homeDimension) + "）";
        if (!cfg.enableHomeProtection) {
            return head + "，保护区已关";
        }
        return head + "，保护区 " + (cfg.protectApothem * 2) + "x" + (cfg.protectApothem * 2)
                + "、上下 " + (cfg.protectHeight * 2) + "，只准交互不准挖";
    }

    public static String shortDimension(String dim) {
        if (dim == null || dim.isBlank()) {
            return "?";
        }
        int colon = dim.indexOf(':');
        return colon < 0 ? dim : dim.substring(colon + 1);
    }

    private static void local(MinecraftClient mc, String message) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[小D] " + message), false);
        }
    }
}
