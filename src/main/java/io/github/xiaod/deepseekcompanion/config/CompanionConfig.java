package io.github.xiaod.deepseekcompanion.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config for the client-side companion. Lives at
 * {@code config/bigfatfish.json} and is created on first launch.
 * The API key may also come from the {@code DEEPSEEK_API_KEY} env var.
 */
public final class CompanionConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static CompanionConfig instance;
    private static Path path;

    // ---- DeepSeek ----
    public String apiKey = "";
    public String baseUrl = "https://api.deepseek.com";
    /** 有的服务（Azure/自建网关）不用 Authorization: Bearer，改成自定义头。 */
    public String apiKeyHeader = "Authorization";
    /** 老服务只认 max_tokens，新 OpenAI 模型只认 max_completion_tokens。 */
    public String maxTokensParam = "max_tokens";
    public String model = "deepseek-chat";
    public double temperature = 0.9;
    public int maxTokens = 200;
    public int timeoutSeconds = 25;

    // ---- identity ----
    /** Display name of the bot account; chat messages starting with it trigger a reply. */
    public String botName = "XiaoD";
    /** Real Minecraft account this client may act as. Empty means "use botName". */
    public String botAccount = "";
    /** When true the mod stays dormant on every other account (shared mods folder safety). */
    public boolean onlyRunAsBotAccount = true;
    /** Your own account name — the player this bot follows and protects. */
    public String ownerName = "";
    public String persona = "你叫小D，是一个正在和玩家一起玩 Minecraft 的真人玩家。你说话像队友：短、口语、有情绪、不啰嗦、不说教，偶尔会开玩笑。";

    // ---- behaviour ----
    public boolean enableChat = true;
    /** 常驻杂役：捡掉落物、卡住自救、清垃圾。 */
    public boolean enableButler = true;
    /** 允许第三方模组通过 entrypoint 注册新动作（插件动作只会执行它自己写的行为）。 */
    public boolean enablePluginActions = true;
    /** 聊天调试：记录收到的每条聊天原文与判定结果，游戏里 {@code .xd chat} 可查看。 */
    public boolean chatDebug = false;
    /** 宽松触发：句子里任何位置出现独立的小D 名字都算在叫它（服务器插件改写 @ 时用）。 */
    public boolean chatTriggerAnywhere = false;
    /** Auth plugins (/login): send the password automatically on join. */
    public boolean autoLogin = true;
    public String loginPassword = "";
    public String loginCommand = "/login {password}";
    public int autoLoginDelaySeconds = 4;
    public boolean enableAutoFollow = true;
    public int followDistance = 12;
    /** While a mine/chop/goto task runs, only abandon it when the owner is this far away. 0 = never. */
    public int abandonTaskDistance = 0;
    /** Owner closer than this -> fight priority. */
    public int fightRange = 16;
    /** Owner farther than this -> follow priority. Keep it above fightRange (hysteresis band). */
    public int followPriorityRange = 32;
    public boolean enableAutoFight = true;
    public boolean enableAutoEat = true;
    /** Jump before swinging so the hit lands with vanilla critical conditions. */
    public boolean critEnabled = true;
    /** Below this distance a jump would just eat a hit, so swing flat instead. */
    public float critMinDistance = 2.4f;
    /** Swap to the hotbar stack with the highest attack-damage modifier. */
    public boolean switchWeapon = true;
    /** 挖不同方块自动换最合适的工具（镐/斧/锹/剪刀）。 */
    public boolean autoTool = true;
    /** 快捷栏用空了自动从背包补回来。 */
    public boolean autoReplenish = true;
    /**
     * 公平模式：只挖"人眼看得见"的方块。
     * <p>关掉时，寻路和选矿都会用 Baritone / 区块数据，等于知道墙后面有什么；
     * 打开后目标必须真的在视线里（raycast 打得到），做不到的就不挖，先走过去。</p>
     */
    public boolean fairPlay = false;
    /** Keep the best armour equipped (armour + toughness + protection score). */
    public boolean autoArmor = true;
    /** Disengage and eat at or below this health; 0 disables the retreat. */
    public float retreatHealth = 8.0f;
    public boolean enableProactiveChat = true;
    /** React when the owner unlocks an advancement (parsed from the server broadcast). */
    public boolean enableAdvancementReaction = true;
    /** Let the model phrase the idle chatter (costs a token call); false = local canned lines only. */
    public boolean proactiveUseModel = true;
    /** Idle-chatter interval bounds, in seconds. */
    public int proactiveMinSeconds = 90;
    public int proactiveMaxSeconds = 300;
    /** Let the model decide actions on its own every N seconds. */
    public boolean enableAutonomous = false;
    public int autonomousIntervalSeconds = 45;

    // ---- agent 层（多步规划）----
    /** 单步做不到或单步失败时，才升级成多步计划。关掉就只剩单动作。 */
    public boolean planOnFailure = true;
    /** 一个计划最多几步（硬上限 20，见 Plan.HARD_MAX_STEPS）。 */
    public int planMaxSteps = 20;
    /** 计划里单步超时（秒），超了算这步失败、触发重规划。 */
    public int planStepTimeoutSeconds = 120;
    /** 一个计划允许重规划几次。 */
    public int planMaxReplans = 2;
    /** Baritone command prefix used in chat (Baritone's default is "#"). */
    public String baritonePrefix = "#";
    /** 用 Baritone 原生的躲避怪物（在寻路层面绕开），替代我们自己的逃跑逻辑。 */
    public boolean useBaritoneAvoidance = true;
    /** 躲避半径（格）/ 权重，直接透传给 Baritone。 */
    public int mobAvoidanceRadius = 12;
    public double mobAvoidanceCoefficient = 1.0;

    // ---- home / 保护区 ----
    /** 小D 自己的床（出生点）就是家；睡一次床会自动记下来。 */
    public boolean homeSet = false;
    public String homeDimension = "";
    public int homeX;
    public int homeY;
    public int homeZ;
    /** 保护区正方形半个边长（边心距），64 = 128x128。 */
    public int protectApothem = 64;
    /** 保护区的垂直半高（上下各这么多格）。 */
    public int protectHeight = 24;
    /** 保护区内只准交互、不准破坏方块（只约束小D这个客户端）。 */
    public boolean enableHomeProtection = true;
    /** 投影目录；留空 = <游戏目录>/schematics（Litematica 的默认目录）。 */
    public String schematicDir = "";

    /** Blocks treated as "tree" for the chop action. */
    public String treeBlocks = "oak_log,birch_log,spruce_log,jungle_log,acacia_log,dark_oak_log,mangrove_log,cherry_log,crimson_stem,warped_stem";

    public static CompanionConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        path = FabricLoader.getInstance().getConfigDir().resolve("bigfatfish.json");
        CompanionConfig loaded = new CompanionConfig();
        if (Files.exists(path)) {
            try {
                CompanionConfig parsed = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), CompanionConfig.class);
                if (parsed != null) {
                    loaded = parsed;
                }
            } catch (Exception e) {
                DeepseekCompanionClient.LOGGER.error("[XiaoD] bad config, using defaults", e);
            }
        }
        String envKey = System.getenv("DEEPSEEK_API_KEY");
        if ((loaded.apiKey == null || loaded.apiKey.isBlank()) && envKey != null && !envKey.isBlank()) {
            loaded.apiKey = envKey.trim();
        }
        if (loaded.botName == null || loaded.botName.isBlank()) {
            loaded.botName = "XiaoD";
        }
        if (loaded.persona == null || loaded.persona.isBlank()) {
            loaded.persona = new CompanionConfig().persona;
        }
        if (loaded.protectApothem <= 0) {
            loaded.protectApothem = 64;
        }
        if (loaded.planMaxSteps <= 0 || loaded.planMaxSteps > 20) {
            loaded.planMaxSteps = 20;
        }
        if (loaded.protectHeight <= 0) {
            loaded.protectHeight = 24;
        }
        instance = loaded;
        save();
    }

    public static synchronized void save() {
        if (instance == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (Exception e) {
            DeepseekCompanionClient.LOGGER.error("[XiaoD] cannot write config", e);
        }
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 兼容各种 OpenAI 风格地址：
     * https://api.deepseek.com → .../v1/chat/completions
     * https://api.openai.com/v1 → .../v1/chat/completions
     * .../chat/completions 原样使用；带 api-version= 的 Azure 地址原样使用。
     */
    public String chatEndpoint() {
        String base = (baseUrl == null || baseUrl.isBlank()) ? "https://api.deepseek.com" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions") || base.contains("api-version=")) {
            return base;
        }
        if (base.endsWith("/v1") || base.endsWith("/v1beta") || base.endsWith("/openai")) {
            return base + "/chat/completions";
        }
        return base + "/v1/chat/completions";
    }

    /** 默认走 Authorization: Bearer；换成自定义头时直接放 key。 */
    public boolean usesBearerAuth() {
        return apiKeyHeader == null || apiKeyHeader.isBlank()
                || apiKeyHeader.equalsIgnoreCase("Authorization");
    }
}
