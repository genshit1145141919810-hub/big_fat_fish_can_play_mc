package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * 放方块（主要是工作台）。合成链的最后一步：2x2 造出台子 → 放到脚边 → 3x3 才能开工。
 *
 * <p>放的位置挑"顶面朝上、上面是空气、不占玩家脚"的最近方块，一次右键完成；
 * 不做复杂选址，放不下就直说，别静默失败。</p>
 */
public final class PlacementController {
    private static final int REACH_HORIZONTAL = 4;
    private static final int REACH_UP = 2;
    private static final int REACH_DOWN = 3;

    /** 换手后等一 tick 再放：SWAP 的结果要等服务端同步回来。 */
    private static String pendingItem;
    private static long pendingUntil;
    /** 放置前先清出来的一块空地。 */
    private static BlockPos clearTarget;
    private static boolean clearStarted;
    private static long clearStartedAt;

    private PlacementController() {
    }

    public static void tick(MinecraftClient mc) {
        if (clearTarget != null) {
            tickClear(mc);
        }
        if (pendingItem == null) {
            return;
        }
        if (mc.player == null || System.currentTimeMillis() > pendingUntil) {
            pendingItem = null;
            return;
        }
        if (hotbarSlot(mc.player, pendingItem) >= 0) {
            String item = pendingItem;
            pendingItem = null;
            place(mc, item);
        }
    }

    /** 同步动作：右键一下就完事，所以 isBusy 恒为 false。 */
    public static boolean isBusy() {
        return false;
    }

    private static void tickClear(MinecraftClient mc) {
        if (clearTarget == null || mc.player == null || mc.world == null || mc.interactionManager == null) {
            clearTarget = null;
            return;
        }
        if (mc.world.getBlockState(clearTarget).isAir()) {
            clearTarget = null;
            return;
        }
        if (System.currentTimeMillis() - clearStartedAt > 15_000L) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] place: {} 清不动，算了", clearTarget);
            clearTarget = null;
            return;
        }
        Vec3d center = Vec3d.ofCenter(clearTarget);
        mc.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, center);
        Vec3d eye = mc.player.getEyePos();
        Direction face = Direction.getFacing(center.x - eye.x, center.y - eye.y, center.z - eye.z);
        if (!clearStarted) {
            MiningController.equipPickaxe(mc);
            mc.interactionManager.attackBlock(clearTarget, face);
            clearStarted = true;
        }
        mc.interactionManager.updateBlockBreakingProgress(clearTarget, face);
    }

    /** 脚下没有合适的位置：往旁边找个能站的地方，走过去再放。 */
    public static boolean reposition(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || !BaritoneBridge.available()) {
            return false;
        }
        BlockPos origin = mc.player.getBlockPos();
        for (int r = 2; r <= 8; r++) {
            for (BlockPos pos : BlockPos.iterateOutwards(origin, r, 6, r)) {
                if (!standable(mc, pos)) {
                    continue;
                }
                if (!mc.world.getBlockState(pos).isAir() || !mc.world.getBlockState(pos.up()).isAir()) {
                    continue;
                }
                DeepseekCompanionClient.LOGGER.info("[XiaoD] place: 换个地方放，去 {}", pos);
                BaritoneBridge.gotoPos(pos.getX(), pos.getY(), pos.getZ());
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

    public static boolean place(MinecraftClient mc, String itemId) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) {
            return false;
        }
        String id = itemId == null ? "" : itemId.strip().toLowerCase(Locale.ROOT);
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        if (id.isBlank()) {
            return false;
        }
        int slot = hotbarSlot(p, id);
        if (slot < 0) {
            // 不在快捷栏就从背包里换过来（SlotActionType.SWAP 等于按数字键换位）
            slot = pullToHotbar(mc, p, id);
            if (slot < 0) {
                if ("crafting_table".equals(id) && ContainerController.tableNearby(mc)) {
                    DeepseekCompanionClient.LOGGER.info("[XiaoD] place {}: 附近已经有台子了，不用再放", id);
                    return true;
                }
                if ("furnace".equals(id) && ContainerController.furnaceNearby(mc)) {
                    DeepseekCompanionClient.LOGGER.info("[XiaoD] place {}: 附近已经有熔炉了，不用再放", id);
                    return true;
                }
                DeepseekCompanionClient.LOGGER.info("[XiaoD] place {}: not in hotbar and not in inventory", id);
                BotBrain.say("身上没有 " + id + "（工作台/熔炉这些得先合出来）");
                return false;
            }
            pendingItem = id;
            pendingUntil = System.currentTimeMillis() + 5000L;
            DeepseekCompanionClient.LOGGER.info("[XiaoD] place {}: swapped into hotbar {}, retrying next tick", id, slot);
            return true;
        }
        if (slot < 0) {
            BotBrain.say("身上没有 " + id + "（工作台/熔炉这些得先合出来）");
            return false;
        }
        p.getInventory().setSelectedSlot(slot);

        BlockPos origin = p.getBlockPos();
        // 放之前先给自己清一块地：站在树/墙里的时候永远找不到空位
        BlockPos blocked = null;
        if (!mc.world.getBlockState(origin.up()).isAir()) {
            blocked = origin.up();
        } else if (!mc.world.getBlockState(origin).isAir()) {
            blocked = origin;
        }
        if (blocked != null && !mc.world.getBlockState(blocked).isOf(net.minecraft.block.Blocks.BEDROCK)) {
            clearTarget = blocked;
            clearStarted = false;
            clearStartedAt = System.currentTimeMillis();
            DeepseekCompanionClient.LOGGER.info("[XiaoD] place: 先把 {} 清掉再放", blocked);
            return true;
        }
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        int baseCount = 0;
        int airCount = 0;
        int overlapCount = 0;
        for (int dy = REACH_UP; dy >= -REACH_DOWN; dy--) {
            for (int dx = -REACH_HORIZONTAL; dx <= REACH_HORIZONTAL; dx++) {
                for (int dz = -REACH_HORIZONTAL; dz <= REACH_HORIZONTAL; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState base = mc.world.getBlockState(cursor);
                    if (base.isAir() || !base.isSolidBlock(mc.world, cursor)) {
                        continue;
                    }
                    baseCount++;
                    BlockPos above = cursor.up();
                    if (!mc.world.getBlockState(above).isAir()) {
                        airCount++;
                        continue;
                    }
                    if (above.equals(origin)) {
                        continue; // 不往自己脚下垫
                    }
                    if (p.getBoundingBox().intersects(new Box(above))) {
                        overlapCount++;
                        continue;
                    }
                    BlockPos target = cursor.toImmutable();
                    Vec3d hit = Vec3d.ofCenter(target).add(0.0, 0.5, 0.0);
                    p.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, hit);
                    mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                            new BlockHitResult(hit, Direction.UP, target, false));
                    p.swingHand(Hand.MAIN_HAND);
                    DeepseekCompanionClient.LOGGER.info("[XiaoD] placed {} on {}", id, target);
                    return true;
                }
            }
        }
io.github.xiaod.deepseekcompanion.DeepseekCompanionClient.LOGGER.info("[XiaoD] place scan {}: bases={} notAir={} overlap={} origin={}",
                id, baseCount, airCount, overlapCount, origin);
        BotBrain.say("脚边没有能放 " + id + " 的位置");
        return false;
    }

    /** 在指定格子放一个（选相邻实心方块的朝向面）。给"堵墙"用。 */
    public static boolean placeAt(MinecraftClient mc, BlockPos target) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null || target == null) {
            return false;
        }
        if (!mc.world.getBlockState(target).isAir()) {
            return false;
        }
        BlockState targetState = mc.world.getBlockState(target);
        if (!targetState.isAir() && !targetState.isReplaceable()) {
            return false;
        }
        for (Direction side : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, Direction.UP}) {
            BlockPos base = target.offset(side);
            BlockState baseState = mc.world.getBlockState(base);
            if (baseState.isAir() || !baseState.isSolidBlock(mc.world, base)) {
                continue;
            }
            Direction face = side.getOpposite();
            Vec3d hit = Vec3d.ofCenter(base).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5,
                    face.getOffsetZ() * 0.5);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, face, base, false));
            p.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private static int hotbarSlot(ClientPlayerEntity p, String id) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = p.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (Registries.ITEM.getId(stack.getItem()).getPath().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从背包换到快捷栏再选中。玩家自己的容器 syncId = 0：
     * 主背包 9..35 对应槽位 9..35，快捷栏 0..8 对应槽位 36..44。
     */
    private static int pullToHotbar(MinecraftClient mc, ClientPlayerEntity p, String id) {
        if (mc.interactionManager == null) {
            return -1;
        }
        int inventorySlot = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = p.getInventory().getStack(i);
            if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getPath().equals(id)) {
                inventorySlot = i;
                break;
            }
        }
        if (inventorySlot < 0) {
            return -1;
        }
        int hotbar = p.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isEmpty()) {
                hotbar = i;
                break;
            }
        }
        mc.interactionManager.clickSlot(0, inventorySlot, hotbar, net.minecraft.screen.slot.SlotActionType.SWAP, p);
        p.getInventory().setSelectedSlot(hotbar);
        return hotbar; // 服务端同步要一 tick，别在这里立刻校验背包
    }
}
