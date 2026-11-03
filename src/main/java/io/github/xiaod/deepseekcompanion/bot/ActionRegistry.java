package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.api.ActionProvider;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 动作注册表：内置枚举 + 第三方插件动作。
 *
 * <p>插件通过 Fabric entrypoint {@code deepseekcompanion:actions} 注册，我们不扫描类路径、
 * 不做动态加载，所以插件必须自己声明、自己实现校验。注册表只做四件事：
 * 校验 id 合法、去重、汇总成给模型的词表、按 id 找到 provider。</p>
 *
 * <p>插件动作<b>不会</b>获得额外权限：它只能做自己代码里写的事，参数来自模型但必须
 * 由插件自己 validate；我们只保证“模型不能凭空造出不被注册的动作”。</p>
 */
public final class ActionRegistry {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{1,31}");
    private static final Map<String, ActionProvider> PLUGINS = new LinkedHashMap<>();
    private static boolean loaded;

    private ActionRegistry() {
    }

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            List<EntrypointContainer<ActionProvider>> containers =
                    FabricLoader.getInstance().getEntrypointContainers("deepseekcompanion:actions", ActionProvider.class);
            for (EntrypointContainer<ActionProvider> container : containers) {
                ActionProvider provider;
                try {
                    provider = container.getEntrypoint();
                } catch (Throwable error) {
                    DeepseekCompanionClient.LOGGER.warn("[XiaoD] plugin action entrypoint failed: {}",
                            container.getProvider().getMetadata().getId(), error);
                    continue;
                }
                register(provider, container.getProvider().getMetadata().getId());
            }
        } catch (Throwable error) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] cannot read plugin actions entrypoint", error);
        }
    }

    /** 也可以在自己的 onInitialize 里直接调用，不一定要走 entrypoint。 */
    public static synchronized boolean register(ActionProvider provider, String source) {
        if (provider == null) {
            return false;
        }
        String id = provider.id() == null ? "" : provider.id().strip().toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] plugin action id rejected: '{}' (from {})", id, source);
            return false;
        }
        if (BotAction.byId(id) != null) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] plugin action '{}' collides with a builtin action", id);
            return false;
        }
        if (PLUGINS.containsKey(id)) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] plugin action '{}' already registered", id);
            return false;
        }
        PLUGINS.put(id, provider);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] plugin action registered: {} ({})", id, source);
        return true;
    }

    public static ActionProvider find(String id) {
        ensureLoaded();
        if (id == null || !enabled()) {
            return null;
        }
        return PLUGINS.get(id.strip().toLowerCase(Locale.ROOT));
    }

    public static List<ActionProvider> available() {
        ensureLoaded();
        List<ActionProvider> list = new ArrayList<>();
        if (!enabled()) {
            return list;
        }
        for (ActionProvider provider : PLUGINS.values()) {
            try {
                if (provider.available()) {
                    list.add(provider);
                }
            } catch (Throwable ignored) {
                // 插件的 available() 崩了就当它不可用
            }
        }
        return list;
    }

    /** 给模型看的插件动作词表（没有插件时返回空串）。 */
    public static String catalogueSuffix() {
        List<ActionProvider> providers = available();
        if (providers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n插件提供的额外动作（同样只能用这些）：\n");
        for (ActionProvider provider : providers) {
            sb.append("- ").append(provider.id()).append("：").append(provider.description());
            switch (provider.argSpec()) {
                case ITEM -> sb.append("（arg=物品 ID）");
                case ITEMS -> sb.append("（arg=物品 ID 列表，逗号分隔）");
                case BLOCKS -> sb.append("（arg=方块 ID）");
                case POS -> sb.append("（arg=三个整数坐标）");
                case ENTITY -> sb.append("（arg=生物类型）");
                case STRING -> sb.append("（arg=自由文本）");
                default -> {
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 执行插件动作：包一层 try/catch，插件崩不能把游戏带走。 */
    public static boolean run(ActionProvider provider, String arg) {
        if (provider == null) {
            return false;
        }
        String validated;
        try {
            validated = provider.validate(arg);
        } catch (IllegalArgumentException bad) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] plugin action {} rejected arg '{}': {}",
                    provider.id(), arg, bad.getMessage());
            BotBrain.tell("插件动作 " + provider.id() + " 参数不对：" + bad.getMessage());
            return false;
        } catch (Throwable error) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] plugin action {} validate() threw", provider.id(), error);
            return false;
        }
        try {
            boolean ok = provider.run(validated);
            DeepseekCompanionClient.LOGGER.info("[XiaoD] plugin action {} arg='{}' ok={}{}",
                    provider.id(), validated, ok, provider.dangerous() ? " [dangerous]" : "");
            return ok;
        } catch (Throwable error) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] plugin action {} threw", provider.id(), error);
            return false;
        }
    }

    public static void tickAll(boolean busy) {
        ensureLoaded();
        for (ActionProvider provider : available()) {
            try {
                provider.tick(busy);
            } catch (Throwable ignored) {
                // 插件的 tick 崩了不影响主循环
            }
        }
    }

    private static boolean enabled() {
        CompanionConfig cfg = CompanionConfig.get();
        return cfg.enablePluginActions;
    }
}
