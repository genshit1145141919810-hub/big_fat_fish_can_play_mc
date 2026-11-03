package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.Vec3d;

/**
 * Delivers an item to the owner: walk over, face them, drop the stack, then step
 * back. Dropping at our own feet would just be picked up again by the vanilla
 * pickup radius, which is why the hand-off used to look "reversed".
 */
public final class GiveController {
    private static final long TIMEOUT_MS = 30_000L;
    private static final long GOTO_INTERVAL_MS = 2500L;
    private static final double HANDOFF_DISTANCE = 3.0;

    private static boolean active;
    private static String itemId = "";
    private static long until;
    private static long lastGoto;

    private GiveController() {
    }

    public static boolean isBusy() {
        return active;
    }

    public static boolean start(MinecraftClient mc, String id) {
        if (mc.player == null) {
            return false;
        }
        itemId = id;
        active = true;
        until = System.currentTimeMillis() + TIMEOUT_MS;
        lastGoto = 0;
        return true;
    }

    public static void cancel() {
        active = false;
        itemId = "";
    }

    public static void tick(MinecraftClient mc, CompanionConfig cfg) {
        if (!active) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null) {
            cancel();
            return;
        }
        long now = System.currentTimeMillis();
        if (now > until) {
            BotBrain.say("走不过去，东西先留着");
            cancel();
            return;
        }
        PlayerEntity owner = GameSnapshot.findOwner(mc, cfg);
        if (owner == null) {
            BotBrain.say("你在哪？我找不到你，东西先留着");
            cancel();
            return;
        }
        if (!hasItem(p, itemId)) {
            BotBrain.say("我身上没有 " + itemId);
            cancel();
            return;
        }
        double distance = Math.sqrt(owner.squaredDistanceTo(p));
        if (distance > HANDOFF_DISTANCE && BaritoneBridge.available()) {
            if (now - lastGoto > GOTO_INTERVAL_MS) {
                BaritoneBridge.gotoPos(owner.getBlockX(), owner.getBlockY(), owner.getBlockZ());
                lastGoto = now;
            }
            return;
        }

        // close enough: stop, face the owner, drop, then step back out of pickup range
        if (BaritoneBridge.available()) {
            BaritoneBridge.stop();
        }
        p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, owner.getEyePos());
        int dropped = dropMatching(mc, p, itemId);
        if (dropped == 0) {
            BotBrain.say("给不了，快捷栏和背包都没找到 " + itemId);
            cancel();
            return;
        }
        DeepseekCompanionClient.LOGGER.info("[XiaoD] gave {} stack(s) of {}", dropped, itemId);
        Vec3d away = p.getEntityPos().subtract(owner.getEntityPos());
        if (away.lengthSquared() < 0.01) {
            away = new Vec3d(1.0, 0.0, 0.0);
        }
        Vec3d behind = p.getEntityPos().add(away.normalize().multiply(3.5));
        if (BaritoneBridge.available()) {
            BaritoneBridge.gotoPos((int) Math.round(behind.x), p.getBlockY(), (int) Math.round(behind.z));
        }
        cancel();
    }

    private static boolean hasItem(ClientPlayerEntity p, String id) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = p.getInventory().getStack(slot);
            if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getPath().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static int dropMatching(MinecraftClient mc, ClientPlayerEntity p, String id) {
        var inventory = p.getInventory();
        int dropped = 0;
        for (int slot = 0; slot < 9 && dropped < 4; slot++) {
            if (matches(inventory.getStack(slot), id)) {
                inventory.setSelectedSlot(slot);
                p.dropSelectedItem(true);
                dropped++;
            }
        }
        if (dropped > 0) {
            return dropped;
        }
        for (int slot = 9; slot < 36; slot++) {
            if (!matches(inventory.getStack(slot), id)) {
                continue;
            }
            int hotbar = freeHotbarSlot(inventory);
            if (hotbar < 0) {
                break;
            }
            try {
                mc.interactionManager.clickSlot(0, slot, hotbar, SlotActionType.SWAP, p);
            } catch (Throwable t) {
                DeepseekCompanionClient.LOGGER.warn("[XiaoD] hotbar swap failed", t);
                return 0;
            }
            inventory.setSelectedSlot(hotbar);
            p.dropSelectedItem(true);
            return 1;
        }
        return 0;
    }

    private static boolean matches(ItemStack stack, String id) {
        return !stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getPath().equals(id);
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

