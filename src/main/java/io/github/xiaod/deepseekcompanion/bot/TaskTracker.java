package io.github.xiaod.deepseekcompanion.bot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/**
 * Remembers that a Baritone task (mine / chop / goto) is in flight, so the
 * auto-follow assist does not yank the bot away from what it was told to do.
 *
 * <p>A task ends only when the bot has stood still long enough (work done or
 * stuck), or when a newer command replaces it - never because the owner walked
 * away, and never on a countdown.</p>
 */
public final class TaskTracker {
    private static final long IDLE_END_MS = 45_000L;
    private static final double MOVED_SQ = 2.25; // 1.5 blocks

    private static boolean active;
    private static String type = "";
    private static long startedAt;
    private static Vec3d lastPos;
    private static long lastMoveAt;

    private TaskTracker() {
    }

    /** The duration argument is kept for call-site readability; tasks do not expire by time. */
    public static void mark(String taskType, long ignoredDurationMs) {
        active = true;
        type = taskType;
        startedAt = System.currentTimeMillis();
        lastPos = null;
        lastMoveAt = startedAt;
    }

    public static void clear() {
        active = false;
        type = "";
        lastPos = null;
    }

    public static boolean isBusy() {
        return active;
    }

    public static String type() {
        return active ? type : "";
    }

    public static long elapsedSeconds() {
        return active ? Math.max(0, (System.currentTimeMillis() - startedAt) / 1000L) : 0L;
    }

    /** Clears the task once the bot has stood still long enough (work done or stuck). */
    public static void tick(MinecraftClient mc) {
        if (!active || mc.player == null) {
            return;
        }
        Vec3d pos = mc.player.getEntityPos();
        if (lastPos == null || pos.squaredDistanceTo(lastPos) > MOVED_SQ) {
            lastPos = pos;
            lastMoveAt = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - lastMoveAt > IDLE_END_MS) {
            clear();
        }
    }
}

