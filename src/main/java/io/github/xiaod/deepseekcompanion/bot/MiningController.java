package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Mines blocks with the client's own interaction manager - exactly the packets
 * a human player produces. Baritone is only asked to walk near the target.
 *
 * <p>This exists because Baritone's own {@code #mine} command builds a
 * {@code BlockOptionalMeta} on the render thread and deadlocks in some worlds
 * (it joins a future that only the render thread could complete). Walking
 * commands do not touch that code, so they stay with Baritone.</p>
 */
public final class MiningController {
    private static final int SCAN_RADIUS = 24;
    private static final int MAX_SCAN_STEPS = 6000;
    private static final double REACH = 4.4;
    private static final long GOTO_COOLDOWN_MS = 3000L;
    private static final long GIVE_UP_MS = 20_000L;

    private static BlockPos target;
    private static String blockId;
    private static boolean chop;
    private static int remaining;
    private static int broken;
    private static long lastGoto;
    private static long lastProgressAt;
    private static boolean stopSent;
    private static boolean started;

    private MiningController() {
    }

    public static boolean isBusy() {
        return target != null;
    }

    /** 手上得拿着镐：拿错东西挖，方块会碎但什么都不掉。 */
    public static void equipPickaxe(MinecraftClient mc) {
        AutoTool.equipPickaxe(mc);
    }

    /** 挖这个方块就用最合适的工具：镐挖石头、斧砍木头、锹铲土。 */
    private static void equipForBlock(MinecraftClient mc, BlockPos pos) {
        if (mc.world != null) {
            AutoTool.equipFor(mc, mc.world.getBlockState(pos));
        }
    }

    /** 最近一次挖矿任务真正挖掉了几块：用来区分"没挖到"和"挖了没捡到"。 */
    public static int minedCount() {
        return broken;
    }

    /** Starts a bounded mining job: count blocks of {@code id}, or any log when chop is true. */
    public static boolean start(MinecraftClient mc, String id, boolean chopMode, int count) {
        cancel(mc);
        equipPickaxe(mc);
        blockId = id;
        chop = chopMode;
        remaining = Math.max(1, count);
        broken = 0;
        lastProgressAt = System.currentTimeMillis();
        if (!acquire(mc)) {
            return false;
        }
        equipForBlock(mc, target);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] mining started: {} at {}", id == null ? "any log" : id, target);
        return true;
    }

    public static void cancel(MinecraftClient mc) {
        if (target != null) {
            BaritoneBridge.stop();
            if (mc.interactionManager != null) {
                mc.interactionManager.cancelBlockBreaking();
            }
        }
        target = null;
        stopSent = false;
        started = false;
    }

    public static void tick(MinecraftClient mc) {
        if (target == null || mc.player == null || mc.world == null) {
            return;
        }
        if (CombatController.isRetreating() || GiveController.isBusy() || FleeController.isFleeing()) {
            return; // someone else owns the movement right now
        }
        if (!matches(mc, target)) {
            target = null;
            broken++;
            DeepseekCompanionClient.LOGGER.info("[XiaoD] mined {} of {}", broken, remaining);
            if (broken >= remaining) {
                cancel(mc);
                return;
            }
            if (!acquire(mc)) {
                cancel(mc);
            }
            return;
        }
        if (System.currentTimeMillis() - lastProgressAt > GIVE_UP_MS) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] mining gave up (no progress)");
            cancel(mc);
            return;
        }

        Vec3d center = Vec3d.ofCenter(target);
        double distance = mc.player.getEyePos().distanceTo(center);
        if (distance > REACH) {
            long now = System.currentTimeMillis();
            if (now - lastGoto > GOTO_COOLDOWN_MS && BaritoneBridge.available()) {
                BlockPos approach = approach(mc, target);
                BaritoneBridge.gotoPos(approach.getX(), approach.getY(), approach.getZ());
                lastGoto = now;
                stopSent = false;
            }
            return;
        }

        if (!stopSent) {
            BaritoneBridge.stop();
            stopSent = true;
        }
        if (mc.interactionManager == null) {
            cancel(mc);
            return;
        }
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
    }

    /** A walkable spot next to the block, so {@code #goto} does not ask for the solid block itself. */
    private static BlockPos approach(MinecraftClient mc, BlockPos pos) {
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos candidate = pos.offset(direction);
            if (standable(mc, candidate)) {
                return candidate;
            }
        }
        return pos;
    }

    private static boolean standable(MinecraftClient mc, BlockPos pos) {
        return mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty()
                && mc.world.getBlockState(pos.up()).getCollisionShape(mc.world, pos.up()).isEmpty()
                && !mc.world.getBlockState(pos.down()).getCollisionShape(mc.world, pos.down()).isEmpty();
    }

    /** 最近的这种方块有多远（没找到返回 -1）：挑配料时用它比远近。 */
    public static double nearestDistance(MinecraftClient mc, String id, int radius) {
        if (mc.player == null || mc.world == null || id == null) {
            return -1.0;
        }
        int r = Math.max(1, Math.min(radius, SCAN_RADIUS));
        BlockPos origin = mc.player.getBlockPos();
        int steps = 0;
        for (BlockPos pos : BlockPos.iterateOutwards(origin, r, r, r)) {
            if (++steps > MAX_SCAN_STEPS) {
                break;
            }
            BlockState state = mc.world.getBlockState(pos);
            if (!state.isAir() && Registries.BLOCK.getId(state.getBlock()).getPath().equals(id)) {
                return Math.sqrt(pos.getSquaredDistance(origin));
            }
        }
        return -1.0;
    }

    /** 附近有没有这种方块：规划阶段用它挑"真有得挖"的材料，而不是挑个挖不到的。 */
    public static boolean anyNearby(MinecraftClient mc, String id, int radius) {
        if (mc.player == null || mc.world == null || id == null) {
            return false;
        }
        int r = Math.max(1, Math.min(radius, SCAN_RADIUS));
        BlockPos origin = mc.player.getBlockPos();
        int steps = 0;
        for (BlockPos pos : BlockPos.iterateOutwards(origin, r, r, r)) {
            if (++steps > MAX_SCAN_STEPS) {
                break;
            }
            BlockState state = mc.world.getBlockState(pos);
            if (!state.isAir() && Registries.BLOCK.getId(state.getBlock()).getPath().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean acquire(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            return false;
        }
        BlockPos origin = mc.player.getBlockPos();
        int steps = 0;
        for (BlockPos pos : BlockPos.iterateOutwards(origin, SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)) {
            if (++steps > MAX_SCAN_STEPS) {
                break;
            }
            if (matches(mc, pos)) {
                target = pos.toImmutable();
                started = false;
                stopSent = false;
                lastProgressAt = System.currentTimeMillis();
                return true;
            }
        }
        return false;
    }

    /** 这个方块是不是真的在视线里（用碰撞体射线打过去，打到它才算看见）。 */
    public static boolean visible(MinecraftClient mc, BlockPos pos) {
        if (mc.player == null || mc.world == null) {
            return false;
        }
        var hit = mc.world.raycast(new net.minecraft.world.RaycastContext(
                mc.player.getEyePos(),
                Vec3d.ofCenter(pos),
                net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                net.minecraft.world.RaycastContext.FluidHandling.NONE,
                mc.player));
        return hit.getBlockPos().equals(pos) || hit.getBlockPos().equals(pos.up());
    }

    private static boolean matches(MinecraftClient mc, BlockPos pos) {
        if (HomeZone.isProtected(pos)) {
            return false; // 家的保护区只准交互
        }
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        if (io.github.xiaod.deepseekcompanion.config.CompanionConfig.get().fairPlay
                && !visible(mc, pos)) {
            return false; // 公平模式：看不见的不算目标
        }
        if (chop) {
            return state.isIn(BlockTags.LOGS);
        }
        return RegistrarIdOf(state).equals(blockId);
    }

    private static String RegistrarIdOf(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).getPath();
    }
}
