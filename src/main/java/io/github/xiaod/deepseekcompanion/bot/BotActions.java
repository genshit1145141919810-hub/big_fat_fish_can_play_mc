package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

import java.util.Comparator;

/** Local (non-Baritone) actions: eating and melee, executed on the client thread. */
public final class BotActions {
    private static long eatingUntil;
    private static long nextReissueAt;
    private static boolean eating;

    private BotActions() {
    }

    /**
     * 真正意义上的"停"：计划、挖掘、合成、存箱、交互、站点、附魔、给予、走门、Baritone 全清。
     * 之前 .xd stop 只清了任务标记和 Baritone，规划器会接着往下走（看起来像"没在听"）。
     */
    public static void stopAll(MinecraftClient mc) {
        PlanRunner.stop("玩家喊停");
        MiningController.cancel(mc);
        ContainerController.cancel(mc);
        StorageController.cancel(mc);
        InteractController.cancel(mc);
        StationController.cancel(mc);
        EnchantController.cancel(mc);
        EnchantPicker.cancel(mc);
        GiveController.cancel();
        PortalController.cancel(mc);
        TaskTracker.clear();
        FleeController.clear();
        BaritoneBridge.stop();
    }

    /**
     * Keeps an item-use alive. This deliberately does NOT use the right-click
     * key binding: input handling is skipped while the window is unfocused, and
     * the bot usually runs minimized.
     */
    public static void tick(MinecraftClient mc) {
        if (!eating) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null) {
            eating = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (now > eatingUntil || !p.isAlive()) {
            eating = false;
            if (p.isUsingItem()) {
                p.stopUsingItem();
            }
            return;
        }
        if (!p.isUsingItem() && now >= nextReissueAt) {
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            nextReissueAt = now + 250;
        }
    }

    public static boolean isEating() {
        return eating;
    }

    public static LivingEntity nearestHostile(MinecraftClient mc, double range) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return null;
        }
        return mc.world.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(range),
                        e -> e instanceof Monster && e.isAlive())
                .stream()
                .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)))
                .orElse(null);
    }

    /** Looks at and hits the closest monster. Returns true when a swing happened. */
    public static boolean attackNearest(MinecraftClient mc, double range) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null) {
            return false;
        }
        LivingEntity target = nearestHostile(mc, range);
        if (target == null) {
            return false;
        }
        double distance = Math.sqrt(target.squaredDistanceTo(p));
        if (distance > 3.2) {
            return false;
        }
        p.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
        if (p.getAttackCooldownProgress(0.5f) < 0.9f) {
            return false;
        }
        mc.interactionManager.attackEntity(p, target);
        p.swingHand(Hand.MAIN_HAND);
        return true;
    }

    public static boolean eat(MinecraftClient mc) {
        return eat(mc, false);
    }

    /** {@code force} ignores the "hungry enough" gate (used when hurt: regen needs food). */
    public static boolean eat(MinecraftClient mc, boolean force) {
        ClientPlayerEntity p = mc.player;
        if (p == null || eating || mc.interactionManager == null) {
            return false;
        }
        if (force) {
            if (p.getHungerManager().getFoodLevel() >= 20) {
                return false;
            }
        } else if (p.getHungerManager().getFoodLevel() >= 18) {
            return false;
        }
        int slot = findFoodSlot(mc, p);
        if (slot < 0) {
            return false;
        }
        p.getInventory().setSelectedSlot(slot);
        eating = true;
        eatingUntil = System.currentTimeMillis() + 2600;
        nextReissueAt = 0;
        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] eating from slot {}", slot);
        return true;
    }

    /** Hotbar first, then a backpack stack swapped into the hotbar. Returns the hotbar slot or -1. */
    private static int findFoodSlot(MinecraftClient mc, ClientPlayerEntity p) {
        var inventory = p.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (isFood(inventory.getStack(slot))) {
                return slot;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            if (!isFood(inventory.getStack(slot))) {
                continue;
            }
            int hotbar = freeHotbarSlot(inventory);
            if (hotbar < 0) {
                return -1;
            }
            try {
                mc.interactionManager.clickSlot(0, slot, hotbar, SlotActionType.SWAP, p);
            } catch (Throwable t) {
                DeepseekCompanionClient.LOGGER.warn("[XiaoD] food swap failed", t);
                return -1;
            }
            return hotbar;
        }
        return -1;
    }

    private static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && stack.contains(net.minecraft.component.DataComponentTypes.FOOD);
    }

    private static int freeHotbarSlot(net.minecraft.entity.player.PlayerInventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getStack(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }
}

