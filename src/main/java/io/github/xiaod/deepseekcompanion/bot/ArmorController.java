package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/**
 * 自动穿"手下最好的一套"盔甲：按护甲值 + 1.5×韧性 + 0.5×保护附魔打分，
 * 每个部位挑分最高的那件换上去。
 *
 * <p>为什么不用 shift 点击：原版的快捷移动只会把盔甲塞进"空着的"盔甲格，
 * 格子里已经有东西时点了等于没点。之前就是这么卡住的——日志一直刷
 * "equipped … on head"，箱子里的铁甲一件没动。</p>
 *
 * <p>现在改成手工三步交换（抓起 → 点盔甲格换下来 → 旧件塞回背包），
 * 一步一个 tick，每步都先验证上一步真的生效了再点下一步；卡住 3 秒就放弃，
 * 下一轮扫描重来。手上要是残留了东西，也会先塞回背包。</p>
 */
public final class ArmorController {
    private static final long SCAN_INTERVAL_MS = 5000L;
    /** 每一步（抓/换/放回）最多等多久。 */
    private static final long STEP_TIMEOUT_MS = 3000L;
    /** 值得换的最小分差：差 0.5 分以内不折腾。 */
    private static final double MIN_GAIN = 0.5;
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static long lastScan;
    /** 0 = 空闲；1 = 抓起背包那件；2 = 点盔甲格换上去；3 = 旧件塞回背包。 */
    private static int swapStep;
    private static int swapInvSlot = -1;
    private static int swapArmorSlot = -1;
    private static String swapItem = "";
    private static long swapDeadline;
    /** 换甲半路放弃时手上可能还挂着旧件：只有这种时候才去清光标。 */
    private static boolean dirtyHands;

    private ArmorController() {
    }

    public static void tick(MinecraftClient mc, CompanionConfig cfg) {
        if (!cfg.autoArmor || mc.player == null || mc.interactionManager == null) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (p.currentScreenHandler != p.playerScreenHandler) {
            return; // 有别的界面开着，别插手
        }
        long now = System.currentTimeMillis();
        if (swapStep != 0) {
            advanceSwap(mc, now);
            return;
        }
        // 别的东西正在动背包/光标时一律不插手：合成就是靠光标抓放材料的，
        // 这里手一抖把光标上的木头塞进背包，合成那边就永远凑不齐了
        if (ContainerController.isBusy() || MiningController.isBusy() || PlacementController.isBusy()
                || DigController.isBusy()) {
            return;
        }
        // 只有自己上一次换甲没收拾干净（手上残留）时才清理，别碰别人的东西
        if (dirtyHands) {
            if (p.playerScreenHandler.getCursorStack().isEmpty()) {
                dirtyHands = false;
            } else {
                emptyHands(mc, p);
                return;
            }
        }
        if (mc.currentScreen != null) {
            return; // 玩家自己开着背包/台子，别抢
        }
        if (now - lastScan < SCAN_INTERVAL_MS) {
            return;
        }
        lastScan = now;
        begin(mc, p);
    }

    /** 每个部位挑分最高的一件，换收益最大的那个（一次扫描只换一件，保持同步）。 */
    private static void begin(MinecraftClient mc, ClientPlayerEntity p) {
        var inventory = p.getInventory();
        EquipmentSlot bestSlot = null;
        int bestIndex = -1;
        double bestGain = MIN_GAIN;
        for (EquipmentSlot slot : SLOTS) {
            double current = score(p.getEquippedStack(slot));
            for (int index = 0; index < 36; index++) {
                ItemStack stack = inventory.getStack(index);
                if (slotOf(stack) != slot) {
                    continue;
                }
                double gain = score(stack) - current;
                if (gain > bestGain) {
                    bestGain = gain;
                    bestSlot = slot;
                    bestIndex = index;
                }
            }
        }
        if (bestSlot == null) {
            return;
        }
        int handlerSlot = handlerSlot(p.playerScreenHandler, inventory, bestIndex);
        if (handlerSlot < 0) {
            return;
        }
        swapItem = idOf(inventory.getStack(bestIndex));
        swapInvSlot = handlerSlot;
        swapArmorSlot = 8 - bestSlot.getEntitySlotId();
        swapStep = 1;
        swapDeadline = System.currentTimeMillis() + STEP_TIMEOUT_MS;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] armor: 换 {} 到 {}（+{}）",
                swapItem, bestSlot.getName(), String.format("%.1f", bestGain));
    }

    /** 一步一个动作，每步先验证上一步的结果。 */
    private static void advanceSwap(MinecraftClient mc, long now) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null || mc.currentScreen != null
                || p.currentScreenHandler != p.playerScreenHandler) {
            abort(); // 界面被抢走了：放下一轮再说
            return;
        }
        if (now > swapDeadline) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] armor: 换甲卡在第 {} 步（{}）", swapStep, swapItem);
            abort();
            return;
        }
        var handler = p.playerScreenHandler;
        ItemStack cursor = handler.getCursorStack();
        ItemStack armor = handler.slots.get(swapArmorSlot).getStack();
        boolean armorHasIt = swapItem.equals(idOf(armor));
        boolean cursorHasIt = swapItem.equals(idOf(cursor));
        switch (swapStep) {
            case 1 -> {
                // 手必须是空的，才能从背包抓起那件
                if (!cursor.isEmpty()) {
                    putBack(mc, p);
                    return;
                }
                if (armorHasIt) {
                    swapStep = 3; // 其实已经穿上了（比如原格子本来就空、上一轮换成了）
                    return;
                }
                click(mc, p, swapInvSlot);
                swapStep = 2;
                swapDeadline = now + STEP_TIMEOUT_MS;
            }
            case 2 -> {
                if (armorHasIt) {
                    swapStep = 3; // 换上了，旧件在手上
                    return;
                }
                if (cursorHasIt) {
                    click(mc, p, swapArmorSlot); // 点盔甲格：空的话放上去，有旧件就换到手上
                    swapDeadline = now + STEP_TIMEOUT_MS;
                    return;
                }
                if (cursor.isEmpty()) {
                    click(mc, p, swapInvSlot); // 没抓到（可能被别的东西占了），再抓一次
                    swapDeadline = now + STEP_TIMEOUT_MS;
                }
            }
            case 3 -> {
                if (cursor.isEmpty()) {
                    finish(p);
                    return;
                }
                click(mc, p, swapInvSlot); // 旧件塞回背包
                swapDeadline = now + STEP_TIMEOUT_MS;
            }
            default -> abort();
        }
    }

    /** 把光标上的东西放到原槽位（同类或空位才放，避免换乱了）。 */
    private static void putBack(MinecraftClient mc, ClientPlayerEntity p) {
        var handler = p.playerScreenHandler;
        ItemStack cursor = handler.getCursorStack();
        for (int id = 9; id < handler.slots.size() && id < 45; id++) {
            ItemStack stack = handler.slots.get(id).getStack();
            if (stack.isEmpty() || idOf(stack).equals(idOf(cursor))) {
                click(mc, p, id);
                return;
            }
        }
        abort(); // 真没地方放：下一轮再说，别把东西丢在地上
    }

    /** 空闲时把光标上的东西塞回背包，免得手上一直挂着。 */
    private static void emptyHands(MinecraftClient mc, ClientPlayerEntity p) {
        putBack(mc, p);
    }

    private static void finish(ClientPlayerEntity p) {
        dirtyHands = false;
        swapStep = 0;
        swapInvSlot = -1;
        swapArmorSlot = -1;
        swapItem = "";
        DeepseekCompanionClient.LOGGER.info("[XiaoD] armor now: 头={} 胸={} 腿={} 脚={}",
                idOf(p.getEquippedStack(EquipmentSlot.HEAD)),
                idOf(p.getEquippedStack(EquipmentSlot.CHEST)),
                idOf(p.getEquippedStack(EquipmentSlot.LEGS)),
                idOf(p.getEquippedStack(EquipmentSlot.FEET)));
    }

    private static void abort() {
        dirtyHands = true;
        swapStep = 0;
        swapInvSlot = -1;
        swapArmorSlot = -1;
        swapItem = "";
    }

    private static void click(MinecraftClient mc, ClientPlayerEntity p, int slot) {
        if (mc.interactionManager == null) {
            return;
        }
        mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, 0, SlotActionType.PICKUP, p);
    }

    private static String idOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "空" : Registries.ITEM.getId(stack.getItem()).getPath();
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
