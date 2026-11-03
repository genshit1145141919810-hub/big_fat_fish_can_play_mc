package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;


/**
 * Picks which copy to enchant out of your inventory, using the 37% rule: the
 * first 37% of the same-type items are sampled to set a baseline, then the first
 * later item that beats that baseline is the one we actually pay for.
 *
 * <p>Reading offers is free: placing an item shows its three offers, so sampling
 * costs nothing but a few clicks per item.</p>
 */
public final class EnchantPicker {
    private enum Phase { OPEN, SCAN_PLACE, SCAN_READ, SCAN_REMOVE, DECIDE, PUT_CHOSEN, CLICK, TAKE, CLOSE }

    private record Offer(int invSlot, int offerIndex, String enchant, int level, int cost, double score) {
    }

    private static boolean active;
    private static Phase phase = Phase.OPEN;
    private static BlockPos tablePos;
    private static String itemId = "";
    private static String wanted = "";
    private static final List<Integer> candidates = new ArrayList<>();
    private static final List<Offer> offers = new ArrayList<>();
    private static int scanIndex;
    private static int sampleSize;
    private static Offer chosen;
    private static long until;

    private EnchantPicker() {
    }

    public static boolean isBusy() {
        return active;
    }

    /** {@code args}: "itemId" or "itemId,desiredEnchantPath". */
    public static boolean start(MinecraftClient mc, String args) {
        if (mc.player == null || args == null || args.isBlank()) {
            return false;
        }
        String[] parts = args.split(",");
        itemId = strip(parts[0]);
        wanted = parts.length > 1 ? strip(parts[1]) : "";
        candidates.clear();
        offers.clear();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getPath().equals(itemId)) {
                candidates.add(slot);
            }
        }
        if (candidates.isEmpty()) {
            BotBrain.say("背包里没有 " + itemId);
            return false;
        }
        tablePos = findBlock(mc);
        if (tablePos == null) {
            BotBrain.say("附近没有附魔台");
            return false;
        }
        scanIndex = 0;
        chosen = null;
        int n = candidates.size();
        sampleSize = Math.max(1, (int) Math.ceil(n * 0.37));
        active = true;
        phase = Phase.OPEN;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] enchanting {} copies of {} (sample {})", n, itemId, sampleSize);
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
                if (now < until) {
                    return;
                }
                p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(tablePos));
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(tablePos), Direction.UP, tablePos, false));
                until = now + 3000L;
                phase = Phase.SCAN_PLACE;
            }
            case SCAN_PLACE -> {
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
                    if (now > until) {
                        fail("附魔台没打开");
                    }
                    return;
                }
                if (now < until) {
                    return;
                }
                if (scanIndex >= candidates.size()) {
                    phase = Phase.DECIDE;
                    return;
                }
                if (!handler.getSlot(0).getStack().isEmpty()) {
                    mc.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, p);
                    until = now + 400L;
                    return;
                }
                int invSlot = candidates.get(scanIndex);
                if (!moveToTable(mc, handler, invSlot)) {
                    scanIndex++;
                    return;
                }
                until = now + 450L;
                phase = Phase.SCAN_READ;
            }
            case SCAN_READ -> {
                if (now < until) {
                    return;
                }
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
                    fail("界面关了");
                    return;
                }
                offers.add(readOffer(handler, candidates.get(scanIndex)));
                phase = Phase.SCAN_REMOVE;
                until = now + 300L;
            }
            case SCAN_REMOVE -> {
                if (now < until) {
                    return;
                }
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
                    fail("界面关了");
                    return;
                }
                mc.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, p);
                scanIndex++;
                phase = Phase.SCAN_PLACE;
                until = now + 400L;
            }
            case DECIDE -> {
                chosen = decide();
                if (chosen == null) {
                    fail("没有合适的附魔");
                    return;
                }
                DeepseekCompanionClient.LOGGER.info("[XiaoD] picked slot {} ({})", chosen.invSlot(), chosen.enchant());
                phase = Phase.PUT_CHOSEN;
            }
            case PUT_CHOSEN -> {
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
                    fail("界面关了");
                    return;
                }
                if (now < until) {
                    return;
                }
                if (handler.getSlot(0).getStack().isEmpty()) {
                    if (!moveToTable(mc, handler, chosen.invSlot())) {
                        fail("放不进去");
                        return;
                    }
                    ensureLapis(mc, handler);
                    until = now + 500L;
                    return;
                }
                phase = Phase.CLICK;
            }
            case CLICK -> {
                if (now < until) {
                    return;
                }
                if (!(p.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
                    fail("界面关了");
                    return;
                }
                if (p.experienceLevel < handler.enchantmentPower[chosen.offerIndex()]) {
                    fail("经验不够（要 " + handler.enchantmentPower[chosen.offerIndex()] + " 级）");
                    return;
                }
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(
                            new ButtonClickC2SPacket(handler.syncId, chosen.offerIndex()));
                }
                until = now + 700L;
                phase = Phase.TAKE;
            }
            case TAKE -> {
                if (now < until) {
                    return;
                }
                if (p.currentScreenHandler instanceof EnchantmentScreenHandler handler
                        && !handler.getSlot(0).getStack().isEmpty()) {
                    mc.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, p);
                }
                until = now + 400L;
                phase = Phase.CLOSE;
            }
            case CLOSE -> {
                if (now < until) {
                    return;
                }
                p.closeHandledScreen();
                active = false;
                BotBrain.say("附魔好了：" + chosen.enchant() + " " + chosen.level());
            }
        }
    }

    /** 37% rule: beat the sample's best, else take the overall best. */
    private static Offer decide() {
        List<Offer> valid = new ArrayList<>();
        for (Offer offer : offers) {
            if (!offer.enchant().isEmpty() && (wanted.isEmpty() || offer.enchant().contains(wanted))) {
                valid.add(offer);
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        if (!wanted.isEmpty()) {
            // explicit target: highest level wins
            Offer best = null;
            for (Offer offer : valid) {
                if (best == null || offer.level() > best.level()) {
                    best = offer;
                }
            }
            return best;
        }
        int sampleCount = Math.min(sampleSize, valid.size());
        double threshold = 0;
        for (int i = 0; i < sampleCount; i++) {
            threshold = Math.max(threshold, valid.get(i).score());
        }
        for (int i = sampleCount; i < valid.size(); i++) {
            if (valid.get(i).score() > threshold) {
                return valid.get(i);
            }
        }
        Offer best = null;
        for (Offer offer : valid) {
            if (best == null || offer.score() > best.score()) {
                best = offer;
            }
        }
        return best;
    }

    private static Offer readOffer(EnchantmentScreenHandler handler, int invSlot) {
        int bestIndex = -1;
        double bestScore = -1;
        String bestName = "";
        int bestLevel = 0;
        int bestCost = 0;
        for (int i = 0; i < 3; i++) {
            if (handler.enchantmentId[i] < 0) {
                continue;
            }
            String path = enchantmentPath(handler.enchantmentId[i]);
            double score = io.github.xiaod.deepseekcompanion.integrations.EnchantQuality.expectedScore(path, handler.enchantmentLevel[i]);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
                bestName = path;
                bestLevel = handler.enchantmentLevel[i];
                bestCost = handler.enchantmentPower[i];
            }
        }
        return new Offer(invSlot, Math.max(bestIndex, 0), bestName, bestLevel, bestCost, Math.max(bestScore, 0));
    }

    private static boolean moveToTable(MinecraftClient mc, EnchantmentScreenHandler handler, int invSlot) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory() && slot.getIndex() == invSlot) {
                mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                return true;
            }
        }
        return false;
    }

    private static void ensureLapis(MinecraftClient mc, EnchantmentScreenHandler handler) {
        if (!handler.getSlot(1).getStack().isEmpty()) {
            return;
        }
        var inventory = mc.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (Registries.ITEM.getId(inventory.getStack(slot).getItem()).getPath().equals("lapis_lazuli")) {
                for (Slot handlerSlot : handler.slots) {
                    if (handlerSlot.inventory == inventory && handlerSlot.getIndex() == slot) {
                        mc.interactionManager.clickSlot(handler.syncId, handlerSlot.id, 0,
                                SlotActionType.QUICK_MOVE, mc.player);
                        return;
                    }
                }
            }
        }
    }

    private static String enchantmentPath(int rawId) {
        try {
            var registry = MinecraftClient.getInstance().world.getRegistryManager()
                    .getOrThrow(RegistryKeys.ENCHANTMENT);
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
        DeepseekCompanionClient.LOGGER.warn("[XiaoD] enchant picking failed: {}", why);
        BotBrain.say(why);
        active = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }
    }
}
