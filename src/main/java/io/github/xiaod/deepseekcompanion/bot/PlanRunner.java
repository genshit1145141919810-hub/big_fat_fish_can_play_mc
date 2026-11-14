package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.chat.Intent;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.client.MinecraftClient;

/**
 * 计划执行器：把 {@link Plan} 一步一步喂给 {@link ActionExecutor}，并判断每步做完了没有。
 *
 * <p>这是"MC 本土 agent"的执行半身，另一半是模型侧的规划（{@link io.github.xiaod.deepseekcompanion.chat.Prompts#plan}）。
 * 分工刻意这么切：<b>模型只负责拆步骤，执行/判定/重规划全在本地</b>。所以模型不能说
 * "然后看着办"——它给出的必须是合法的动作序列，而"做没做完"由客户端的控制器状态判定。</p>
 *
 * <p>判定依据是各个控制器的 {@code isBusy()}：挖掘、合成、存储、交互、站点、附魔、给予、进食、
 * 打猎各自有忙闲信号，全都闲下来才算一步真的结束；{@code goto} 则额外用"到没到坐标"判定。</p>
 */
public final class PlanRunner {
    private static final long MIN_STEP_MS = 1500L;

    private static Plan plan;
    private static String goal = "";
    private static int index;
    private static int replans;
    private static long stepStartedAt;
    private static boolean stepExecuted;
    private static boolean waitingModel;
    private static boolean finished;
    /** 每次 stop/finish 自增：用来丢弃"喊停之前发出去、现在才回来"的模型回包。 */
    private static int epoch;

    public static int epoch() {
        return epoch;
    }

    private PlanRunner() {
    }

    public static boolean isPlanning() {
        return plan != null && !finished;
    }

    public static boolean isWaitingModel() {
        return waitingModel;
    }

    /** 当前计划的目标；没在跑计划时为空串。 */
    public static String goalText() {
        return plan == null ? "" : goal;
    }

    public static String status() {
        if (plan == null || finished) {
            return "没有在跑计划";
        }
        if (waitingModel) {
            return "计划卡住，等模型改计划（第 " + (index + 1) + " 步：" + plan.steps().get(index) + "）";
        }
        return "计划 " + (index + 1) + "/" + plan.steps().size() + "：" + goal
                + "（当前 " + plan.steps().get(index) + "，重规划 " + replans + " 次）";
    }

    public static void start(String goalText, Plan newPlan) {
        plan = newPlan;
        goal = goalText == null ? "" : goalText;
        index = 0;
        replans = 0;
        stepExecuted = false;
        waitingModel = false;
        finished = false;
        stepStartedAt = System.currentTimeMillis();
        DeepseekCompanionClient.LOGGER.info("[XiaoD] plan start: {} | {}", goal, newPlan.describe());
    }

    public static void stop(String reason) {
        epoch++;
        if (plan == null) {
            return;
        }
        DeepseekCompanionClient.LOGGER.info("[XiaoD] plan cancelled: {}", reason);
        BaritoneBridge.stop();
        MiningController.cancel(MinecraftClient.getInstance());
        TaskTracker.clear();
        plan = null;
        index = 0;
        waitingModel = false;
        stepExecuted = false;
        finished = false;
    }

    public static void onReplan(Plan replacement) {
        if (plan == null) {
            return;
        }
        plan = replacement;
        index = 0;
        stepExecuted = false;
        stepStartedAt = System.currentTimeMillis();
        waitingModel = false;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] plan replaced: {}", replacement.describe());
    }

    public static void onReplanFailed(String reason) {
        waitingModel = false;
        finish("计划改不出来（" + reason + "）");
    }

    public static void tick(MinecraftClient mc) {
        if (plan == null || finished) {
            return;
        }
        if (mc.player == null || mc.world == null) {
            plan = null;
            return;
        }
        if (waitingModel) {
            return;
        }
        if (index >= plan.steps().size()) {
            finish(null);
            return;
        }
        long now = System.currentTimeMillis();
        Plan.Step step = plan.steps().get(index);
        if (!stepExecuted) {
            // 保命优先：逃跑/撤退时不推进计划，也不开始新步骤
            if (FleeController.isFleeing() || CombatController.isRetreating()) {
                return;
            }
            if (anyBusy()) {
                return;
            }
            ActionExecutor.Result result = ActionExecutor.execute(mc, new Intent("", step.action(), step.arg()));
            stepExecuted = true;
            stepStartedAt = now;
            DeepseekCompanionClient.LOGGER.info("[XiaoD] plan {}/{} {} ok={}",
                    index + 1, plan.steps().size(), step, result.ok());
            if (!result.ok()) {
                fail(mc, "第 " + (index + 1) + " 步「" + step + "」没执行起来");
                return;
            }
            if (instant(step.action())) {
                advance();
            }
            return;
        }
        // 走门失败：控制器已经清空，靠信号区分"成功传送"和"没走成"（必须早于 done 判定）
        if (step.action() == BotAction.PORTAL && !PortalController.isBusy()
                && PortalController.consumeFailure()) {
            fail(mc, "第 " + (index + 1) + " 步「" + step + "」没走成（附近没门或者没传过去）");
            return;
        }
        if (stepDone(mc, step, now)) {
            advance();
            return;
        }
        int timeoutSeconds = Math.max(15, CompanionConfig.get().planStepTimeoutSeconds);
        // 挖矿/砍树/赶路本来就慢：按 3 倍宽限，别 90 秒就把还在干活的步骤判死
        long limitMs = switch (step.action()) {
            case MINE, CHOP, GOTO -> timeoutSeconds * 3000L;
            default -> timeoutSeconds * 1000L;
        };
        if (now - stepStartedAt > limitMs && !FleeController.isFleeing()) {
            fail(mc, "第 " + (index + 1) + " 步「" + step + "」" + (limitMs / 1000L) + " 秒没完成");
        }
    }

    private static void advance() {
        index++;
        stepExecuted = false;
        stepStartedAt = System.currentTimeMillis();
        if (plan != null && index >= plan.steps().size()) {
            finish(null);
        }
    }

    private static void fail(MinecraftClient mc, String reason) {
        int max = Math.max(1, CompanionConfig.get().planMaxReplans);
        replans++;
        if (replans > max) {
            finish(reason);
            return;
        }
        Perception.note("计划失败：" + reason);
        EventLog.note("任务失败", goal + " → " + reason);
        io.github.xiaod.deepseekcompanion.bot.Memory.noteFailure(goal, reason);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] plan replan #{}/{}: {}", replans, max, reason);
        waitingModel = true;
        BotBrain.requestReplan(goal, observation(mc, reason), reason);
    }

    private static void finish(String problem) {
        if (plan == null && problem == null) {
            return;
        }
        epoch++; // 之后回来的模型回包一律作废，别让它自己续上
        finished = true;
        if (problem != null) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] plan failed: {}", problem);
            BotBrain.tell("这单我搞不定：" + problem);
        } else {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] plan done: {}", goal);
            BotBrain.say("搞定：" + goal);
        }
        plan = null;
        index = 0;
        waitingModel = false;
        stepExecuted = false;
        finished = false;
    }

    private static String observation(MinecraftClient mc, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标：").append(goal).append('\n');
        sb.append("失败原因：").append(reason).append('\n');
        if (plan != null) {
            sb.append("原计划：").append(plan.describe()).append('\n');
            if (index > 0) {
                sb.append("已完成的步骤：");
                for (int i = 0; i < Math.min(index, plan.steps().size()); i++) {
                    if (i > 0) {
                        sb.append("、");
                    }
                    sb.append(plan.steps().get(i));
                }
                sb.append('\n');
            }
        }
        sb.append("当前状态：").append(GameSnapshot.build(mc));
        return sb.toString();
    }

    // -------------------------------------------------------------- 判定

    /** 发出去就算完成的动作（持续型指令）。 */
    private static boolean instant(BotAction action) {
        return switch (action) {
            case FOLLOW, COME, STOP, IDLE -> true;
            default -> false;
        };
    }

    private static boolean stepDone(MinecraftClient mc, Plan.Step step, long now) {
        long elapsed = now - stepStartedAt;
        switch (step.action()) {
            case GOTO -> {
                if (arrived(mc, step.arg())) {
                    return true;
                }
                // Baritone 不走了也算结束（走不过去/被挡住）
                return elapsed > 4000L && !TaskTracker.isBusy();
            }
            case MINE, CHOP -> {
                return elapsed > MIN_STEP_MS && !MiningController.isBusy() && !TaskTracker.isBusy();
            }
            case CRAFT, COOK -> {
                return elapsed > MIN_STEP_MS && !ContainerController.isBusy();
            }
            case STORE, TAKE -> {
                return elapsed > MIN_STEP_MS && !StorageController.isBusy();
            }
            case HOLD, USE, BREW -> {
                return elapsed > MIN_STEP_MS && !InteractController.isBusy();
            }
            case STATION -> {
                return elapsed > MIN_STEP_MS && !StationController.isBusy();
            }
            case ENCHANT -> {
                return elapsed > MIN_STEP_MS && !EnchantController.isBusy();
            }
            case ENCHANT_BEST -> {
                return elapsed > MIN_STEP_MS && !EnchantPicker.isBusy();
            }
            case GIVE -> {
                return elapsed > MIN_STEP_MS && !GiveController.isBusy();
            }
            case EAT -> {
                return elapsed > 800L && !BotActions.isEating();
            }
            case HUNT -> {
                return elapsed > 800L && !CombatController.isHunting();
            }
            case FIGHT -> {
                return elapsed > 800L && !CombatController.isEngaged(now);
            }
            case PORTAL -> {
                // 传送成功时控制器自己已经清空，这里只等它结束
                return elapsed > 800L && !PortalController.isBusy();
            }
            case PLACE -> {
                return elapsed > 600L;
            }
            default -> {
                return elapsed > MIN_STEP_MS && !anyBusy();
            }
        }
    }

    private static boolean arrived(MinecraftClient mc, String arg) {
        if (mc.player == null || arg == null || arg.isBlank()) {
            return false;
        }
        String[] parts = arg.strip().split("\\s+");
        if (parts.length != 3) {
            return false;
        }
        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            double dx = mc.player.getX() - x;
            double dy = mc.player.getY() - y;
            double dz = mc.player.getZ() - z;
            return dx * dx + dz * dz <= 16.0 && Math.abs(dy) <= 4.0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 任一控制器在忙，就不该开始下一步。 */
    public static boolean anyBusy() {
        return AcquireTask.isBusy()
                || BlockInController.isBusy()
                || MiningController.isBusy()
                || ContainerController.isBusy()
                || StorageController.isBusy()
                || InteractController.isBusy()
                || StationController.isBusy()
                || EnchantController.isBusy()
                || EnchantPicker.isBusy()
                || GiveController.isBusy()
                || PortalController.isBusy()
                || TaskTracker.isBusy()
                || BotActions.isEating()
                || CombatController.isHunting();
    }

    /**
     * 换维度了：旧坐标全废。
     * 如果当前步就是走门，那这是成功信号，正常推进；否则算这一步失败并触发重规划。
     */
    public static void onDimensionChanged(String from, String to) {
        if (plan == null || finished || waitingModel) {
            return;
        }
        Plan.Step step = index < plan.steps().size() ? plan.steps().get(index) : null;
        if (step != null && step.action() == BotAction.PORTAL) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] plan: portal step completed ({} -> {})", from, to);
            advance();
            return;
        }
        fail(net.minecraft.client.MinecraftClient.getInstance(),
                "维度从 " + shortDim(from) + " 变成了 " + shortDim(to) + "，原计划的坐标失效了");
    }

    private static String shortDim(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return "?";
        }
        int colon = dimension.indexOf(':');
        return colon < 0 ? dimension : dimension.substring(colon + 1);
    }
}
