package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

/**
 * While a mining job is running, a monster is avoided instead of fought: stop the
 * job, walk away from the threat, then resume the job once the area is clear.
 */
public final class FleeController {
    private static final long TIMEOUT_MS = 20_000L;
    private static final double THREAT_RANGE = 10.0;
    private static final double SAFE_RANGE = 12.0;
    private static final long GOTO_INTERVAL_MS = 3000L;

    private static boolean active;
    private static long until;
    private static long lastGoto;
    private static String resume = "";

    private FleeController() {
    }

    /** Remembered by the executor so a cancelled mine/chop can be re-issued. */
    public static void remember(String action) {
        resume = action == null ? "" : action;
    }

    public static void clear() {
        active = false;
        resume = "";
    }

    public static boolean isFleeing() {
        return active;
    }

    public static void start(MinecraftClient mc, LivingEntity threat, CompanionConfig cfg) {
        if (active || threat == null) {
            return;
        }
        // 交给 Baritone 原生躲避：它在寻路层面绕开怪，别中途打断任务，也别刷屏
        if (cfg.useBaritoneAvoidance && BaritoneBridge.available() && !MiningController.isBusy()) {
            return;
        }
        active = true;
        until = System.currentTimeMillis() + TIMEOUT_MS;
        lastGoto = 0;
        BaritoneBridge.stop();
        MiningController.cancel(mc);
        Perception.note("被 " + threat.getName().getString() + " 打断，先躲");
        DeepseekCompanionClient.LOGGER.info("[XiaoD] mining interrupted by {}, fleeing", threat.getName().getString());
        if (cfg.enableProactiveChat) {
            BotBrain.tell("有怪，先躲一下再挖");
        }
    }

    public static void tick(MinecraftClient mc, CompanionConfig cfg) {
        if (!active) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            active = false;
            return;
        }
        long now = System.currentTimeMillis();
        LivingEntity threat = BotActions.nearestHostile(mc, (int) SAFE_RANGE);
        boolean safe = threat == null || threat.squaredDistanceTo(p) > SAFE_RANGE * SAFE_RANGE;
        if (now > until || safe) {
            finish();
            return;
        }
        if (now - lastGoto > GOTO_INTERVAL_MS && BaritoneBridge.available()) {
            Vec3d away = p.getEntityPos().subtract(threat.getEntityPos());
            if (away.lengthSquared() < 0.01) {
                away = new Vec3d(1.0, 0.0, 0.0);
            }
            Vec3d destination = p.getEntityPos().add(away.normalize().multiply(14.0));
            if (BaritoneBridge.gotoPos((int) Math.round(destination.x), p.getBlockY(),
                    (int) Math.round(destination.z))) {
                lastGoto = now;
            }
        }
    }

    private static void finish() {
        active = false;
        String action = resume;
        resume = "";
        if (action.isEmpty()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }
        if (action.equals("chop")) {
            if (BaritoneBridge.mine(ActionExecutor.nearestLog(mc))) {
                TaskTracker.mark("chop", 0L);
            }
        } else if (action.startsWith("mine ")) {
            if (BaritoneBridge.mine(action.substring("mine ".length()))) {
                TaskTracker.mark("mine", 0L);
            }
        }
        DeepseekCompanionClient.LOGGER.info("[XiaoD] resuming mining: {}", action);
    }
}

