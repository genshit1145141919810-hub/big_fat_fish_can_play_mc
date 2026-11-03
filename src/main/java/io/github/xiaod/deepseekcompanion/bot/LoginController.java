package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

/**
 * Handles /login on servers that use an auth plugin. Sends once a few seconds
 * after the connection changes, and again whenever the server prints a login
 * prompt (some plugins ignore commands sent too early).
 */
public final class LoginController {
    private static final long PROMPT_RETRY_MS = 30_000L;

    private static Object lastHandler;
    private static long sendAt;
    private static long lastSent;
    private static boolean sentForThisConnection;

    private LoginController() {
    }

    public static void tick(MinecraftClient mc, CompanionConfig cfg) {
        if (!cfg.autoLogin) {
            return;
        }
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null || mc.player == null) {
            lastHandler = null;
            sentForThisConnection = false;
            sendAt = 0;
            return;
        }
        if (handler != lastHandler) {
            lastHandler = handler;
            sentForThisConnection = false;
            sendAt = System.currentTimeMillis() + Math.max(1, cfg.autoLoginDelaySeconds) * 1000L;
            return;
        }
        long now = System.currentTimeMillis();
        if (!sentForThisConnection && sendAt > 0 && now >= sendAt) {
            send(mc, cfg);
        }
    }

    /** Called for every chat/system line; reacts to "please login" style prompts only. */
    public static void onChat(String raw, CompanionConfig cfg) {
        if (!cfg.autoLogin || raw == null || cfg.loginPassword.isBlank()) {
            return;
        }
        // Ignore ordinary player chat ("<Name> ..." / "Name: ..."): only server prompts count.
        String trimmed = raw.strip();
        if (trimmed.startsWith("<") || trimmed.matches("^[A-Za-z0-9_]{1,16}:.*")) {
            return;
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        boolean prompt = lower.contains("/login") || lower.contains("/register")
                || lower.contains("please log in") || lower.contains("please login")
                || lower.contains("login required") || lower.contains("not logged in")
                || trimmed.contains("请登录") || trimmed.contains("请先登录")
                || trimmed.contains("登录失败") || trimmed.contains("未登录");
        if (!prompt) {
            return;
        }
        long now = System.currentTimeMillis();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (now - lastSent < PROMPT_RETRY_MS) {
            return;
        }
        send(mc, cfg);
    }

    /** Also used by the .xd login command to log in on demand. */
    public static void sendNow(MinecraftClient mc) {
        send(mc, CompanionConfig.get());
    }

    private static void send(MinecraftClient mc, CompanionConfig cfg) {
        if (mc.getNetworkHandler() == null) {
            return;
        }
        String password = cfg.loginPassword == null ? "" : cfg.loginPassword.strip();
        if (password.isEmpty()) {
            return;
        }
        String template = cfg.loginCommand == null || cfg.loginCommand.isBlank()
                ? "/login {password}" : cfg.loginCommand;
        String command = template.replace("{password}", password);
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        mc.getNetworkHandler().sendChatCommand(command);
        lastSent = System.currentTimeMillis();
        sentForThisConnection = true;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] sent login command ({})",
                template.replace(password.isEmpty() ? "" : password, "***"));
    }
}
