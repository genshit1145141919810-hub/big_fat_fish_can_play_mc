package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 用完的东西要收回来：工作台、熔炉放下去用过之后挖回到背包。
 *
 * <p>不回收的后果很实在：下一段行程又找不到台子、又得重新做一张，
 * 木头和石头就这么一次次浪费掉。</p>
 */
public final class PlacedBlocks {
    private static final long BLOCK_TIMEOUT_MS = 15_000L;

    /** 这一趟放下去、还没收回来的方块。 */
    private static final List<BlockPos> PLACED = new ArrayList<>();
    private static final Deque<BlockPos> QUEUE = new ArrayDeque<>();
    private static BlockPos current;
    private static boolean started;
    private static long lastProgressAt;

    private PlacedBlocks() {
    }

    /** 刚放下一块：记下来，之后要收。 */
    public static void note() {
        BlockPos pos = PlacementController.lastPlaced();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (pos == null || mc.player == null) {
            return;
        }
        if (mc.player.getBlockPos().getSquaredDistance(pos) > 25.0) {
            return; // 不是刚放的那块，别误记
        }
        if (!PLACED.contains(pos)) {
            PLACED.add(pos);
        }
    }

    public static boolean isBusy() {
        return current != null || !QUEUE.isEmpty();
    }

    /** 开始回收：把记下来的位置都排进队列。 */
    public static void recoverAll() {
        for (BlockPos pos : PLACED) {
            if (!QUEUE.contains(pos)) {
                QUEUE.add(pos);
            }
        }
        PLACED.clear();
    }

    /** 还剩几块没收回。 */
    public static int pending() {
        return QUEUE.size() + (current == null ? 0 : 1);
    }

    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }
        // 别人在干正事的时候别抢
        // 获取任务可以还在跑——只要此刻没人在挖/合成/打架，就能顺手把它收回来
        if (PlanRunner.isPlanning() || MiningController.isBusy() || DigController.isBusy()
                || ContainerController.isBusy() || CombatController.isHunting()) {
            return;
        }
        if (current == null) {
            current = QUEUE.poll();
            if (current == null) {
                return;
            }
            started = false;
            lastProgressAt = System.currentTimeMillis();
        }
        BlockState state = mc.world.getBlockState(current);
        if (state.isAir()) {
            current = null; // 已经没了（可能是自己塌了/别人挖了）
            return;
        }
        if (state.isOf(Blocks.BEDROCK) || System.currentTimeMillis() - lastProgressAt > BLOCK_TIMEOUT_MS) {
            current = null; // 挖不动就算了，别卡住
            return;
        }
        double distance = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(current));
        if (distance > 4.2) {
            if (BaritoneBridge.expectingMovement()) {
                return; // 有人正在走路（比如去挖矿），别打断它
            }
            if (BaritoneBridge.available()) {
                BaritoneBridge.gotoPos(current.getX(), current.getY() + 1, current.getZ());
            }
            lastProgressAt = System.currentTimeMillis();
            return;
        }
        Vec3d center = Vec3d.ofCenter(current);
        mc.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, center);
        Vec3d eye = mc.player.getEyePos();
        Direction face = Direction.getFacing(center.x - eye.x, center.y - eye.y, center.z - eye.z);
        if (!started) {
            MiningController.equipPickaxe(mc);
            mc.interactionManager.attackBlock(current, face);
            started = true;
        }
        mc.interactionManager.updateBlockBreakingProgress(current, face);
        if (!mc.world.getBlockState(current).equals(state)) {
            lastProgressAt = System.currentTimeMillis();
        }
        if (mc.world.getBlockState(current).isAir()) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] 收回了 {} 的方块", current);
            current = null;
        }
    }
}
