package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

/**
 * 自动换工具 + 自动补货（学 Meteor 的 AutoTool / AutoReplenish，但只做生存需要的那部分）。
 *
 * <ul>
 *   <li><b>AutoTool</b>：挖某个方块前，选背包里挖得最快的那把工具（镐/斧/锹/剪刀），
 *       而不是永远举着镐；主背包里的会先换到手上。</li>
 *   <li><b>AutoReplenish</b>：快捷栏某格用空了，就从背包里把同样的东西补回来，
 *       省得长任务挖到一半没方块/没火把。</li>
 * </ul>
 */
public final class AutoTool {
    private static long lastReplenish;

    private AutoTool() {
    }

    /** 挖这个方块前换到最合适的工具；返回是否真的换了。 */
    public static boolean equipFor(MinecraftClient mc, BlockState state) {
        CompanionConfig cfg = CompanionConfig.get();
        if (!cfg.autoTool || mc.player == null || state == null || state.isAir()) {
            return false;
        }
        var inventory = mc.player.getInventory();
        int best = -1;
        float bestSpeed = 1.0f;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = slot;
            }
        }
        if (best < 0) {
            return false;
        }
        return equip(mc, best);
    }

    /** 战斗/放置之外，挖矿时用的"最熟的那把镐"（保留旧行为）。 */
    public static void equipPickaxe(MinecraftClient mc) {
        if (mc.player == null) {
            return;
        }
        var inventory = mc.player.getInventory();
        int best = -1;
        int bestTier = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (!id.endsWith("_pickaxe")) {
                continue;
            }
            int tier = id.startsWith("netherite") ? 5 : id.startsWith("diamond") ? 4
                    : id.startsWith("iron") ? 3 : id.startsWith("stone") ? 2
                    : id.startsWith("golden") ? 1 : 0;
            if (tier > bestTier) {
                bestTier = tier;
                best = slot;
            }
        }
        if (best >= 0) {
            equip(mc, best);
        }
    }

    /** 把第 slot 格换到手上：快捷栏直接选，背包里用 SWAP 键换位。 */
    private static boolean equip(MinecraftClient mc, int slot) {
        var inventory = mc.player.getInventory();
        if (slot == inventory.getSelectedSlot()) {
            return false;
        }
        if (slot < 9) {
            inventory.setSelectedSlot(slot);
            return true;
        }
        int hotbar = inventory.getSelectedSlot();
        int handlerSlot = handlerSlot(mc, slot);
        if (handlerSlot < 0 || mc.interactionManager == null) {
            return false;
        }
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, handlerSlot, hotbar,
                net.minecraft.screen.slot.SlotActionType.SWAP, mc.player);
        return true;
    }

    private static int handlerSlot(MinecraftClient mc, int inventoryIndex) {
        for (var slot : mc.player.currentScreenHandler.slots) {
            if (slot.inventory == mc.player.getInventory() && slot.getIndex() == inventoryIndex) {
                return slot.id;
            }
        }
        return -1;
    }

    /** 快捷栏空了就补：间隔两秒看一次，别每 tick 都在点格子。 */
    public static void replenish(MinecraftClient mc) {
        CompanionConfig cfg = CompanionConfig.get();
        if (!cfg.autoReplenish || mc.player == null || mc.currentScreen != null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReplenish < 2000L) {
            return;
        }
        lastReplenish = now;
        var inventory = mc.player.getInventory();
        for (int hotbar = 0; hotbar < 9; hotbar++) {
            ItemStack current = inventory.getStack(hotbar);
            if (!current.isEmpty()) {
                continue;
            }
            int source = -1;
            for (int slot = 9; slot < 36; slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (!stack.isEmpty() && stack.isStackable()) {
                    source = slot;
                    break;
                }
            }
            if (source < 0) {
                continue;
            }
            int handlerSlot = handlerSlot(mc, source);
            if (handlerSlot < 0 || mc.interactionManager == null) {
                return;
            }
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, handlerSlot, hotbar,
                    net.minecraft.screen.slot.SlotActionType.SWAP, mc.player);
            DeepseekCompanionClient.LOGGER.info("[XiaoD] replenish: 背包 {} -> 快捷栏 {}", source, hotbar);
            return; // 一次只补一格，下一轮再说
        }
    }
}
