package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.chat.Intent;
import io.github.xiaod.deepseekcompanion.chat.LlmClient;
import io.github.xiaod.deepseekcompanion.chat.Prompts;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.MinecraftClient;

/**
 * The brain: turns chat into validated intents, feeds query results back to the
 * model once, runs the always-on assists (follow / fight / eat) and occasionally
 * starts a conversation on its own.
 */
public final class BotBrain {
    private static final Deque<LlmClient.Msg> HISTORY = new ArrayDeque<>();
    private static final Deque<String> RECENT_SAYS = new ArrayDeque<>();
    private static final int MAX_HISTORY = 8;
    private static final int MAX_RECENT_SAYS = 6;

    private static volatile boolean pending;
    private static int correctionAttempts;
    private static int planCorrections;
    private static boolean escalated;
    private static int queryDepth;
    private static long lastFollowCmd;
    private static long lastOwnerAsk;
    private static long lastEatAt;
    private static long lastAutonomousAt;
    private static long lastPlayerAt;
    private static long lastBotSayAt;
    private static long nextProactiveAt;
    private static long lastAdvancementAt;
    private static String lastAct = "";
    /** 玩家那句话去掉 @提及 之后的内容，用来当"目标"文案，避免把"名字："也塞进去。 */
    private static String lastPayload = "";
    /** 聊天去重：同一句话这段时间内只发一次。 */
    private static String lastSaidText = "";
    private static long lastSaidAt;
    private static final long SAY_DEDUPE_MS = 10_000L;

    private static boolean dormancyLogged; private static boolean avoidanceApplied;

    private BotBrain() {
    }

    /**
     * The mod lives in a shared mods folder, so it must not take over any other
     * account that happens to launch the same instance. Only the configured bot
     * account is allowed to run the assists, chat and proactive lines.
     */
    public static boolean isActiveAccount(MinecraftClient mc) {
        CompanionConfig cfg = CompanionConfig.get();
        if (!cfg.onlyRunAsBotAccount) {
            return true;
        }
        if (mc.player == null) {
            return false;
        }
        String expected = cfg.botAccount == null || cfg.botAccount.isBlank() ? cfg.botName : cfg.botAccount;
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String actual = mc.player.getName().getString();
        boolean active = actual.equalsIgnoreCase(expected.strip());
        if (!active && !dormancyLogged) {
            dormancyLogged = true;
            DeepseekCompanionClient.LOGGER.info("[XiaoD] dormant on account '{}' (bot account is '{}')", actual, expected);
        }
        return active;
    }

    public static boolean baritoneLoaded() {
        return BaritoneBridge.available();
    }

    // ------------------------------------------------------------------ chat

    public static void onPlayerSpoke(String from, String text) {
        CompanionConfig cfg = CompanionConfig.get();
        lastPlayerAt = System.currentTimeMillis();
        String line = from == null || from.isBlank() ? text : from + "：" + text;
        lastPayload = text == null ? "" : text.strip();
        HISTORY.add(new LlmClient.Msg("user", line));
        trimHistory();
        queryDepth = 0;
        escalated = false;
        planCorrections = 0;
        if (PlanRunner.isPlanning()) {
            // 计划正在跑：玩家直接下令（跟我走/过来/停下）时先停计划再照做，
            // 别让一个卡住的计划把玩家的话全吞掉
            Intent quick = Intent.keyword(lastPayload);
            if (quick.action() == BotAction.STOP || quick.action() == BotAction.COME
                    || quick.action() == BotAction.FOLLOW) {
                PlanRunner.stop("玩家插入指令");
                tell("计划停了");
                apply(quick, lastPayload);
                return;
            }
            tell("手上还有活：" + shorten(PlanRunner.goalText(), 24) + "（急事就 .xd plan stop）");
            return;
        }
        if (!cfg.hasKey()) {
            apply(Intent.keyword(text), text);
            return;
        }
        ask(line, false, null);
    }

    private static void ask(String userText, boolean autonomous, String correctionReason) {        if (pending) {
            return;
        }
        pending = true;
        MinecraftClient mc = MinecraftClient.getInstance();
        String system = Prompts.system(GameSnapshot.build(mc), autonomous);
        List<LlmClient.Msg> history = LlmClient.trim(new ArrayList<>(HISTORY), MAX_HISTORY);
        String user = correctionReason != null ? Prompts.correction(correctionReason) : userText;
        LlmClient.complete(system, history, user).whenComplete((reply, error) -> mc.execute(() -> {
            pending = false;
            if (error != null || reply == null || reply.isBlank()) {
                DeepseekCompanionClient.LOGGER.warn("[XiaoD] model unavailable, keyword fallback");
                apply(Intent.keyword(userText));
                return;
            }
            handleReply(reply, autonomous, userText);
        }));
    }

    private static void handleReply(String reply, boolean autonomous, String original) {
        // agent 层升级：模型自己说"一步做不到，给我规划"
        String needPlan = Prompts.extractNeedPlan(reply);
        if (needPlan != null) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] model asked for a plan: {}", needPlan);
            correctionAttempts = 0;
            HISTORY.add(new LlmClient.Msg("assistant", reply));
            trimHistory();
            requestPlan(needPlan, original);
            return;
        }
        Intent intent;
        try {
            intent = Intent.parse(reply);
        } catch (ActionValidator.FormatException e) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] rejected model output: {}", e.getMessage());
            if (correctionAttempts < 1) {
                correctionAttempts++;
                ask(original, autonomous, e.getMessage());
            } else {
                correctionAttempts = 0;
                apply(Intent.keyword(original));
            }
            return;
        }
        correctionAttempts = 0;
        HISTORY.add(new LlmClient.Msg("assistant", reply));
        trimHistory();

        if (intent.action() != null && intent.action().isQuery()) {
            if (!intent.say().isBlank()) {
                say(intent.say());
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            ActionExecutor.Result result = ActionExecutor.execute(mc, intent);
            String observation = result.observation();
            DeepseekCompanionClient.LOGGER.info("[XiaoD] query {} -> {}", intent.action().id(), observation);
            if (observation == null || observation.isBlank()) {
                return;
            }
            if (queryDepth < 1 && CompanionConfig.get().hasKey()) {
                queryDepth++;
                ask(original + "\n【工具结果】" + observation, autonomous, null);
            } else {
                say(ActionValidator.sanitizeSay(observation));
            }
            return;
        }
        apply(intent, original);
    }

    private static void apply(Intent intent) {
        apply(intent, "");
    }

    private static void apply(Intent intent, String origin) {
        if (intent.action() != null) {
            lastAct = intent.action().id();
        }
        if (!intent.say().isBlank()) {
            say(intent.say());
        }
        if ((intent.action() != null && !intent.action().isQuery()) || intent.pluginAction() != null) {
            MinecraftClient mc = MinecraftClient.getInstance();
            boolean ok = ActionExecutor.execute(mc, intent).ok();
            DeepseekCompanionClient.LOGGER.info("[XiaoD] act={} arg={} ok={}",
                    intent.pluginAction() != null ? "plugin:" + intent.pluginAction() : intent.action().id(),
                    intent.arg(), ok);
            CompanionConfig cfg = CompanionConfig.get();
            // 单步做不成才是 agent 层的入口：失败 → 升级成多步计划（每人一次机会）
            if (!ok && cfg.planOnFailure && !escalated && !PlanRunner.isPlanning()) {
                escalated = true;
                String goal = lastPayload == null || lastPayload.isBlank()
                        ? (origin == null || origin.isBlank() ? intent.action().description() : origin)
                        : lastPayload;
                goal = shorten(goal, 40);
                DeepseekCompanionClient.LOGGER.info("[XiaoD] single step failed, escalating to a plan: {}", goal);
                requestPlan("完成这件事：" + goal,
                        "你刚才只做单步「" + intent.action().id() + "」，没成。玩家原话：" + goal
                                + "。现在拆成多步计划。");
            }
        }
    }

    // ------------------------------------------------------- agent 层（规划）

    /**
     * 把目标交给模型拆成计划。这是"单步做不到"之后的升级路径：
     * 模型主动要（need_plan）或单步执行失败时才会走到这里，平时一次对话只走单动作。
     */
    public static void requestPlan(String goal, String context) {
        if (goal == null || goal.isBlank()) {
            return;
        }
        if (pending) {
            tell("我这边正忙，等我一下");
            return;
        }
        CompanionConfig cfg = CompanionConfig.get();
        if (!cfg.hasKey()) {
            tell("没配置密钥，规划做不了");
            return;
        }
        pending = true;
        final int epoch = PlanRunner.epoch();
        MinecraftClient mc = MinecraftClient.getInstance();
        String system = Prompts.plan(GameSnapshot.build(mc), goal);
        String user = context == null || context.isBlank() ? "目标：" + goal : context;
        LlmClient.complete(system, LlmClient.trim(new ArrayList<>(HISTORY), MAX_HISTORY), user)
                .whenComplete((reply, error) -> mc.execute(() -> {
                    pending = false;
                    if (epoch != PlanRunner.epoch()) {
                        return; // 这段对话已经被喊停了，别自己续上
                    }
                    if (error != null || reply == null || reply.isBlank()) {
                        tell("想不出计划，我先按单步试");
                        apply(Intent.keyword(goal), goal);
                        return;
                    }
                    startPlanFromReply(goal, reply);
                }));
    }

    private static void startPlanFromReply(String goal, String reply) {
        try {
            Plan plan = Plan.parse(reply, CompanionConfig.get().planMaxSteps);
            if (!plan.say().isBlank()) {
                tell(plan.say());
            }
            PlanRunner.start(goal, plan);
        } catch (ActionValidator.FormatException e) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] bad plan: {}", e.getMessage());
            if (planCorrections < 1) {
                planCorrections++;
                requestPlanCorrection(goal, e.getMessage());
            } else {
                say("这活我拆不开（" + e.getMessage() + "），先按单步试");
                apply(Intent.keyword(goal), goal);
            }
        }
    }

    private static void requestPlanCorrection(String goal, String reason) {
        if (pending) {
            return;
        }
        pending = true;
        MinecraftClient mc = MinecraftClient.getInstance();
        String system = Prompts.planCorrection(GameSnapshot.build(mc), goal, reason);
        LlmClient.complete(system, LlmClient.trim(new ArrayList<>(HISTORY), MAX_HISTORY), "目标：" + goal)
                .whenComplete((reply, error) -> mc.execute(() -> {
                    pending = false;
                    if (error != null || reply == null || reply.isBlank()) {
                        say("拆不开这活");
                        return;
                    }
                    startPlanFromReply(goal, reply);
                }));
    }

    /** 计划某一步失败：让模型换一份计划，而不是死磕。 */
    public static void requestReplan(String goal, String observation, String reason) {
        if (pending) {
            PlanRunner.onReplanFailed("模型正忙");
            return;
        }
        pending = true;
        MinecraftClient mc = MinecraftClient.getInstance();
        String system = Prompts.planCorrection(GameSnapshot.build(mc), goal,
                "执行到「" + reason + "」就卡住了，换一种做法");
        LlmClient.complete(system, LlmClient.trim(new ArrayList<>(HISTORY), MAX_HISTORY), observation)
                .whenComplete((reply, error) -> mc.execute(() -> {
                    pending = false;
                    if (error != null || reply == null || reply.isBlank()) {
                        PlanRunner.onReplanFailed("模型没回话");
                        return;
                    }
                    try {
                        Plan plan = Plan.parse(reply, CompanionConfig.get().planMaxSteps);
                        if (!plan.say().isBlank()) {
                            tell(plan.say());
                        }
                        PlanRunner.onReplan(plan);
                    } catch (ActionValidator.FormatException e) {
                        PlanRunner.onReplanFailed(e.getMessage());
                    }
                }));
    }

    /** 只发给自己客户端看的提示：规划/状态这类不该别人看见的东西全走这里。 */
    public static void tell(String message) {
        message = io.github.xiaod.deepseekcompanion.chat.Lang.t(message);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && message != null && !message.isBlank()) {
            mc.player.sendMessage(net.minecraft.text.Text.literal("[小D] " + message), false);
        }
        DeepseekCompanionClient.LOGGER.info("[XiaoD] local: {}", message);
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.strip();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    public static void say(String text) {
        text = io.github.xiaod.deepseekcompanion.chat.Lang.t(text);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null || text == null || text.isBlank()) {
            return;
        }
        // 去重：同一句话短时间内只发一次。逃跑/挖掘这类每 tick 都可能重入的路径
        // 之前会把"有怪，先躲一下再挖"刷成一屏。
        long now = System.currentTimeMillis();
        if (text.equals(lastSaidText) && now - lastSaidAt < SAY_DEDUPE_MS) {
            return;
        }
        lastSaidText = text;
        lastSaidAt = now;
        mc.getNetworkHandler().sendChatMessage(text);
        lastBotSayAt = now;
        remember(text);
    }

    private static void remember(String line) {
        RECENT_SAYS.addLast(line);
        while (RECENT_SAYS.size() > MAX_RECENT_SAYS) {
            RECENT_SAYS.removeFirst();
        }
    }

    private static void trimHistory() {
        while (HISTORY.size() > MAX_HISTORY) {
            HISTORY.removeFirst();
        }
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            DimensionWatch.reset(); EventLog.reset(); TestDriver.reset(); avoidanceApplied = false;
            return;
        }
        if (!isActiveAccount(mc)) {
            return;
        }
        if (mc.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen) {
            RespawnController.tick(mc);
            return;
        }
        BotActions.tick(mc);
        HomeZone.tick(mc);
        PortalController.tick(mc);
        DimensionWatch.tick(mc);
        MiningController.tick(mc);
        DigController.tick(mc);
        PlacedBlocks.tick(mc);
        ContainerController.tick(mc);
        ArmorController.tick(mc, CompanionConfig.get());
        LoginController.tick(mc, CompanionConfig.get());
        GiveController.tick(mc, CompanionConfig.get());
        StorageController.tick(mc);
        InteractController.tick(mc);
        StationController.tick(mc);
        EnchantController.tick(mc);
        EnchantPicker.tick(mc);
        FleeController.tick(mc, CompanionConfig.get());
        TaskTracker.tick(mc);
        PlanRunner.tick(mc); ActionRegistry.tickAll(PlanRunner.anyBusy()); AcquireTask.tick(mc); Butler.tick(mc, CompanionConfig.get()); Memory.flushIfDirty(); EventLog.watchHealth(mc); TestDriver.tick(mc); BlockInController.tick(mc, CompanionConfig.get()); PlacementController.tick(mc); if (!avoidanceApplied && CompanionConfig.get().useBaritoneAvoidance && BaritoneBridge.available()) { avoidanceApplied = true; BaritoneBridge.applyAvoidance(CompanionConfig.get().mobAvoidanceRadius, CompanionConfig.get().mobAvoidanceCoefficient); }
        CompanionConfig cfg = CompanionConfig.get();
        PriorityMode.update(mc, cfg);
        long now = System.currentTimeMillis();

        if (cfg.enableAutoEat && now - lastEatAt > 1500
                && mc.player.getHungerManager().getFoodLevel() <= 16) {
            if (BotActions.eat(mc)) {
                lastEatAt = now;
            }
        }

        if (cfg.enableAutoFight) {
            CombatController.tick(mc, cfg, now);
        }

        if (cfg.enableAutoFollow && BaritoneBridge.available() && !MiningController.isBusy()
                && !PlanRunner.isPlanning()
                && !PortalController.isBusy()
                && !GiveController.isBusy() && !FleeController.isFleeing()
                && !CombatController.isRetreating() && !CombatController.isEngaged(now)
                && !PriorityMode.isFight()
                && now - lastFollowCmd > 6000) {
            PlayerEntity owner = GameSnapshot.findOwner(mc, cfg);
            if (owner != null) {
                double distanceSq = owner.squaredDistanceTo(mc.player);
                boolean tooFar = distanceSq > (double) cfg.followDistance * cfg.followDistance;
                boolean busy = TaskTracker.isBusy() || AcquireTask.isBusy();
                boolean emergency = cfg.abandonTaskDistance > 0
                        && distanceSq > (double) cfg.abandonTaskDistance * cfg.abandonTaskDistance;
                if (tooFar && (!busy || emergency)) {
                    if (BaritoneBridge.followPlayer(owner.getName().getString())) {
                        lastFollowCmd = now;
                        TaskTracker.clear();
                    }
                }
            }
        }

        if (cfg.enableAutoFollow && cfg.ownerName != null && !cfg.ownerName.isBlank()
                && !AcquireTask.isBusy() && !TaskTracker.isBusy()
                && now - lastOwnerAsk > 60_000L && GameSnapshot.findOwner(mc, cfg) == null) {
            lastOwnerAsk = now;
            tell(pick("你在哪？我找不到你了", "报个坐标，我过去找你", "看不着你了，你在哪片？"));
        }

        if (cfg.enableAutonomous && !pending && cfg.hasKey() && !PlanRunner.isPlanning()
                && now - lastAutonomousAt > cfg.autonomousIntervalSeconds * 1000L) {
            lastAutonomousAt = now;
            queryDepth = 0;
            ask("（没人说话，自己看看局势决定下一步）", true, null);
        }

        if (cfg.enableProactiveChat) {
            maybeProactive(mc, cfg, now);
        }
    }

    // ------------------------------------------------------- proactive chat

    /** Fired when the owner's advancement broadcast shows up in chat. */
    public static void onOwnerAdvancement(String title) {
        long now = System.currentTimeMillis();
        if (now - lastAdvancementAt < 8000L) {
            return;
        }
        lastAdvancementAt = now;
        CompanionConfig cfg = CompanionConfig.get();
        if (pending) {
            return;
        }
        String canned = pick("「" + title + "」可以啊！", "这个进度我记下了", "牛，这成就不好拿的",
                "「" + title + "」干得漂亮", "有你在就是不一样");
        if (!cfg.hasKey()) {
            say(canned);
            return;
        }
        pending = true;
        MinecraftClient mc = MinecraftClient.getInstance();
        String system = Prompts.system(GameSnapshot.build(mc), true)
                + "\n这次只是对成就做出反应：act 必须是 idle，不要执行动作。";
        String user = "主人刚达成了进度「" + title + "」，用一句队友式的口语反应一下，不超过 30 字。";
        LlmClient.complete(system, LlmClient.trim(new ArrayList<>(HISTORY), MAX_HISTORY), user)
                .whenComplete((reply, error) -> mc.execute(() -> {
                    pending = false;
                    if (error != null || reply == null || reply.isBlank()) {
                        say(canned);
                        return;
                    }
                    try {
                        Intent intent = Intent.parse(reply);
                        if (!intent.say().isBlank()) {
                            say(intent.say());
                        }
                    } catch (ActionValidator.FormatException e) {
                        say(canned);
                    }
                }));
    }

    private static String pick(String... options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private static void maybeProactive(MinecraftClient mc, CompanionConfig cfg, long now) {
        if (pending) {
            return;
        }
        // never talk over an active conversation
        if (now - lastPlayerAt < 20_000 || now - lastBotSayAt < 25_000) {
            return;
        }
        if (nextProactiveAt == 0) {
            nextProactiveAt = now + proactiveDelay(cfg);
            return;
        }
        if (now < nextProactiveAt) {
            return;
        }
        nextProactiveAt = now + proactiveDelay(cfg);

        PlayerEntity owner = GameSnapshot.findOwner(mc, cfg);
        if (owner == null || owner.squaredDistanceTo(mc.player) > 32 * 32) {
            return; // only chat when it is actually nearby
        }
        boolean useModel = cfg.proactiveUseModel && cfg.hasKey()
                && ThreadLocalRandom.current().nextInt(100) < 70;
        if (useModel) {
            askProactive(mc, cfg, owner);
        } else {
            String line = localLine(mc);
            if (line != null) {
                say(line);
            }
        }
    }

    private static long proactiveDelay(CompanionConfig cfg) {
        int min = Math.max(30, cfg.proactiveMinSeconds);
        int max = Math.max(min + 30, cfg.proactiveMaxSeconds);
        return (min + ThreadLocalRandom.current().nextInt(max - min + 1)) * 1000L;
    }

    /** Model-phrased small talk. Actions are never taken from this path. */
    private static void askProactive(MinecraftClient mc, CompanionConfig cfg, PlayerEntity owner) {
        if (pending) {
            return;
        }
        pending = true;
        String system = Prompts.system(GameSnapshot.build(mc), true)
                + "\n这次你只是搭话：act 必须是 idle，不要执行任何动作。玩家叫 " + owner.getName().getString() + "。";
        String user = "主动跟玩家说一句话。不要重复这些你之前说过的：" + recentText();
        LlmClient.complete(system, LlmClient.trim(new ArrayList<>(HISTORY), MAX_HISTORY), user)
                .whenComplete((reply, error) -> mc.execute(() -> {
                    pending = false;
                    if (error != null || reply == null || reply.isBlank()) {
                        String fallback = localLine(mc);
                        if (fallback != null) {
                            say(fallback);
                        }
                        return;
                    }
                    try {
                        Intent intent = Intent.parse(reply);
                        String text = intent.say();
                        if (!text.isBlank() && !RECENT_SAYS.contains(text)) {
                            say(text);
                        }
                    } catch (ActionValidator.FormatException e) {
                        String fallback = localLine(mc);
                        if (fallback != null) {
                            say(fallback);
                        }
                    }
                }));
    }

    private static String recentText() {
        return RECENT_SAYS.isEmpty() ? "（还没聊过）" : String.join(" / ", RECENT_SAYS);
    }

    /** Cheap, contextual lines that need no API call. */
    private static String localLine(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        long dayTime = mc.world.getTimeOfDay() % 24000L;
        String[] pool;
        if (dayTime >= 13000 && dayTime < 23000) {
            pool = new String[]{"天黑了，你带床了吗？", "晚上别乱跑，有怪。", "要不要先回屋？"};
        } else if (mc.player.getHealth() < 8.0f) {
            pool = new String[]{"你血不多了，先吃点东西。", "你这血线太危险了。"};
        } else if (mc.player.getHungerManager().getFoodLevel() < 12) {
            pool = new String[]{"你饿了，我这儿有吃的。", "先吃点东西再干活。"};
        } else if (BotActions.nearestHostile(mc, 10) != null) {
            pool = new String[]{"附近有怪，别走神。", "我盯着呢，有怪就喊我。"};
        } else if (mc.world.isRaining()) {
            pool = new String[]{"这雨下得没完。", "下雨了，火把省着点。"};
        } else {
            pool = new String[]{"你挖到啥好货了？", "这地方挺适合安家的。", "累了就歇会儿，我帮你看着。", "要不咱往东边探探？"};
        }
        for (String candidate : pool) {
            if (!RECENT_SAYS.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static String statusLine() {
        long now = System.currentTimeMillis();
        long in = nextProactiveAt <= 0 ? -1 : Math.max(0, nextProactiveAt - now) / 1000L;
        String task = TaskTracker.isBusy()
                ? TaskTracker.type() + "(" + TaskTracker.elapsedSeconds() + "s)"
                : "-";
        return "pending=" + pending + " lastAct=" + (lastAct.isEmpty() ? "-" : lastAct)
                + " 任务=" + task + " 优先=" + PriorityMode.name()
                + " 计划=" + (PlanRunner.isPlanning() ? PlanRunner.status() : "-")
                + " 下次搭话=" + (in < 0 ? "未排期" : in + "s");
    }
}

