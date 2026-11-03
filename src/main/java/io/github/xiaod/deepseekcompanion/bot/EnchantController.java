package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Enchanting-table reroll loop.
 *
 * <p>Vanilla detail that drives the strategy: the three offers are derived from
 * the player's enchantment seed, and that seed only advances when XP changes.
 * So a reroll means spending XP: the cheapest offer is applied to a throwaway
 * item (books), which rerolls the table. When a wanted enchantment shows up we
 * pay for the most expensive offer to get the highest level.</p>
 */
public final class EnchantController {
    private enum Phase { OPEN, PLACE, READ, ACCEPT, ACCEPTED, REROLL_OUT, REROLL_PLACE, REROLL_CLICK, REROLL_DONE }

    private static boolean active;
    private static Phase phase = Phase.OPEN;
    private static BlockPos tablePos;
    private static String itemId = "";
    private static String wanted = "";
    private static String rerollItem = "book";
    private static int attempts;
    private static int maxAttempts = 12;
    private static int chosen;
    private static long until;
    private static long lastGoto;

    private EnchantController() {
    }

    public static boolean isBusy() {
        return active;
    }

    /** {@code args}: "itemId" or "itemId,desiredEnchantPath" (e.g. diamond_sword,sharpness). */
    public static boolean start(MinecraftClient mc, String args) {
        if (mc.player == null || args == null || args.isBlank()) {
            return false;
        }
        String[] parts = args.split(",");
        itemId = strip(parts[0]);
        wanted = parts.length > 1 ? strip(parts[1]) : "";
        tablePos = findBlock(mc);
        if (tablePos == null) {
            BotBrain.say("附近没有附魔台");
            return false;
        }
        attempts = 0;
        chosen = -1;
        active = true;
        phase = Phase.OPEN;
        return true;
    }

    public static void cancel(MinecraftClient mc) {
        active = false;
        phase = Phase.OPEN;
        if (mc.player != null && mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }
    }

    public static void tick(MinecraftClient mc) {
        if (!active) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null) {
            cancel(mc);
            return;
        }
        long now = System.currentTimeMillis();

        switch (phase) {
            case OPEN -> {
                double distance = Math.sqrt(p.squaredDistanceTo(
                        tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5));
                if (distance > 4.0) {
                    if (now - lastGoto > 2500L && BaritoneBridge.available()) {
                        BaritoneBridge.gotoPos(tablePos.getX(), tablePos.getY() + 1, tablePos.getZ());
                        lastGoto = now;
                    }
                    return;
                }
                if (BaritoneBridge.available()) {
                    BaritoneBridge.stop();
                }
                p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(tablePos));
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(tablePos), Direction.UP, tablePos, false));
                until = now + 3000L;
                phase = Phase.PLACE;
            }
            case PLACE -> {
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
                    if (now > until) {
                        fail("附魔台没打开");
                    }
                    return;
                }
                until = now + 2500L;
                if (!placeFromInventory(mc, handler, itemId, 0)) {
                    fail("身上没有 " + itemId);
                    return;
                }
                placeFromInventory(mc, handler, "lapis_lazuli", 1);
                phase = Phase.READ;
                until = now + 500L;
            }
            case READ -> {
                if (now < until) {
                    return;
                }
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
                    fail("界面关了");
                    return;
                }
                int best = bestOfferFor(handler, wanted);
                if (best >= 0) {
                    chosen = best;
                    phase = Phase.ACCEPT;
                    return;
                }
                if (attempts >= maxAttempts) {
                    fail("刷了 " + attempts + " 次没出想要的附魔");
                    return;
                }
                logOffers(handler);
                phase = Phase.REROLL_OUT;
            }
            case ACCEPT -> {
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
                    fail("界面关了");
                    return;
                }
                if (p.experienceLevel < handler.enchantmentPower[chosen]) {
                    fail("经验不够（要 " + handler.enchantmentPower[chosen] + " 级）");
                    return;
                }
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(
                            new ButtonClickC2SPacket(p.currentScreenHandler.syncId, chosen));
                }
                DeepseekCompanionClient.LOGGER.info("[XiaoD] enchanting with offer {} (cost {} levels)",
                        chosen, handler.enchantmentPower[chosen]);
                phase = Phase.ACCEPTED;
                until = now + 600L;
            }
            case ACCEPTED -> {
                if (now < until) {
                    return;
                }
                if (p.currentScreenHandler instanceof EnchantmentScreenHandler handler) {
                    if (!handler.getSlot(0).getStack().isEmpty()) {
                        mc.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, p);
                    }
                }
                p.closeHandledScreen();
                active = false;
                BotBrain.say("附魔好了");
            }
            case REROLL_OUT -> {
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
                    fail("界面关了");
                    return;
                }
                if (!handler.getSlot(0).getStack().isEmpty()) {
                    mc.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, p);
                }
                phase = Phase.REROLL_PLACE;
                until = now + 400L;
            }
            case REROLL_PLACE -> {
                if (now < until) {
                    return;
                }
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
                    fail("界面关了");
                    return;
                }
                if (!placeFromInventory(mc, handler, rerollItem, 0)) {
                    fail("没有可以拿来刷的 " + rerollItem);
                    return;
                }
                phase = Phase.REROLL_CLICK;
                until = now + 400L;
            }
            case REROLL_CLICK -> {
                if (now < until) {
                    return;
                }
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
                    fail("界面关了");
                    return;
                }
                int cheapest = cheapestOffer(handler);
                if (cheapest < 0) {
                    fail("没有可用的便宜选项");
                    return;
                }
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(
                            new ButtonClickC2SPacket(p.currentScreenHandler.syncId, cheapest));
                }
                phase = Phase.REROLL_DONE;
                until = now + 700L;
            }
            case REROLL_DONE -> {
                if (now < until) {
                    return;
                }
                if (p.currentScreenHandler instanceof EnchantmentScreenHandler handler) {
                    if (!handler.getSlot(0).getStack().isEmpty()) {
                        mc.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, p);
                    }
                }
                attempts++;
                DeepseekCompanionClient.LOGGER.info("[XiaoD] rerolled {} time(s)", attempts);
                phase = Phase.PLACE;
                until = now + 400L;
            }
        }
    }

    /** Highest offer whose enchantment path contains {@code wanted}; -1 when none. */
    private static int bestOfferFor(EnchantmentScreenHandler handler, String wanted) {
        int best = -1;
        int bestPower = -1;
        for (int i = 0; i < 3; i++) {
            if (handler.enchantmentId[i] < 0) {
                continue;
            }
            String id = enchantmentPath(handler.enchantmentId[i]);
            if (wanted.isEmpty() || id.contains(wanted)) {
                if (handler.enchantmentPower[i] > bestPower) {
                    bestPower = handler.enchantmentPower[i];
                    best = i;
                }
            }
        }
        return best;
    }

    private static int cheapestOffer(EnchantmentScreenHandler handler) {
        int best = -1;
        int bestPower = Integer.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            if (handler.enchantmentId[i] >= 0 && handler.enchantmentPower[i] > 0
                    && handler.enchantmentPower[i] < bestPower) {
                bestPower = handler.enchantmentPower[i];
                best = i;
            }
        }
        return best;
    }

    private static void logOffers(EnchantmentScreenHandler handler) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (handler.enchantmentId[i] < 0) {
                continue;
            }
            sb.append('#').append(i).append(' ')
                    .append(enchantmentPath(handler.enchantmentId[i]))
                    .append(' ').append(handler.enchantmentLevel[i])
                    .append(" (cost ").append(handler.enchantmentPower[i]).append(") ");
        }
        DeepseekCompanionClient.LOGGER.info("[XiaoD] offers: {}", sb.toString().trim());
    }

    private static String enchantmentPath(int rawId) {
        try {
            var registry = MinecraftClient.getInstance().world.getRegistryManager()
                    .getOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT);
            for (var enchantment : registry) {
                if (registry.getRawId(enchantment) == rawId) {
                    return registry.getId(enchantment).getPath();
                }
            }
        } catch (Throwable ignored) {
            // registry not ready
        }
        return "#" + rawId;
    }

    private static boolean placeFromInventory(MinecraftClient mc, ScreenHandler handler, String id, int targetSlot) {
        var inventory = mc.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !Registries.ITEM.getId(stack.getItem()).getPath().equals(id)) {
                continue;
            }
            for (Slot handlerSlot : handler.slots) {
                if (handlerSlot.inventory == inventory && handlerSlot.getIndex() == slot) {
                    mc.interactionManager.clickSlot(handler.syncId, handlerSlot.id, 0,
                            SlotActionType.QUICK_MOVE, mc.player);
                    return true;
                }
            }
        }
        return false;
    }

    private static BlockPos findBlock(MinecraftClient mc) {
        for (BlockPos pos : BlockPos.iterateOutwards(mc.player.getBlockPos(), 6, 6, 6)) {
            if (mc.world.getBlockState(pos).isOf(Blocks.ENCHANTING_TABLE)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private static String strip(String id) {
        String value = id == null ? "" : id.strip();
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    private static void fail(String why) {
        DeepseekCompanionClient.LOGGER.warn("[XiaoD] enchanting failed: {}", why);
        BotBrain.say(why);
        active = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }
    }
}
