package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Chooses what the bot cares about based on how far the owner is:
 * close -> fight, far -> follow. The band between the two thresholds keeps the
 * previous decision, so it does not flip-flop while the owner walks around.
 */
public final class PriorityMode {
    public enum Mode {
        NEUTRAL, FIGHT, FOLLOW
    }

    private static Mode mode = Mode.NEUTRAL;

    private PriorityMode() {
    }

    public static void update(MinecraftClient mc, CompanionConfig cfg) {
        PlayerEntity owner = GameSnapshot.findOwner(mc, cfg);
        if (owner == null || mc.player == null) {
            mode = Mode.NEUTRAL;
            return;
        }
        double distance = Math.sqrt(owner.squaredDistanceTo(mc.player));
        if (distance <= cfg.fightRange) {
            mode = Mode.FIGHT;
        } else if (distance >= cfg.followPriorityRange) {
            mode = Mode.FOLLOW;
        }
        // in between: keep the current mode (hysteresis)
    }

    public static boolean isFight() {
        return mode == Mode.FIGHT;
    }

    public static boolean isFollow() {
        return mode == Mode.FOLLOW;
    }

    public static String name() {
        return mode.name().toLowerCase(java.util.Locale.ROOT);
    }
}
