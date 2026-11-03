package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;

/**
 * Respawns automatically when the bot dies, and drops whatever it was doing so
 * the task system does not keep trying to reach a spot it can no longer stand on.
 */
public final class RespawnController {
    private static final long COOLDOWN_MS = 2000L;
    private static long lastAt;

    private RespawnController() {
    }

    public static void tick(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof DeathScreen)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAt < COOLDOWN_MS) {
            return;
        }
        lastAt = now;
        clearWork(mc);
        if (mc.player != null) {
            mc.player.requestRespawn();
        }
        mc.setScreen(null);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] respawned");
        BotBrain.say("我死了，复活接着干");
    }

    private static void clearWork(MinecraftClient mc) {
        BaritoneBridge.stop();
        TaskTracker.clear();
        FleeController.clear();
        MiningController.cancel(mc);
        StorageController.cancel(mc);
        InteractController.cancel(mc);
        StationController.cancel(mc);
        EnchantController.cancel(mc);
        EnchantPicker.cancel(mc);
        GiveController.cancel();
        ContainerController.cancel(mc);
    }
}
