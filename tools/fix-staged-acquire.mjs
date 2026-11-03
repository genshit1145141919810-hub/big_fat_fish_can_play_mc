// Staged acquisition: a target that needs better tools is split into milestones
// (wooden pickaxe -> stone pickaxe -> iron pickaxe). Each milestone is planned and run
// on its own from the *current* inventory, so a failure only costs one stage.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/AcquireTask.java';
let s = readFileSync(p, 'utf8');
const out = [];

// 1) stage state + prerequisite table
if (!s.includes('stagesFor')) {
  s = s.replace(
    '    private static final List<List<Step>> CANDIDATES = new ArrayList<>();',
    `    /** 里程碑队列：铁镐 = 木镐 → 石镐 → 铁镐，每个阶段独立规划。 */
    private static final Deque<String> STAGES = new ArrayDeque<>();
    private static String finalGoal = "";

    private static final List<List<Step>> CANDIDATES = new ArrayList<>();`
  );
  s = s.replace(
    '    private AcquireTask() {\n    }',
    `    private AcquireTask() {
    }

    /** 目标需要的工具里程碑（只写"没有就补"的，顺序即执行顺序）。 */
    private static List<String> stagesFor(String target) {
        List<String> chain = new ArrayList<>();
        if (target.startsWith("iron_") || target.equals("furnace") || target.equals("bucket")
                || target.startsWith("shears") || target.equals("flint_and_steel")) {
            chain.add("wooden_pickaxe");
            chain.add("stone_pickaxe");
        } else if (target.startsWith("diamond_") || target.equals("enchanting_table")
                || target.equals("anvil") || target.equals("obsidian")) {
            chain.add("wooden_pickaxe");
            chain.add("stone_pickaxe");
            chain.add("iron_pickaxe");
        } else if (target.startsWith("stone_")) {
            chain.add("wooden_pickaxe");
        }
        chain.remove(target);
        chain.removeIf(item -> count(MinecraftClient.getInstance(), item) > 0);
        return chain;
    }`
  );
  out.push('ok stages table');
}

// 2) start(): build the stage queue, then begin the first stage
if (!s.includes('beginStage')) {
  s = s.replace(
    `        CANDIDATES.clear();
        NOTES.clear();
        PATH.clear();
        SIM.clear();
        goalItem = id;
        goalCount = Math.max(1, count);`,
    `        CANDIDATES.clear();
        NOTES.clear();
        PATH.clear();
        SIM.clear();
        STAGES.clear();
        finalGoal = id;
        STAGES.addAll(stagesFor(id));
        STAGES.add(id);
        goalCount = Math.max(1, count);
        return beginStage(mc);`
  );
  out.push('ok start stages');
}

// 3) beginStage(): plan the current stage only
if (!s.includes('private static boolean beginStage')) {
  s = s.replace(
    '    private static void buildCandidates(String item, int need) {',
    `    private static boolean beginStage(MinecraftClient mc) {
        String stage = STAGES.poll();
        if (stage == null) {
            finish(mc);
            return true;
        }
        goalItem = stage;
        candidateIndex = 0;
        CANDIDATES.clear();
        NOTES.clear();
        PATH.clear();
        SIM.clear();
        int before = count(mc, stage);
        buildCandidates(stage, 1);
        if (CANDIDATES.isEmpty()) {
            if (before > 0) {
                return beginStage(mc); // 已经有这个里程碑，跳到下一个阶段
            }
            String why = NOTES.isEmpty() ? "没有可行路线" : NOTES.get(NOTES.size() - 1);
            DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire stage {} failed: {}", stage, why);
            Memory.noteFailure("获取 " + stage, why);
            EventLog.note("获取失败", stage + "：" + why);
            BotBrain.tell("弄不到 " + stage + "（" + why + "），整条链走不下去");
            return false;
        }
        loadCandidate();
        startedAt = System.currentTimeMillis();
        running = true;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire stage {} (have {}) - {} route(s): {}",
                stage, before, CANDIDATES.size(), PLAN);
        return true;
    }

    private static void buildCandidates(String item, int need) {`
  );
  out.push('ok beginStage');
}

// 4) finish(): next stage, or really finish
s = s.replace(
  `    private static void finish(MinecraftClient mc) {
        running = false;
        int have = count(mc, goalItem);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire finished: {} (now {}, route {})",
                goalItem, have, candidateIndex + 1);
        EventLog.note("获取完成", goalItem + " 现在 " + have + " 个");
        BotBrain.tell("弄完了：" + goalItem + "（" + have + " 个）");
    }`,
  `    private static void finish(MinecraftClient mc) {
        int have = count(mc, goalItem);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire stage done: {} (now {})", goalItem, have);
        if (!STAGES.isEmpty()) {
            beginStage(mc);
            return;
        }
        running = false;
        int total = count(mc, finalGoal);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire finished: {} (now {})", finalGoal, total);
        EventLog.note("获取完成", finalGoal + " 现在 " + total + " 个");
        BotBrain.tell("弄完了：" + finalGoal + "（" + total + " 个）");
    }`
);

// 5) fail(): only this stage dies; report which stage
s = s.replace(
  '        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire failed: {} ({})", goalItem, reason);',
  '        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire stage failed: {} ({})", goalItem, reason);'
);

writeFileSync(p, s, 'utf8');
console.log(out.join('\n'));
