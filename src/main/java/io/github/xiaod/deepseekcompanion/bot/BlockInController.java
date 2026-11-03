package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * 堵墙（block in）：被怪逼住的时候不转身逃，而是面朝怪、在中间垒两格方块把自己隔开，
 * 然后在墙后回血/吃东西，等安全了再继续。
 *
 * <p>为什么不是逃跑：僵尸/小白比玩家慢不了多少，背身跑只会挨打；而两格高的墙能立刻
 * 把近战怪挡在外面，是最省材料的"避其锋芒"。</p>
 */
public final class BlockInController {
    private static final int WALL_HEIGHT = 2;
    private static final long PLACE_INTERVAL_MS = 350L;
    private static final long GIVE_UP_MS = 8000L;
    private static final List<String> PREFERRED = List.of(
            "cobblestone", "cobbled_deepslate", "stone", "dirt", "oak_planks", "spruce_planks",
            "birch_planks", "oak_log", "spruce_log", "sand", "gravel");

    private static LivingEntity threat;
    private static long startedAt;
    private static long lastPlaceAt;
    private static int placed;
    /** 墙垒好后的一段时间里，别再往外跑。 */
    private static long walledUntil;

    private BlockInController() {
    }

    public static boolean isBusy() {
        return threat != null;
    }

    /** 刚垒好墙：这期间应该守着墙回血，而不是继续跑。 */
    public static boolean recentlyWalled() {
        return System.currentTimeMillis() < walledUntil;
    }

    public static boolean start(MinecraftClient mc, LivingEntity target) {
        if (mc.player == null || mc.world == null || target == null) {
            return false;
        }
        if (blockSlot(mc.player) < 0) {
            return false; // 身上没方块，堵不了
        }
        threat = target;
        startedAt = System.currentTimeMillis();
        lastPlaceAt = 0L;
        placed = 0;
        MineCancel(mc);
        Perception.note("被 " + target.getName().getString() + " 逼住，垒墙");
        DeepseekCompanionClient.LOGGER.info("[XiaoD] block-in against {}", target.getName().getString());
        return true;
    }

    private static void MineCancel(MinecraftClient mc) {
        if (MiningController.isBusy()) {
            MiningController.cancel(mc);
        }
    }

    public static void cancel(MinecraftClient mc) {
        threat = null;
        placed = 0;
    }

    public static void tick(MinecraftClient mc, CompanionConfig cfg) {
        if (threat == null) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        long now = System.currentTimeMillis();
        if (p == null || mc.world == null || mc.interactionManager == null || !threat.isAlive()) {
            cancel(mc);
            return;
        }
        if (placed >= WALL_HEIGHT && walledUntil < now) {
            walledUntil = now + 12_000L;
            DeepseekCompanionClient.LOGGER.info("[XiaoD] wall is up ({} blocks), holding position", placed);
        }
        if (now - startedAt > GIVE_UP_MS || placed >= WALL_HEIGHT * 2) {
            // 墙垒好了（或放弃），站墙后回血
            if (p.getHealth() < p.getMaxHealth() * 0.75f) {
                BotActions.eat(mc, true);
            }
            if (now - startedAt > GIVE_UP_MS) {
                cancel(mc);
            }
            return;
        }
        if (now - lastPlaceAt < PLACE_INTERVAL_MS) {
            return;
        }
        int slot = blockSlot(p);
        if (slot < 0) {
            cancel(mc);
            return;
        }
        p.getInventory().setSelectedSlot(slot);
        // 朝怪的方向垒：先脚下那一格，再头上那一格
        Direction facing = horizontalTowards(p, threat);
        BlockPos origin = p.getBlockPos();
        for (int distance = 2; distance <= 3; distance++) {
            BlockPos feet = origin.offset(facing, distance);
            if (PlacementController.placeAt(mc, feet)) {
                placed++;
                lastPlaceAt = now;
                return;
            }
            if (PlacementController.placeAt(mc, feet.up())) {
                placed++;
                lastPlaceAt = now;
                return;
            }
        }
        // 正面位置不对（比如已经贴脸），退一格再垒
        p.setVelocity(p.getVelocity().x - facing.getOffsetX() * 0.18, p.getVelocity().y,
                p.getVelocity().z - facing.getOffsetZ() * 0.18);
    }

    private static Direction horizontalTowards(ClientPlayerEntity p, LivingEntity target) {
        double dx = target.getX() - p.getX();
        double dz = target.getZ() - p.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static int blockSlot(ClientPlayerEntity p) {
        for (String id : PREFERRED) {
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = p.getInventory().getStack(slot);
                if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getPath().equals(id)
                        && stack.getItem() != Items.AIR) {
                    return slot;
                }
            }
        }
        // 兜底：快捷栏里任何"能放下去"的方块（物品本身有方块形态）
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = p.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (net.minecraft.block.Block.getBlockFromItem(stack.getItem()) != net.minecraft.block.Blocks.AIR) {
                return slot;
            }
        }
        return -1;
    }
}
