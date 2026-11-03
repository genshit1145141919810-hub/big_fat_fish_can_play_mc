package io.github.xiaod.deepseekcompanion;

import io.github.xiaod.deepseekcompanion.bot.BaritoneBridge;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import io.github.xiaod.deepseekcompanion.integrations.XaeroWaypoints;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the client half. Hooks live in {@code mixin}: chat is read
 * from the chat HUD, local {@code .xd} commands are cancelled before they leave
 * the client, and the brain ticks with the client.
 */
public class DeepseekCompanionClient implements ClientModInitializer {
    public static final String MOD_ID = "deepseekcompanion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        CompanionConfig.load();
        LOGGER.info("[XiaoD] ready (baritone={}, xaero={}, key={})",
                BaritoneBridge.available(), XaeroWaypoints.available(),
                CompanionConfig.get().hasKey() ? "set" : "missing");
    }
}
