package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Keeps the best armour equipped, scored the same way weapons are: armour points,
 * toughness, and protection enchantment. Upgrades are equipped with a shift-click
 * on the player's own inventory handler, which makes vanilla swap the old piece out.
 */
public final class ArmorController {
    private static final long SCAN_INTERVAL_MS = 5000L;
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static long lastScan;

    private ArmorController() {
    }

    public static void tick(MinecraftClient mc, CompanionConfig cfg) {
        if (!cfg.autoArmor || mc.player == null || mc.interactionManager == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_INTERVAL_MS) {
            return;
        }
        lastScan = now;
        ClientPlayerEntity p = mc.player;
        if (mc.currentScreen != null || p.currentScreenHandler != p.playerScreenHandler) {
            return; // do not fight with an open container
        }
        var inventory = p.getInventory();
        for (int index = 0; index < 36; index++) {
            ItemStack candidate = inventory.getStack(index);
            EquipmentSlot slot = slotOf(candidate);
            if (slot == null) {
                continue;
            }
            ItemStack current = p.getEquippedStack(slot);
            if (score(candidate) <= score(current) + 0.5) {
                continue;
            }
            int handlerSlot = handlerSlot(p.playerScreenHandler, inventory, index);
            if (handlerSlot < 0) {
                continue;
            }
            try {
                mc.interactionManager.clickSlot(0, handlerSlot, 0, SlotActionType.QUICK_MOVE, p);
                DeepseekCompanionClient.LOGGER.info("[XiaoD] equipped {} on {} (score {})",
                        candidate.getName().getString(), slot.getName(), String.format("%.1f", score(candidate)));
            } catch (Throwable t) {
                DeepseekCompanionClient.LOGGER.warn("[XiaoD] armor equip failed", t);
            }
            return; // one swap per scan keeps the server in sync
        }
    }

    private static EquipmentSlot slotOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        if (equippable == null) {
            return null;
        }
        EquipmentSlot slot = equippable.slot();
        for (EquipmentSlot candidate : SLOTS) {
            if (candidate == slot) {
                return slot;
            }
        }
        return null;
    }

    /** armour + 1.5 * toughness + 0.5 * protection level. */
    private static double score(ItemStack stack) {
        if (slotOf(stack) == null) {
            return 0.0;
        }
        double total = 0.0;
        try {
            var component = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (component != null) {
                for (var entry : component.modifiers()) {
                    var attribute = entry.attribute().value();
                    if (attribute == EntityAttributes.ARMOR.value()) {
                        total += entry.modifier().value();
                    } else if (attribute == EntityAttributes.ARMOR_TOUGHNESS.value()) {
                        total += entry.modifier().value() * 1.5;
                    }
                }
            }
            total += enchantLevel(stack, net.minecraft.enchantment.Enchantments.PROTECTION) * 0.5;
        } catch (Throwable ignored) {
            // component/enchantment API mismatch: keep whatever score we have
        }
        return total;
    }

    private static int enchantLevel(ItemStack stack, net.minecraft.registry.RegistryKey<net.minecraft.enchantment.Enchantment> key) {
        try {
            var enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
            if (enchantments == null) {
                return 0;
            }
            for (var entry : enchantments.getEnchantments()) {
                if (entry.matchesKey(key)) {
                    return enchantments.getLevel(entry);
                }
            }
        } catch (Throwable ignored) {
            // unenchanted if the lookup fails
        }
        return 0;
    }

    private static int handlerSlot(ScreenHandler handler, net.minecraft.entity.player.PlayerInventory inventory, int invIndex) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == inventory && slot.getIndex() == invIndex) {
                return slot.id;
            }
        }
        return -1;
    }
}
