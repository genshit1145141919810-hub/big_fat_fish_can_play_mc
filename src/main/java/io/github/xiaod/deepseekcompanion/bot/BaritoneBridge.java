package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.util.regex.Pattern;

/**
 * Thin bridge to Baritone. Baritone consumes outgoing chat lines that start
 * with its prefix, so there is no compile-time dependency on its API.
 *
 * <p>Only walking commands are allowed here. Baritone's {@code #mine} builds a
 * {@code BlockOptionalMeta} on the render thread and can deadlock (it joins a
 * future only the render thread could complete), so mining is done by
 * {@link MiningController} with real player actions instead.</p>
 */
public final class BaritoneBridge {
    /** The only command shapes this mod is allowed to emit. */
    private static final Pattern ALLOWED = Pattern.compile(
            "^(stop|come"
                    + "|follow player [A-Za-z0-9_]{1,16}"
                    + "|follow entity [a-z0-9_]{1,48}"
                    + "|goto -?[0-9]{1,8} -?[0-9]{1,3} -?[0-9]{1,8}"
                    + "|mine [a-z0-9_]{1,48}"
                    // Baritone 原生设置：只放行"设置名 + 布尔/数值"
                    + "|[a-z]{3,32} (?:true|false|[0-9]{1,3}(?:\\.[0-9]{1,3})?))$");
    private static boolean warned;

    private BaritoneBridge() {
    }

    public static boolean available() {
        return FabricLoader.getInstance().isModLoaded("baritone");
    }

    /** Returns true when the command was accepted and handed to the client. */
    public static boolean exec(String command) {
        if (command == null || !ALLOWED.matcher(command).matches()) {
            DeepseekCompanionClient.LOGGER.error("[XiaoD] rejected non-whitelisted command: {}", command);
            return false;
        }
        if (!available()) {
            if (!warned) {
                warned = true;
                DeepseekCompanionClient.LOGGER.warn("[XiaoD] Baritone not installed - movement disabled");
            }
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) {
            return false;
        }
        CompanionConfig cfg = CompanionConfig.get();
        String prefix = cfg.baritonePrefix == null || cfg.baritonePrefix.isEmpty() ? "#" : cfg.baritonePrefix;
        mc.getNetworkHandler().sendChatMessage(prefix + command);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] baritone <- {}{}", prefix, command);
        return true;
    }

    public static boolean followPlayer(String name) {
        if (!ActionValidator.isSafePlayerName(name)) {
            return false;
        }
        return exec("follow player " + name);
    }

    public static boolean followEntity(String type) {
        if (!ActionValidator.isSafeEntityId(type)) {
            return false;
        }
        return exec("follow entity " + type);
    }

    public static boolean gotoPos(int x, int y, int z) {
        return exec("goto " + x + " " + y + " " + z);
    }

    /**
     * Baritone's mine takes exactly one block. The BOM deadlock it could hit is
     * patched by {@code BaritoneBlockOptionalMetaMixin}, so this path is safe again.
     */
    public static boolean mine(String block) {
        if (block == null || block.isBlank()) {
            return false;
        }
        if (!block.matches("[a-z0-9_]{1,48}")) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] mine rejected: '{}'", block);
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (HomeZone.protectedTargetNear(mc, block, false, 24) != null) {
            DeepseekCompanionClient.LOGGER.info(
                    "[XiaoD] mine {} refused: nearest target sits in the home protection zone", block);
            return false;
        }
        return exec("mine " + block);
    }

    public static boolean stop() {
        return exec("stop");
    }

    /**
     * 用 Baritone 原生的"躲避怪物"，而不是我们自己再写一套逃跑逻辑：
     * 它在寻路层面绕开怪，路线更合理，也不用中途打断任务。
     * 只放行固定几个设置名，值只允许布尔或数值。
     */
    public static void applyAvoidance(int radius, double coefficient) {
        setSetting("avoidance", "true");
        setSetting("mobavoidanceradius", String.valueOf(Math.max(1, Math.min(64, radius))));
        setSetting("mobavoidancecoefficient", String.format(java.util.Locale.ROOT, "%.2f",
                Math.max(0.1, Math.min(10.0, coefficient))));
    }

    public static boolean setSetting(String name, String value) {
        if (name == null || value == null || name.isBlank() || value.isBlank()) {
            return false;
        }
        return exec(name.strip().toLowerCase(java.util.Locale.ROOT) + " " + value.strip());
    }

    public static boolean come() {
        return exec("come");
    }
}
