package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.integrations.CraftRecipes;
import io.github.xiaod.deepseekcompanion.integrations.SmeltRecipes;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recursive acquisition with exhaustive fallback.
 *
 * The planner emits several candidate routes (cheapest recipe first, then the alternatives,
 * then mining/hunting). The runner executes one route; when a step fails or produces nothing
 * the route is discarded and the next one is tried. Acquisition only fails when all routes
 * are exhausted.
 */
public final class AcquireTask {
    private static final int MAX_STEPS = 48;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_CANDIDATES = 4;
    private static final int MAX_REPLANS = 3;
    private static final int PLACE_ATTEMPTS = 3;
    private static final long STEP_TIMEOUT_MS = 60_000L;
    private static final long TOTAL_TIMEOUT_MS = 600_000L;
    /** 补挖时的冗余比例：少捡到一个就多挖一半，别正好卡在数量上。 */
    private static final double REDUNDANCY = 0.5;
    /** 一开始就多挖的比例：人挖木头也不会掐着数挖。 */
    private static final double MINE_ESTIMATE = 0.5;
    /** 硬性下限：木头一次至少挖够这么多，别抠着算。 */
    private static final int WOOD_FLOOR = 10;

    private enum Op { CRAFT, SMELT, MINE, HUNT, PLACE, DIG }

    private record Step(Op op, String item, String arg, int count) {
        @Override
        public String toString() {
            return op + ":" + item + (arg == null || arg.isEmpty() ? "" : "(" + arg + ")") + (count > 1 ? "x" + count : "");
        }
    }

    private static final Map<String, String> TOOL_TIER = new LinkedHashMap<>();
    private static final Map<String, String> ITEM_TO_BLOCK = new LinkedHashMap<>();
    private static final Map<String, String> ITEM_TO_MOB = new LinkedHashMap<>();
    private static final Map<String, Integer> COST = new LinkedHashMap<>();
    /** 标签里挑物料时的常识顺序：先常见、再冷门。 */
    private static final java.util.List<String> COMMON_PREF = java.util.List.of(
            "cobblestone", "stone", "oak_planks", "oak_log", "stick", "coal", "iron_ingot",
            "iron_ore", "raw_iron", "spruce_planks", "birch_planks", "crafting_table");

    /** 挖方块掉不出来的东西（没有精准采集），只能烧出来。 */
    private static final Set<String> SMELT_ONLY = Set.of("stone", "glass");
    /** 压缩块（铁块、金块…）不是矿源：它们在世上一块块摆着，但来源是合成。 */
    private static final Set<String> STORAGE_BLOCKS = Set.of(
            "iron_block", "gold_block", "diamond_block", "emerald_block", "redstone_block",
            "lapis_block", "coal_block", "copper_block", "netherite_block", "raw_iron_block",
            "raw_copper_block", "raw_gold_block");

    static {
        for (String b : List.of("stone", "cobblestone", "coal_ore", "deepslate_coal_ore",
                "nether_quartz_ore", "deepslate", "tuff", "andesite", "diorite", "granite")) {
            TOOL_TIER.put(b, "wooden_pickaxe");
        }
        for (String b : List.of("iron_ore", "copper_ore", "lapis_ore", "deepslate_iron_ore",
                "deepslate_copper_ore", "deepslate_lapis_ore")) {
            TOOL_TIER.put(b, "stone_pickaxe");
        }
        for (String b : List.of("gold_ore", "redstone_ore", "diamond_ore", "emerald_ore",
                "deepslate_gold_ore", "deepslate_redstone_ore", "deepslate_diamond_ore",
                "deepslate_emerald_ore")) {
            TOOL_TIER.put(b, "iron_pickaxe");
        }
        TOOL_TIER.put("obsidian", "diamond_pickaxe");
        TOOL_TIER.put("ancient_debris", "diamond_pickaxe");

        ITEM_TO_BLOCK.put("cobblestone", "stone");
        ITEM_TO_BLOCK.put("raw_iron", "iron_ore");
        ITEM_TO_BLOCK.put("raw_gold", "gold_ore");
        ITEM_TO_BLOCK.put("raw_copper", "copper_ore");
        ITEM_TO_BLOCK.put("coal", "coal_ore");
        ITEM_TO_BLOCK.put("diamond", "diamond_ore");
        ITEM_TO_BLOCK.put("emerald", "emerald_ore");
        ITEM_TO_BLOCK.put("redstone", "redstone_ore");
        ITEM_TO_BLOCK.put("lapis_lazuli", "lapis_ore");


        ITEM_TO_MOB.put("beef", "cow");
        ITEM_TO_MOB.put("leather", "cow");
        ITEM_TO_MOB.put("porkchop", "pig");
        ITEM_TO_MOB.put("chicken", "chicken");
        ITEM_TO_MOB.put("feather", "chicken");
        ITEM_TO_MOB.put("mutton", "sheep");
        ITEM_TO_MOB.put("rabbit", "rabbit");
        ITEM_TO_MOB.put("rotten_flesh", "zombie");
        ITEM_TO_MOB.put("bone", "skeleton");
        ITEM_TO_MOB.put("string", "spider");
        ITEM_TO_MOB.put("gunpowder", "creeper");
        ITEM_TO_MOB.put("slime_ball", "slime");

        COST.put("oak_log", 2);
        COST.put("spruce_log", 2);
        COST.put("birch_log", 2);
        COST.put("oak_planks", 3);
        COST.put("spruce_planks", 3);
        COST.put("birch_planks", 3);
        COST.put("stick", 4);
        COST.put("crafting_table", 4);
        COST.put("stone", 4);
        COST.put("cobblestone", 4);
        COST.put("coal", 5);
        COST.put("raw_iron", 7);
        COST.put("iron_ore", 7);
        COST.put("iron_ingot", 8);
    }

    /** 里程碑队列：铁镐 = 木镐 → 石镐 → 铁镐，每个阶段独立规划。 */
    private static final Deque<String> STAGES = new ArrayDeque<>();
    private static String finalGoal = "";

    private static final List<List<Step>> CANDIDATES = new ArrayList<>();
    private static final Deque<Step> PLAN = new ArrayDeque<>();
    private static final List<String> NOTES = new ArrayList<>();
    private static final Set<String> PATH = new LinkedHashSet<>();

    /**
     * 需求图：每种东西"从哪来"只决定一次，数量在整张图上合并。
     * 这样挖木头就是一次挖够，而不会挖一根、合成一次、再挖一根。
     */
    private record Prod(Op op, String arg, String extra, CraftRecipes.Recipe recipe,
                        boolean needsTable, boolean needsFurnace) {
    }

    private static final Map<String, Prod> STRUCT = new LinkedHashMap<>();
    private static final List<String> ORDER = new ArrayList<>();
    private static final Map<String, Integer> PRODUCE = new LinkedHashMap<>();
    private static final Set<String> EMITTED = new LinkedHashSet<>();
    private static boolean tablePlaced;
    private static boolean furnacePlaced;
    private static int candidateIndex;
    private static int placeAttempts;
    private static String goalItem = "";
    private static int goalCount;
    private static long startedAt;
    private static long stepSince;
    private static boolean running;
    private static boolean stepStarted;
    private static int baseline;
    private static int verifications;
    private static int mineRetries;
    private static int mineDryStreak;
    private static int replans;
    /** 同一份配料的其他候选（挖不到就换），按"离得近"排好。 */
    private static final Map<String, List<String>> MINE_ALTS = new LinkedHashMap<>();
    /** 规划过程的追踪，诊断用。 */
    private static final List<String> TRACE = new ArrayList<>();
    /** 这一步改用自己的挖矿（Baritone 找不到路时）。 */
    private static boolean selfMine;
    /** 深挖那一步：交给 Baritone 赶路到目标高度。 */
    private static int digTargetY;
    /** 当前这一步是交给 Baritone 挖的。 */
    private static boolean baritoneMine;
    private static int lastMineHave;
    private static double lastMineX;
    private static double lastMineY;
    private static double lastMineZ;
    private static long lastMineProgress;
    private static final long MINE_TIMEOUT_MS = 300_000L;
    /** 一次获取里挖矿最多花多久（挖到地下矿层要时间）。 */
    private static final long MINE_STALL_MS = 90_000L;
    private static final int MAX_MINE_RETRIES = 3;
    private static long verifyUntil;
    private static String statusText = "";

    /** 任务树：当前路线内已完成的步骤数、每步最后结果、失败历史。 */
    private static int doneSteps;
    private static final Map<String, String> STEP_RESULT = new LinkedHashMap<>();
    private static final java.util.List<String> HISTORY = new ArrayList<>();

    private AcquireTask() {
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
    }

    private static void noteHistory(String line) {
        HISTORY.add(line);
        while (HISTORY.size() > 12) {
            HISTORY.remove(0);
        }
    }

    /**
     * 任务树的只读快照，给 {@code .xd plan tree} 用。
     * 不改变任何状态，只把当前阶段/路线/每步状态摊平成几行文字。
     */
    public static List<String> treeLines() {
        List<String> lines = new ArrayList<>();
        int route = Math.max(1, candidateIndex + 1);
        int routes = Math.max(1, CANDIDATES.size());
        int stage = Math.max(1, route);
        int stages = STAGES.size() + stage;
        lines.add("目标 " + (finalGoal.isEmpty() ? "?" : finalGoal)
                + "  阶段 " + stage + "/" + stages
                + "  路线 " + route + "/" + routes
                + "  重算 " + replans + "/" + MAX_REPLANS);
        if (!running) {
            lines.add("（当前没有正在跑的获取任务）");
        } else {
            int index = 0;
            for (Step step : PLAN) {
                String mark = index < doneSteps ? "✔" : index == doneSteps ? "▶" : "·";
                String extra = STEP_RESULT.getOrDefault(step.toString(), "");
                String suffix = extra.isEmpty() || "✔".equals(extra) ? "" : "  " + extra;
                lines.add("  [" + mark + "] " + step + suffix);
                index++;
                if (index > 24) {
                    lines.add("  …（还有 " + (PLAN.size() - index) + " 步）");
                    break;
                }
            }
            if (!STAGES.isEmpty()) {
                lines.add("  后续里程碑: " + STAGES);
            }
        }
        if (!HISTORY.isEmpty()) {
            lines.add("最近:");
            for (int i = Math.max(0, HISTORY.size() - 4); i < HISTORY.size(); i++) {
                lines.add("  · " + HISTORY.get(i));
            }
        }
        return lines;
    }

    public static boolean isBusy() {
        return running;
    }

    /** 正在挖矿（已经把移动交给 Baritone 的 mine）：这期间任何 goto 都会把挖矿打断。 */
    /** 只算不算：把"这东西从哪来、缺什么"直接说出来，方便排查配方问题。 */
    public static String diagnose(String raw, int count) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return "还没进游戏";
        }
        if (running) {
            return "手上还有活，先 .xd stop 再查";
        }
        String id = normalize(raw);
        if (id.isEmpty()) {
            return "要说查什么";
        }
        CANDIDATES.clear();
        buildCandidates(id, Math.max(1, count));
        StringBuilder trace = new StringBuilder("来源：");
        for (Map.Entry<String, Prod> entry : STRUCT.entrySet()) {
            Prod prod = entry.getValue();
            trace.append(entry.getKey()).append("←").append(prod.op());
            if (prod.arg() != null && !prod.arg().isEmpty()) {
                trace.append("(").append(prod.arg()).append(")");
            }
            if (prod.extra() != null && !prod.extra().isEmpty()) {
                trace.append("+").append(prod.extra());
            }
            trace.append("; ");
        }
        StringBuilder sb = new StringBuilder("查 " + id + "：" + CANDIDATES.size() + " 条路线");
        for (List<Step> candidate : CANDIDATES) {
            sb.append(" | ").append(candidate);
        }
        if (!NOTES.isEmpty()) {
            sb.append(" | 说明：").append(String.join("；", NOTES));
        }
        sb.append(" | ").append(trace);
        if (!TRACE.isEmpty()) {
            sb.append(" | 追踪：").append(String.join("；", TRACE));
        }
        return sb.toString();
    }

    public static boolean isMining() {
        return running && (baritoneMine || (!BaritoneBridge.available() && MiningController.isBusy()));
    }

    public static String status() {
        return running ? statusText : "";
    }

    public static boolean start(MinecraftClient mc, String target, int count) {
        if (mc.player == null) {
            return false;
        }
        String id = normalize(target);
        if (id.isEmpty()) {
            BotBrain.tell("没说要弄什么");
            return false;
        }
        CANDIDATES.clear();
        NOTES.clear();
        TRACE.clear();
        PATH.clear();
        STAGES.clear();
        HISTORY.clear();
        STEP_RESULT.clear();
        doneSteps = 0;
        finalGoal = id;
        STAGES.addAll(stagesFor(id));
        STAGES.add(id);
        goalCount = Math.max(1, count);
        return beginStage(mc);
    }

    private static boolean beginStage(MinecraftClient mc) {
        String stage = STAGES.poll();
        if (stage == null) {
            finish(mc);
            return true;
        }
        goalItem = stage;
        candidateIndex = 0;
        replans = 0;
        mineDryStreak = 0;
        CANDIDATES.clear();
        NOTES.clear();
        PATH.clear();
        int before = count(mc, stage);
        int want = stageNeed();
        buildCandidates(stage, want);
        if (CANDIDATES.isEmpty()) {
            if (before > 0) {
                return beginStage(mc); // 已经有这个里程碑，跳到下一个阶段
            }
            String why = NOTES.isEmpty() ? "没有可行路线" : NOTES.get(NOTES.size() - 1);
            DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire notes: {}", NOTES);
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

    private static void buildCandidates(String item, int need) {
        List<CraftRecipes.Recipe> recipes = usefulRecipes(item);
        recipes.sort(Comparator.comparingInt(AcquireTask::recipeCost));
        for (CraftRecipes.Recipe recipe : recipes) {
            if (CANDIDATES.size() >= MAX_CANDIDATES) {
                break;
            }
            List<Step> plan = planGraph(item, need, recipe);
            if (plan != null) {
                CANDIDATES.add(List.copyOf(plan));
            }
        }
        if (CANDIDATES.isEmpty()) {
            List<Step> plan = planGraph(item, need, null);
            if (plan != null) {
                CANDIDATES.add(List.copyOf(plan));
            }
        }
    }

    /**
     * 先把整条链摊成一张需求图（每种东西"从哪来"只决定一次，数量在图上合并），
     * 再按依赖顺序一口气排完：挖木头是"一次挖够三根"，不是"挖一根合成一次"。
     * 返回 null 表示这条路线算不通，原因留在 {@link #NOTES}。
     */
    private static List<Step> planGraph(String item, int need, CraftRecipes.Recipe forcedTop) {
        STRUCT.clear();
        ORDER.clear();
        PRODUCE.clear();
        EMITTED.clear();
        NOTES.clear();
        PATH.clear();
        tablePlaced = false;
        furnacePlaced = false;
        if (!structure(item, 0, need, forcedTop)) {
            return null;
        }
        orderTop(item);
        if (!quantities(item, Math.max(1, need))) {
            return null;
        }
        List<Step> steps = new ArrayList<>();
        emit(item, steps);
        if (steps.isEmpty()) {
            NOTES.add("已经有 " + item + " 了");
            return null;
        }
        if (steps.size() > MAX_STEPS) {
            NOTES.add("步骤太多（" + steps.size() + " 步）");
            return null;
        }
        return coalesce(steps);
    }

    /** 决定一种东西怎么来：合成 → 挖 → 烧 → 打怪，先选的写进 STRUCT。 */
    /** 能用的配方：过滤掉"把压缩块拆开"这种伪来源（钻石块拆 9 个钻石之类）。 */
    private static List<CraftRecipes.Recipe> usefulRecipes(String item) {
        List<CraftRecipes.Recipe> out = new ArrayList<>();
        for (CraftRecipes.Recipe recipe : CraftRecipes.find(item)) {
            if (isDecompression(item, recipe)) {
                continue;
            }
            out.add(recipe);
        }
        out.sort(Comparator.comparingInt(AcquireTask::recipeCost));
        return out;
    }

    /** 只有一个材料、产量还大于 1，而且材料正好是"自己的压缩块"：这是拆包，不是来源。 */
    private static boolean isDecompression(String item, CraftRecipes.Recipe recipe) {
        int cells = 0;
        String only = null;
        for (String cell : recipe.cells()) {
            if (cell != null) {
                cells++;
                only = cell;
            }
        }
        if (cells != 1 || recipe.count() <= 1 || only == null) {
            return false;
        }
        String input = resolveToken(only);
        return input != null && input.equals(item + "_block");
    }

    private static boolean structure(String item, int depth, int need, CraftRecipes.Recipe forcedTop) {
        if (STRUCT.containsKey(item)) {
            return true;
        }
        if (PATH.contains(item)) {
            NOTES.add(item + " 绕回了自己");
            return false;
        }
        if (depth > MAX_DEPTH) {
            NOTES.add(item + " 套得太深");
            return false;
        }
        PATH.add(item);
        try {
            List<CraftRecipes.Recipe> recipes = usefulRecipes(item);
            // 挖一下就有、又不是合成品（钻石、煤、粗铁…）：直接挖，别绕去烧
            if (recipes.isEmpty()) {
                String direct = blockFor(item);
                if (direct != null && structureMine(item, direct, depth)) {
                    return true;
                }
            }
            // 得靠烧出来的（铁锭、木炭、玻璃…）：先挖矿再烧，而不是把压缩块拆开
            if (smeltFromMinable(item) && structureSmelt(item, depth, need)) {
                return true;
            }
            recipes.sort(Comparator.comparingInt(AcquireTask::recipeCost));
            for (CraftRecipes.Recipe recipe : recipes) {
                if (forcedTop != null && depth == 0 && !recipeKey(recipe).equals(recipeKey(forcedTop))) {
                    continue;
                }
                if (structureRecipe(item, recipe, depth)) {
                    return true;
                }
            }
            String block = blockFor(item);
            if (block != null && structureMine(item, block, depth)) {
                return true;
            }
            if (!SmeltRecipes.byOutput(item).isEmpty() && structureSmelt(item, depth, need)) {
                return true;
            }
            String mob = ITEM_TO_MOB.get(item);
            if (mob != null) {
                STRUCT.put(item, new Prod(Op.HUNT, mob, "", null, false, false));
                return true;
            }
            NOTES.add("不知道 " + item + " 从哪来");
            TRACE.add(item + "(深度" + depth + ")：无配方/不可挖/不可烧");
            return false;
        } finally {
            PATH.remove(item);
        }
    }

    private static boolean structureRecipe(String item, CraftRecipes.Recipe recipe, int depth) {
        for (String cell : recipe.cells()) {
            if (cell == null) {
                continue;
            }
            String dep = resolveToken(cell);
            if (dep == null) {
                NOTES.add(item + " 的配方里有认不出的材料");
                return false;
            }
            if (!structure(dep, depth + 1, 1, null)) {
                NOTES.add(item + " 缺 " + dep);
                return false;
            }
        }
        boolean needsTable = recipe.width() > 2 || recipe.height() > 2;
        if (needsTable && !STRUCT.containsKey("crafting_table")
                && !ContainerController.tableNearby(MinecraftClient.getInstance()) && count(MinecraftClient.getInstance(), "crafting_table") <= 0
                && !structure("crafting_table", depth + 1, 1, null)) {
            NOTES.add(item + " 要工作台，但做不出工作台");
            return false;
        }
        STRUCT.put(item, new Prod(Op.CRAFT, item, "", recipe, needsTable, false));
        return true;
    }

    private static boolean structureMine(String item, String block, int depth) {
        String tool = TOOL_TIER.get(block);
        if (tool != null && count(MinecraftClient.getInstance(), tool) <= 0
                && !structure(tool, depth + 1, 1, null)) {
            NOTES.add("挖 " + block + " 要 " + tool + "，弄不到");
            return false;
        }
        STRUCT.put(item, new Prod(Op.MINE, block, tool == null ? "" : tool, null, false, false));
        return true;
    }

    /** 这种物品能不能靠"挖一种直接能挖到的东西再烧"得到（铁锭←铁矿、木炭←原木…）。 */
    private static boolean smeltFromMinable(String item) {
        for (SmeltRecipes.Entry entry : SmeltRecipes.byOutput(item)) {
            String ore = resolveToken(entry.inputToken());
            if (ore != null && !ore.isEmpty() && !ore.equals(item) && blockFor(ore) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean structureSmelt(String item, int depth, int need) {
        boolean furnaceNear = ContainerController.furnaceNearby(MinecraftClient.getInstance());
        // 优先用"挖矿直接掉的那种"（raw_iron），矿块本身（iron_ore/deepslate_iron_ore）排后面：
        // 挖矿块只掉粗铁，所以只有粗铁真的能进背包
        List<SmeltRecipes.Entry> entries = new ArrayList<>(SmeltRecipes.byOutput(item));
        entries.sort((a, b) -> Integer.compare(smeltPreference(b), smeltPreference(a)));
        for (SmeltRecipes.Entry entry : entries) {
            String ore = resolveToken(entry.inputToken());
            if (ore == null || ore.isEmpty() || ore.equals(item)) {
                continue;
            }
            if (!furnaceNear && !structure("furnace", depth + 1, 1, null)) {
                NOTES.add(item + " 要熔炉，但做不出熔炉");
                continue;
            }
            if (!structure(ore, depth + 1, 1, null)) {
                NOTES.add(item + " 缺 " + ore);
                continue;
            }
            String fuel = pickFuel(depth, need);
            if (fuel == null) {
                NOTES.add(item + " 没有燃料");
                continue;
            }
            STRUCT.put(item, new Prod(Op.SMELT, ore, fuel, null, false, !furnaceNear));
            return true;
        }
        return false;
    }

    /** 越像"挖出来就到手的原料"分越高：raw_iron 高于 iron_ore。 */
    private static int smeltPreference(SmeltRecipes.Entry entry) {
        String ore = resolveToken(entry.inputToken());
        if (ore == null || ore.isEmpty()) {
            return 0;
        }
        int score = 0;
        if (!Registries.BLOCK.containsId(Identifier.of("minecraft", ore))) {
            score += 5; // 不是方块，说明是挖出来的掉落物
        }
        if (ore.startsWith("raw_")) {
            score += 3;
        }
        if (blockFor(ore) != null) {
            score += 1;
        }
        return score;
    }

    /** 燃料优先用木头（手上最常见），实在没有才去挖煤。 */
    private static String pickFuel(int depth, int need) {
        int n = Math.max(1, need);
        for (String fuel : List.of("oak_planks", "spruce_planks", "coal", "charcoal", "oak_log")) {
            if (count(MinecraftClient.getInstance(), fuel) >= n || structure(fuel, depth + 1, n, null)) {
                return fuel;
            }
        }
        return null;
    }

    private static void orderTop(String goal) {
        ORDER.clear();
        collect(goal);
    }

    private static void collect(String item) {
        if (ORDER.contains(item)) {
            return;
        }
        ORDER.add(item);
        Prod prod = STRUCT.get(item);
        if (prod == null) {
            return;
        }
        for (String dep : depsOf(prod)) {
            collect(dep);
        }
    }

    private static List<String> depsOf(Prod prod) {
        List<String> deps = new ArrayList<>();
        switch (prod.op()) {
            case CRAFT -> {
                for (String cell : prod.recipe().cells()) {
                    if (cell == null) {
                        continue;
                    }
                    String dep = resolveToken(cell);
                    if (dep != null && !deps.contains(dep)) {
                        deps.add(dep);
                    }
                }
                if (prod.needsTable()) {
                    deps.add("crafting_table");
                }
            }
            case SMELT -> {
                deps.add(prod.arg());
                if (prod.extra() != null && !prod.extra().isEmpty()) {
                    deps.add(prod.extra());
                }
                if (prod.needsFurnace()) {
                    deps.add("furnace");
                }
            }
            case MINE -> {
                if (prod.extra() != null && !prod.extra().isEmpty()) {
                    deps.add(prod.extra());
                }
            }
            default -> {
            }
        }
        return deps;
    }

    /** 自顶向下算数量：每个物品的总需求都落到"要做几个"，背包里已经有的先扣掉。 */
    /**
     * 算数量：同一样东西可能被好几个地方需要（木板既要做木棍又做工作台），
     * 所以反复迭代到需求不再变化，再定"每样要产几个"，而不是按遍历顺序一次算完。
     */
    private static boolean quantities(String goal, int need) {
        Map<String, Integer> want = new LinkedHashMap<>();
        want.put(goal, Math.max(1, need));
        Map<String, Integer> finalWant = want;
        for (int pass = 0; pass < 8; pass++) {
            Map<String, Integer> next = new LinkedHashMap<>();
            next.put(goal, Math.max(1, need));
            for (String item : ORDER) {
                int total = next.getOrDefault(item, 0);
                if (total <= 0) {
                    continue;
                }
                Prod prod = STRUCT.get(item);
                if (prod == null) {
                    NOTES.add("不知道 " + item + " 从哪来");
                    return false;
                }
                int missing = total - Math.min(countAny(MinecraftClient.getInstance(), item), total);
                if (missing <= 0) {
                    continue;
                }
                switch (prod.op()) {
                    case CRAFT -> {
                        CraftRecipes.Recipe recipe = prod.recipe();
                        int crafts = Math.max(1, (missing + recipe.count() - 1) / recipe.count());
                        for (String cell : recipe.cells()) {
                            if (cell == null) {
                                continue;
                            }
                            String dep = resolveToken(cell);
                            if (dep == null) {
                                NOTES.add(item + " 的配方里有认不出的材料");
                                return false;
                            }
                            next.merge(dep, crafts, Integer::sum);
                        }
                        if (prod.needsTable() && !ContainerController.tableNearby(MinecraftClient.getInstance())
                                && count(MinecraftClient.getInstance(), "crafting_table") <= 0) {
                            next.merge("crafting_table", 1, Integer::sum);
                        }
                    }
                    case SMELT -> {
                        next.merge(prod.arg(), missing, Integer::sum);
                        if (prod.extra() != null && !prod.extra().isEmpty()) {
                            next.merge(prod.extra(), missing, Integer::sum);
                        }
                        if (prod.needsFurnace()) {
                            next.merge("furnace", 1, Integer::sum);
                        }
                    }
                    default -> {
                    }
                }
            }
            boolean stable = next.equals(want);
            want = next;
            finalWant = next;
            if (stable) {
                break;
            }
        }
        // 需求定了，再算每样要产几个
        PRODUCE.clear();
        for (String item : ORDER) {
            int total = finalWant.getOrDefault(item, 0);
            if (total <= 0) {
                continue;
            }
            int missing = total - Math.min(countAny(MinecraftClient.getInstance(), item), total);
            if (missing > 0) {
                PRODUCE.put(item, missing);
            }
        }
        return true;
    }

    /** 依赖先做、自己后做；要工作台/熔炉的，顺手插一步放下。 */
    private static void emit(String item, List<Step> steps) {
        if (!EMITTED.add(item)) {
            return;
        }
        Prod prod = STRUCT.get(item);
        if (prod == null) {
            return;
        }
        for (String dep : depsOf(prod)) {
            emit(dep, steps);
        }
        int count = PRODUCE.getOrDefault(item, 0);
        if (count <= 0) {
            return;
        }
        switch (prod.op()) {
            case CRAFT -> {
                if (prod.needsTable() && !tablePlaced) {
                    // 附近已经有一张能用的台子就不用再放，放了也是浪费
                    if (!ContainerController.tableNearby(MinecraftClient.getInstance())) {
                        steps.add(new Step(Op.PLACE, "crafting_table", "crafting_table", 1));
                    }
                    tablePlaced = true;
                }
                int crafts = Math.max(1, (count + prod.recipe().count() - 1) / prod.recipe().count());
                steps.add(new Step(Op.CRAFT, item, "", crafts));
            }
            case SMELT -> {
                if (prod.needsFurnace() && !furnacePlaced) {
                    if (!ContainerController.furnaceNearby(MinecraftClient.getInstance())) {
                        steps.add(new Step(Op.PLACE, "furnace", "furnace", 1));
                    }
                    furnacePlaced = true;
                }
                steps.add(new Step(Op.SMELT, item, prod.arg(), count));
            }
            case MINE -> {
                // 钻石/红石：附近一块都没有时，才往下挖去找深板岩层；附近有就直接挖（洞里常有露天的）
                if (PREFER_DEEPSLATE.contains(item) && !anyFamilyBlockNearby(item)) {
                    steps.add(new Step(Op.DIG, item, "-54", 0));
                }
                // 人是估着挖的：按需要再宽一点，路上掉几个、漏捡几个也还有富余
                int buffer = Math.max(1, (int) Math.ceil(count * MINE_ESTIMATE));
                int total = count + buffer;
                if (isWood(item)) {
                    total = Math.max(total, WOOD_FLOOR);
                }
                steps.add(new Step(Op.MINE, item, prod.arg(), total));
            }
            case HUNT -> steps.add(new Step(Op.HUNT, item, prod.arg(), count));
        }
    }
    /** 合并相邻的同类步骤：挖三根木头算一步，合两次木板算一步。 */
    private static List<Step> coalesce(List<Step> plan) {
        List<Step> merged = new ArrayList<>();
        for (Step step : plan) {
            if (!merged.isEmpty()) {
                Step last = merged.get(merged.size() - 1);
                if (last.op() == step.op() && last.item().equals(step.item())
                        && (last.arg() == null ? "" : last.arg()).equals(step.arg() == null ? "" : step.arg())) {
                    merged.set(merged.size() - 1,
                            new Step(last.op(), last.item(), last.arg(), last.count() + step.count()));
                    continue;
                }
            }
            merged.add(step);
        }
        return merged;
    }

    /** 重新测算：拿现在的背包重排一次，只补真正还缺的东西。 */
    private static void replan(MinecraftClient mc, String reason) {
        if (++replans > MAX_REPLANS) {
            nextCandidate(mc, reason + "（重排了 " + MAX_REPLANS + " 次还是不够）");
            return;
        }
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire: 重新测算 {}（{}，第 {} 次）",
                goalItem, reason, replans);
        EventLog.note("重新测算", goalItem + "：" + reason);
        noteHistory("重新测算：" + reason);
        CANDIDATES.clear();
        buildCandidates(goalItem, stageNeed());
        if (CANDIDATES.isEmpty()) {
            nextCandidate(mc, reason);
            return;
        }
        candidateIndex = 0;
        loadCandidate();
    }

    private static int stageNeed() {
        return goalItem.equals(finalGoal) ? Math.max(1, goalCount) : 1;
    }

    private static void loadCandidate() {
        PLAN.clear();
        if (candidateIndex < CANDIDATES.size()) {
            PLAN.addAll(CANDIDATES.get(candidateIndex));
        }
        stepStarted = false;
        doneSteps = 0;
        selfMine = false;
        placeAttempts = 0;
        mineRetries = 0;
        mineDryStreak = 0;
        stepSince = System.currentTimeMillis();
        statusText = "获取 " + goalItem + "（路线 " + (candidateIndex + 1) + "/" + CANDIDATES.size() + "）";
    }

    private static void nextCandidate(MinecraftClient mc, String reason) {
        noteHistory("换路线：" + reason);
        if (!PLAN.isEmpty()) {
            STEP_RESULT.put(PLAN.peek().toString(), "✘ " + reason);
        }
        NOTES.add("路线 " + (candidateIndex + 1) + " 失败：" + reason);
        candidateIndex++;
        if (candidateIndex >= CANDIDATES.size()) {
            fail(mc, "所有路线都试过了（" + reason + "）");
            return;
        }
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire {}: route {} -> {}, reason: {}",
                goalItem, candidateIndex, candidateIndex + 1, reason);
        EventLog.note("换路线", goalItem + "：" + reason);
        loadCandidate();
    }

    public static void tick(MinecraftClient mc) {
        if (!running) {
            return;
        }
        if (mc.player == null || mc.world == null) {
            running = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - startedAt > TOTAL_TIMEOUT_MS) {
            fail(mc, "总时长超过 7 分钟");
            return;
        }
        if (PLAN.isEmpty()) {
            if (count(mc, goalItem) > 0) {
                finish(mc);
            } else {
                nextCandidate(mc, "路线跑完但没有产出");
            }
            return;
        }
        Step step = PLAN.peek();
        boolean busy = ContainerController.isBusy() || MiningController.isBusy() || DigController.isBusy()
                || CombatController.isHunting() || TaskTracker.isBusy();
        if (stepStarted) {
            if (baritoneMine) {
                tickBaritoneMine(mc, step, now, baseline);
                return;
            }
            if (step.op() == Op.DIG) {
                if (DigController.isBusy()) {
                    return;
                }
                PLAN.poll();
                stepStarted = false;
                stepSince = now;
                return;
            }
            if (busy) {
                if (now - stepSince > STEP_TIMEOUT_MS) {
                    nextCandidate(mc, step + " 超时");
                }
                return;
            }
            // 按份数校验：计划要 10 个木头，只捡到 2 个不算做完
            boolean produced = countAny(mc, step.item()) >= baseline + Math.max(1, step.count());
            if (!produced && step.op() != Op.PLACE && step.op() != Op.HUNT && step.op() != Op.DIG) {
                // 挖下来的东西掉在地上，要走过去捡：挖得多，等得久一点
                long grace = step.op() == Op.MINE ? 20_000L : 8_000L;
                if (verifyUntil == 0L) {
                    verifyUntil = now + grace;
                    return;
                }
                if (now < verifyUntil) {
                    return;
                }
                int gained = Math.max(0, countAny(mc, step.item()) - baseline);
                if (step.op() == Op.MINE && retryMine(step, gained)) {
                    stepStarted = false;
                    stepSince = now;
                    verifyUntil = 0L;
                    return;
                }
                PLAN.poll();
                stepStarted = false;
                verifications++;
                if (step.op() == Op.CRAFT || step.op() == Op.SMELT) {
                    replan(mc, step + " " + whyNoOutput(step));
                } else {
                    nextCandidate(mc, step + " " + whyNoOutput(step));
                }
                return;
            }
            if (step.op() == Op.PLACE && !placementHeld(mc, step.item())) {
                // 放下去了不等于放成功：没看到就再试，别让后面的合成对着空气开界面
                stepStarted = false;
                if (++placeAttempts <= PLACE_ATTEMPTS) {
                    return;
                }
                PLAN.poll();
                nextCandidate(mc, step + " 放不下去");
                return;
            }
            PLAN.poll();
            doneSteps++;
            STEP_RESULT.put(step.toString(), "✔");
            stepStarted = false;
            stepSince = now;
            verifyUntil = 0L;
            mineDryStreak = 0;
            mineRetries = 0;
            if (!planNeedsStation()) {
                // 后面不再需要工作台/熔炉：刚用完就收回来，别丢在原地
                PlacedBlocks.recoverAll();
            }
            return;
        }
        boolean ok = switch (step.op()) {
            case CRAFT -> ContainerController.craft(mc, step.item(), Math.max(1, step.count()));
            case SMELT -> ContainerController.cook(mc, step.item());
            case MINE -> startMine(mc, step);
            case HUNT -> CombatController.startHunt(mc, step.arg());
            case PLACE -> {
                boolean placedOk = PlacementController.place(mc, step.item());
                if (placedOk) {
                    PlacedBlocks.note();
                }
                yield placedOk;
            }
            case DIG -> startDeepDig(mc, step);
        };
        stepStarted = true;
        stepSince = now;
        baseline = countAny(mc, step.item());
        verifyUntil = 0L;
        statusText = "获取 " + goalItem + "：" + step;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire step {} ok={}", step, ok);
        if (!ok) {
            if (step.op() == Op.PLACE) {
                if (placementHeld(mc, step.item())) {
                    // 其实已经有一张在附近了，这一步直接算过
                    PLAN.poll();
                    stepStarted = false;
                    stepSince = now;
                    return;
                }
                if (++placeAttempts <= PLACE_ATTEMPTS && PlacementController.reposition(mc)) {
                    stepStarted = false;
                    return;
                }
                if (placeAttempts <= PLACE_ATTEMPTS) {
                    stepStarted = false;
                    return;
                }
            }
            PLAN.poll();
            stepStarted = false;
            if (step.op() == Op.CRAFT || step.op() == Op.SMELT) {
                // 材料半路不够了：按现在的背包重新算一遍还缺什么，而不是整条链作废
                replan(mc, step + " 差材料");
                return;
            }
            if (step.op() == Op.MINE && digDownThenRetry(step)) {
                return;
            }
            nextCandidate(mc, step.op() == Op.MINE
                    ? step + " 附近找不到 " + step.arg() + " 了"
                    : step + " 起不来");
        }
    }

    /** 计划里后面还有没有要用工作台/熔炉的步骤。 */
    private static boolean planNeedsStation() {
        for (Step step : PLAN) {
            if (step.op() == Op.CRAFT || step.op() == Op.SMELT) {
                return true;
            }
        }
        return false;
    }

    /** 那种方块是不是真在脚边了（放工作台/熔炉之后检查）。 */
    private static boolean placementHeld(MinecraftClient mc, String item) {
        if ("crafting_table".equals(item)) {
            return ContainerController.tableNearby(mc);
        }
        if ("furnace".equals(item)) {
            return ContainerController.furnaceNearby(mc);
        }
        return true;
    }

    /** 有 Baritone 就让它的 mine 自己走过去挖：会走位、会捡东西，比我们再写一遍靠谱。 */
    /** 这些矿在深板岩层更多：同族里有深板岩变体就先挖它。 */
    private static final Set<String> PREFER_DEEPSLATE = Set.of(
            "diamond", "deepslate_diamond_ore", "redstone", "deepslate_redstone_ore");

    /** 挖矿目标：同族里离得最近的方块（没有橡木就挖云杉，没有铁矿就挖深板岩铁矿）。 */
    private static String mineBlockFor(String item) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String best = null;
        double bestScore = Double.MAX_VALUE;
        boolean preferDeepslate = PREFER_DEEPSLATE.contains(item);
        if (preferDeepslate) {
            // 钻石/红石就该去深板岩层：有深板岩变体就直接选它，别在原地找浅层的
            for (String variant : ItemFamilies.variants(item)) {
                for (String block : BlockDrops.blocksFor(variant)) {
                    if (block.startsWith("deepslate_")) {
                        return block;
                    }
                }
            }
        }
        for (String variant : ItemFamilies.variants(item)) {
            for (String block : BlockDrops.blocksFor(variant)) {
                double dist = MiningController.nearestDistance(mc, block, 24);
                double score = dist < 0 ? 1000.0 : dist;
                if (score < bestScore) {
                    bestScore = score;
                    best = block;
                }
            }
        }
        return best;
    }

    private static boolean startMine(MinecraftClient mc, Step step) {
        if (io.github.xiaod.deepseekcompanion.config.CompanionConfig.get().fairPlay) {
            // 公平模式：不用 Baritone 的 #mine（它会用区块数据挑目标），改自己挖看得见的
            String visibleBlock = mineBlockFor(step.item());
            return MiningController.start(mc, visibleBlock == null ? step.arg() : visibleBlock,
                    false, Math.max(1, step.count()));
        }
        String block = mineBlockFor(step.item());
        if (block != null && !block.equals(step.arg())) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire: 同族里 {} 更近，挖它", block);
        }
        if (block == null || block.isEmpty()) {
            block = step.arg();
        }
        if (!BaritoneBridge.available() || selfMine) {
            return MiningController.start(mc, block, false, Math.max(1, step.count()));
        }
        BaritoneBridge.setSetting("allowBreak", "true");
        BaritoneBridge.setSetting("allowSprint", "true");
        boolean ok = BaritoneBridge.mine(block);
        baritoneMine = ok;
        lastMineHave = countAny(mc, step.item());
        lastMineX = mc.player == null ? 0 : mc.player.getX();
        lastMineY = mc.player == null ? 0 : mc.player.getY();
        lastMineZ = mc.player == null ? 0 : mc.player.getZ();
        lastMineProgress = System.currentTimeMillis();
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire: baritone 挖 {}（要 {} 个 {}）", block, step.count(), step.item());
        return ok;
    }

    /** 挖阶梯下去到目标高度（Baritone 不肯往垂直井里走，这一件只能自己来）。 */
    private static boolean startDeepDig(MinecraftClient mc, Step step) {
        if (mc.player == null) {
            return false;
        }
        int targetY = deepY(step.item());
        try {
            targetY = Integer.parseInt(step.arg());
        } catch (Throwable ignored) {
            // 用默认值
        }
        digTargetY = targetY;
        int levels = Math.max(1, (int) Math.abs(mc.player.getBlockY() - targetY));
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire: 挖 {} 级阶梯到 y={}，再挖 {}", levels, targetY, step.item());
        return DigController.start(mc, levels);
    }


    /** 补挖一次（有上限）；同时把掉在地上的东西捡回来，别光顾着挖。 */
    /** 同族方块附近有没有能挖的（附近没有才值得往下挖）。 */
    private static boolean anyFamilyBlockNearby(String item) {
        for (String variant : ItemFamilies.variants(item)) {
            for (String block : BlockDrops.blocksFor(variant)) {
                if (MiningController.nearestDistance(MinecraftClient.getInstance(), block, 24) >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 这种东西该挖到多深：钻石/红石靠深板岩层，其它按常规矿层。 */
    private static int deepY(String item) {
        if (PREFER_DEEPSLATE.contains(item) || item.startsWith("deepslate_")) {
            return -54;
        }
        if (item.startsWith("raw_iron") || item.startsWith("raw_copper") || item.startsWith("raw_gold")
                || item.equals("coal") || item.equals("lapis_lazuli") || item.equals("iron_ingot")) {
            return 16;
        }
        return 0;
    }

    /** 目标在地底、Baritone 走不到时，先自己挖下去再试。 */
    private static boolean digDownThenRetry(Step step) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return false;
        }
        if (mineRetries >= MAX_MINE_RETRIES) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire: 挖下去的次数用完了（{} 次）", MAX_MINE_RETRIES);
            return false;
        }
        if (mc.player.getY() < -40) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire: 已经在 y={}，不再往下挖", (int) mc.player.getY());
            return false;
        }
        int depth = Math.max(1, (int) Math.abs(mc.player.getY() - deepY(step.item())));
        mineRetries++;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire: {} 走不到，先往下挖 {} 格（第 {}/{} 次）",
                step.item(), depth, mineRetries, MAX_MINE_RETRIES);
        PLAN.addFirst(new Step(Op.MINE, step.item(), step.arg(), Math.max(1, step.count())));
        PLAN.addFirst(new Step(Op.DIG, step.item(), String.valueOf(deepY(step.item())), 0));
        return true;
    }

    private static boolean retryMine(Step step, int gained) {
        // 穷举：这种配料挖不到，就换同一栏里离得最近的下一种（石头挖不到就挖深板岩）
        List<String> alts = MINE_ALTS.get(step.item());
        while (alts != null && !alts.isEmpty()) {
            String alt = alts.remove(0);
            String block = blockFor(alt);
            if (block == null) {
                continue;
            }
            DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire: {} 挖不到，换成 {}", step.item(), alt);
            PLAN.addFirst(new Step(Op.MINE, alt, block, Math.max(1, step.count() - gained)));
            return true;
        }
        if (mineRetries >= MAX_MINE_RETRIES) {
            return false;
        }
        // 补挖只补差额：冗余只属于一开始那一下预估，不再往上加
        int missing = Math.max(1, step.count() - gained);
        mineRetries++;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire: {} 只到手 {} 个，还差 {} 个（第 {}/{} 次）",
                step, gained, missing, mineRetries, MAX_MINE_RETRIES);
        PLAN.addFirst(new Step(Op.MINE, step.item(), step.arg(), missing));
        return true;
    }

    /** 盯着 Baritone 挖：够了就叫停，没进展就补挖或换路线。 */
    private static void tickBaritoneMine(MinecraftClient mc, Step step, long now, int base) {
        int have = countAny(mc, step.item());
        int need = Math.max(1, step.count());
        if (have >= base + need) {
            BaritoneBridge.stop();
            baritoneMine = false;
            PLAN.poll();
            stepStarted = false;
            stepSince = now;
            mineDryStreak = 0;
            mineRetries = 0;
            return;
        }
        double moved = 0.0;
        if (mc.player != null) {
            moved = Math.hypot(mc.player.getX() - lastMineX, mc.player.getZ() - lastMineZ)
                    + Math.abs(mc.player.getY() - lastMineY);
            lastMineX = mc.player.getX();
            lastMineY = mc.player.getY();
            lastMineZ = mc.player.getZ();
        }
        if (have > lastMineHave || moved > 0.8) {
            // 东西变多了，或者人确实在移动/往下挖：都算在干活，别重启
            lastMineHave = have;
            lastMineProgress = now;
        }
        if (now - stepSince > MINE_TIMEOUT_MS) {
            BaritoneBridge.stop();
            baritoneMine = false;
            PLAN.poll();
            stepStarted = false;
            nextCandidate(mc, step + " 挖太久还没够");
            return;
        }
        if (now - lastMineProgress > MINE_STALL_MS) {
            BaritoneBridge.stop();
            baritoneMine = false;
            boolean reachable = false;
            for (String variant : ItemFamilies.variants(step.item())) {
                for (String block : BlockDrops.blocksFor(variant)) {
                    if (MiningController.nearestDistance(mc, block, 24) >= 0) {
                        reachable = true;
                        break;
                    }
                }
                if (reachable) {
                    break;
                }
            }
            if (!selfMine && reachable) {
                // 只有附近真有一块够得着的，才改自己挖；地底下的交给 Baritone 挖下去
                selfMine = true;
                DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire: baritone 走不过去，改用自己挖 {}", step.arg());
                return;
            }
            PLAN.poll();
            stepStarted = false;
            int gained = Math.max(0, have - base);
            // 深层矿：先自己挖下去，再让 Baritone 顺着井道去挖
            if (digDownThenRetry(step)) {
                return;
            }
            nextCandidate(mc, step + " 没进展");
        }
    }

    /** 失败原因说清楚：是没挖到，还是挖了没捡到。 */
    private static String whyNoOutput(Step step) {
        if (step.op() == Op.MINE) {
            return MiningController.minedCount() > 0 ? "挖了但东西没进背包" : "挖不动";
        }
        if (step.op() == Op.CRAFT) {
            return "合成没出东西";
        }
        if (step.op() == Op.SMELT) {
            return "烧完没拿到";
        }
        return "没有产出";
    }

    private static void finish(MinecraftClient mc) {
        int have = count(mc, goalItem);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire stage done: {} (now {})", goalItem, have);
        if (!STAGES.isEmpty()) {
            beginStage(mc);
            return;
        }
        running = false;
        PlacedBlocks.recoverAll(); // 工作台、熔炉用完收回来
        int total = count(mc, finalGoal);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire finished: {} (now {})", finalGoal, total);
        EventLog.note("获取完成", finalGoal + " 现在 " + total + " 个");
        BotBrain.tell("弄完了：" + finalGoal + "（" + total + " 个）");
    }

    private static void fail(MinecraftClient mc, String reason) {
        running = false;
        PlacedBlocks.recoverAll();
        PLAN.clear();
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire stage failed: {} ({})", goalItem, reason);
        Memory.noteFailure("获取 " + goalItem, reason);
        EventLog.note("获取失败", goalItem + "：" + reason);
        BotBrain.tell("弄不到 " + goalItem + "：" + reason);
    }

    public static void cancel(MinecraftClient mc) {
        running = false;
        if (baritoneMine) {
            BaritoneBridge.stop();
            baritoneMine = false;
        }
        PLAN.clear();
        CANDIDATES.clear();
        MiningController.cancel(mc);
        DigController.cancel(mc);
        ContainerController.cancel(mc);
        TaskTracker.clear();
    }

    private static String recipeKey(CraftRecipes.Recipe recipe) {
        return recipe.result() + "|" + recipe.width() + "x" + recipe.height() + "|"
                + String.join(",", recipe.cells());
    }

    private static int recipeCost(CraftRecipes.Recipe recipe) {
        int total = 0;
        for (String cell : recipe.cells()) {
            if (cell == null) {
                continue;
            }
            String resolved = resolveToken(cell);
            if (resolved == null) {
                return Integer.MAX_VALUE - 1;
            }
            total += costOf(resolved);
        }
        return total;
    }

    private static int costOf(String item) {
        if (item == null) {
            return 9;
        }
        if (have(item) > 0) {
            return 0;
        }
        Integer known = COST.get(item);
        if (known != null) {
            return known;
        }
        if (!CraftRecipes.find(item).isEmpty()) {
            return 5;
        }
        if (isLikelyItemId(item) && Registries.BLOCK.containsId(Identifier.of("minecraft", item))) {
            return 6;
        }
        return 8;
    }

    private static boolean isLikelyItemId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isWood(String item) {
        return item != null && (item.endsWith("_log") || item.endsWith("_stem")
                || item.endsWith("_wood") || item.endsWith("_hyphae"));
    }

    private static String blockFor(String item) {
        if (item == null || SMELT_ONLY.contains(item) || STORAGE_BLOCKS.contains(item)) {
            return null; // 挖了也掉不出来、或者本来就不该靠挖，别白跑
        }
        String mapped = ITEM_TO_BLOCK.get(item);
        if (mapped != null) {
            return mapped;
        }
        // 原版掉落表说哪块掉这个东西：铁矿和深板岩铁矿都会算进来
        String fromDrops = BlockDrops.bestBlockFor(item);
        if (fromDrops != null && !fromDrops.isEmpty()) {
            return fromDrops;
        }
        if (!isLikelyItemId(item)) {
            return null;
        }
        try {
            return Registries.BLOCK.containsId(Identifier.of("minecraft", item)) ? item : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 背包里（或正在砍的）是哪种木头：oak / spruce / birch …；没有就返回 null。 */
    private static String woodType(MinecraftClient mc) {
        if (mc == null || mc.player == null) {
            return null;
        }
        var inventory = mc.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            for (String suffix : List.of("_log", "_stem", "_wood", "_hyphae", "_planks")) {
                if (id.endsWith(suffix)) {
                    return id.substring(0, id.length() - suffix.length());
                }
            }
        }
        return null;
    }

    private static String resolveToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String kind = token;
        String rest = "";
        int split = token.indexOf(':');
        if (split > 0) {
            kind = token.substring(0, split);
            rest = token.substring(split + 1);
        }
        if (kind.equals("item")) {
            int colon = rest.indexOf(':');
            return colon < 0 ? rest : rest.substring(colon + 1);
        }
        if (kind.equals("tag")) {
            int colon = rest.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : rest.substring(0, colon);
            String path = colon < 0 ? rest : rest.substring(colon + 1);
            TagKey<net.minecraft.item.Item> key = TagKey.of(RegistryKeys.ITEM, Identifier.of(namespace, path));
            String fallback = null;
            String best = null;
            int bestScore = Integer.MIN_VALUE;
            java.util.List<String> ranked = new ArrayList<>();
            for (var item : Registries.ITEM) {
                if (item == net.minecraft.item.Items.AIR) {
                    continue;
                }
                try {
                    if (!new ItemStack(item).isIn(key)) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    continue;
                }
                String id = Registries.ITEM.getId(item).getPath();
                if (fallback == null) {
                    fallback = id;
                }
                // 背包里已经有 > 附近真能挖到 > 更常见
                int score = 0;
                if (count(MinecraftClient.getInstance(), id) > 0) {
                    score += 50;
                }
                String block = blockFor(id);
                if (block != null && MiningController.anyNearby(MinecraftClient.getInstance(), block, 16)) {
                    score += 100;
                }
                String wood = woodType(MinecraftClient.getInstance());
                if (wood != null && id.startsWith(wood + "_")) {
                    score += 400; // 手上的木头优先：不然会去合橡木木板却只有云杉
                }
                int pref = COMMON_PREF.indexOf(id);
                if (pref >= 0) {
                    score += 30 - pref;
                }
                double dist = -1.0;
                if (block != null) {
                    dist = MiningController.nearestDistance(MinecraftClient.getInstance(), block, 24);
                    if (dist >= 0) {
                        score += 120 - Math.min(119, (int) (dist * 6)); // 越近越优先
                    }
                }
                ranked.add(id);
                if (score > bestScore) {
                    bestScore = score;
                    best = id;
                }
            }
            if (best != null && ranked.size() > 1) {
                ranked.remove(best);
                MINE_ALTS.put(best, new ArrayList<>(ranked)); // 挖不动时按顺序换下一种
            }
            return best != null ? best : fallback;
        }
        int colon = token.indexOf(':');
        return colon < 0 ? token : token.substring(colon + 1);
    }

    /** 同族一起数：要橡木但身上是云杉木，也算数（变种表里是一族）。 */
    public static int countAny(MinecraftClient mc, String item) {
        int total = 0;
        for (String variant : ItemFamilies.variants(item)) {
            total += count(mc, variant);
        }
        return total;
    }

    public static int count(MinecraftClient mc, String id) {
        ClientPlayerEntity p = mc.player;
        if (p == null || id == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = p.getInventory().getStack(slot);
            if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getPath().equals(id)) {
                total += stack.getCount();
            }
        }
        if (!p.getOffHandStack().isEmpty()
                && Registries.ITEM.getId(p.getOffHandStack().getItem()).getPath().equals(id)) {
            total += p.getOffHandStack().getCount();
        }
        return total;
    }

    private static int have(String id) {
        return count(MinecraftClient.getInstance(), id);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String id = raw.strip().toLowerCase(Locale.ROOT);
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
