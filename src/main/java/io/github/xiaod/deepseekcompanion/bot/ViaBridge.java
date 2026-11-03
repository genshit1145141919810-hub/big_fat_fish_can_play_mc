package io.github.xiaod.deepseekcompanion.bot;

import net.minecraft.client.MinecraftClient;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reads the negotiated protocol from ViaFabric/ViaVersion (bundled by ViaFabric).
 * Reflection only, so the mod still loads without it.
 *
 * <p>The protocol number is what actually matters for behaviour: a 1.8 server has
 * no attack cooldown and no offhand, a 1.20+ server does. Known numbers:
 * 47 = 1.8, 107 = 1.9, 340 = 1.12.2, 754 = 1.16.5, 763 = 1.20.1, 767 = 1.21.1.</p>
 */
public final class ViaBridge {
    private static final Class<?> VIA_API = resolve("com.viaversion.viaversion.api.Via");
    private static int cachedProtocol = Integer.MIN_VALUE;
    private static long cachedAt;

    private ViaBridge() {
    }

    public static boolean available() {
        return VIA_API != null;
    }

    /** -1 when ViaFabric is absent or the player is not connected through it. */
    public static int protocol(MinecraftClient mc) {
        if (VIA_API == null || mc.player == null) {
            return -1;
        }
        long now = System.currentTimeMillis();
        if (cachedProtocol != Integer.MIN_VALUE && now - cachedAt < 5000L) {
            return cachedProtocol;
        }
        cachedAt = now;
        try {
            Method getApi = VIA_API.getMethod("getAPI");
            Object api = getApi.invoke(null);
            UUID uuid = mc.player.getUuid();
            Method playerVersion = api.getClass().getMethod("getPlayerVersion", UUID.class);
            cachedProtocol = ((Number) playerVersion.invoke(api, uuid)).intValue();
        } catch (Throwable t) {
            cachedProtocol = -1;
        }
        return cachedProtocol;
    }

    public static boolean isLegacyCombat(int protocol) {
        return protocol > 0 && protocol <= 47; // 1.8 and below: no attack cooldown
    }

    public static String describe(int protocol) {
        if (protocol <= 0) {
            return "未知";
        }
        return switch (protocol) {
            case 47 -> protocol + " (1.8)";
            case 107 -> protocol + " (1.9)";
            case 110 -> protocol + " (1.9.4)";
            case 340 -> protocol + " (1.12.2)";
            case 404 -> protocol + " (1.13.2)";
            case 498 -> protocol + " (1.14.4)";
            case 578 -> protocol + " (1.15.2)";
            case 754 -> protocol + " (1.16.5)";
            case 758 -> protocol + " (1.18.2)";
            case 763 -> protocol + " (1.20.1)";
            case 765 -> protocol + " (1.20.4)";
            case 767 -> protocol + " (1.21.1)";
            default -> String.valueOf(protocol);
        };
    }

    private static Class<?> resolve(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
