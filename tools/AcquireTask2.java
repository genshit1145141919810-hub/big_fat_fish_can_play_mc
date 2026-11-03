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
 * <p>The planner produces several candidate plans (cheapest recipe first, then alternatives,
 * then mining/hunting routes). The runner executes the first plan; if a step fails or produces
 * nothing, the plan is discarded and the next candidate is tried. Only when every candidate is
 * exhausted does the acquisition fail.</p>
 *
 * <p>Deterministic and token-free: the model only states the goal.</p>
 */
public final class AcquireTask {
    private static final int MAX_STEPS = 48;
    private static final int MAX_DEPTH = 4;
    private static final int MAX_CANDIDATES = 4;
    private static final int PLACE_ATTEMPTS = 3;
    private static final long STEP_TIMEOUT_MS = 60_000L;
    private static final long TOTAL_TIMEOUT_MS = 420_000L;

    private enum Op { CRAFT, SMELT, MINE, HUNT, PLACE }

    private record Step(Op op, String item, String arg, int count) {
        @Override
        public String toString() {
            return op + ":" + item + (arg == null || arg.isEmpty() ? "" : "(" + arg + ")");
        }
    }

    private static final Map<String, String> TOOL_TIER = new LinkedHashMap<>();
    private static final Map<String, String> ITEM_TO_BLOCK = new LinkedHashMap<>();
    private static final Map<String, String> ITEM_TO_MOB = new LinkedHashMap<>();
    private static final Map<String, Integer> COST = new LinkedHashMap<>();

    static {
        for (String b : List.of("stone", "cobblestone", "coal_ore", "copper_ore", "iron_ore",
                "lapis_ore", "deepslate", "tuff", "andesite", "diorite", "granite")) {
            TOOL_TIER.put(b, "wooden_pickaxe");
        }
        for (String b : List.of("gold_ore", "redstone_ore", "diamond_ore", "emerald_ore",
                "deepslate_iron_ore", "deepslate_gold_ore", "deepslate_diamond_ore", "nether_quartz_ore")) {
            TOOL_TIER.put(b, "stone_pickaxe");
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
        ITEM_TO_BLOCK.put("charcoal", "oak_log");
        ITEM_TO_BLOCK.put("bamboo", "bamboo");

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
        COST.put("crafting_table", 4);
        COST.put("stick", 4);
        COST.put("stone", 4);
        COST.put("cobblestone", 4);
        COST.put("coal", 5);
        COST.put("raw_iron", 7);
        COST.put("iron_ore", 7);
        COST.put("iron_ingot", 8);
    }

    private static final List<List<Step>> CANDIDATES = new ArrayList<>();
    private static final Deque<Step> PLAN = new ArrayDeque<>();
    private static final List<String> NOTES = new ArrayList<>();
    private static final Set<String> PATH = new LinkedHashSet<>();
    private static int candidateIndex;
    private static String goalItem = "";
    private static int goalCount;
    private static int placeAttempts;
    private static long startedAt;
    private static long stepSince;
    private static boolean running;
    private static boolean stepStarted;
    private static String statusText = "";

    private AcquireTask() {
    }

    public static boolean isBusy() {
        return running;
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
            BotBrain.tell("娌¤瑕佸紕浠€涔?);
            return false;
        }
        CANDIDATES.clear();
        NOTES.clear();
        PATH.clear();
        goalItem = id;
        goalCount = Math.max(1, count);
        int before = count(mc, id);
        planCandidates(id, goalCount);
        if (CANDIDATES.isEmpty()) {
            String why = NOTES.isEmpty() ? "娌℃湁鍙璺嚎" : NOTES.get(NOTES.size() - 1);
            BotBrain.tell("寮勪笉鍒?" + id + "锛? + why);
            Memory.noteFailure("鑾峰彇 " + id, why);
            EventLog.note("鑾峰彇澶辫触", id + "锛? + why);
            return false;
        }
        candidateIndex = 0;
        loadCandidate(mc);
        startedAt = System.currentTimeMillis();
        stepSince = startedAt;
        running = true;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire {} x{} (have {}) - {} candidate(s): {}",
                id, goalCount, before, CANDIDATES.size(), PLAN);
        EventLog.note("寮€濮嬭幏鍙?, id + "锛? + CANDIDATES.size() + " 鏉¤矾绾匡級");
        return true;
    }

    // ------------------------------------------------------------- planning

    /** 椤跺眰绌蜂妇锛氬悓涓€鐗╁搧鐨勬瘡鏉￠厤鏂瑰悇鐢熸垚涓€涓€欓€夋柟妗堬紝鏈€鍚庡啀鍔?鎸?鎵?璺嚎銆?*/
    private static void planCandidates(String item, int need) {
        List<CraftRecipes.Recipe> recipes = new ArrayList<>(CraftRecipes.find(item));
        recipes.sort(Comparator.comparingInt(AcquireTask::recipeCost));
        for (CraftRecipes.Recipe recipe : recipes) {
            if (CANDIDATES.size() >= MAX_CANDIDATES) {
                break;
            }
            List<Step> plan = new ArrayList<>();
            if (planWith(item, need, 0, plan, recipeKey(recipe))) {
                CANDIDATES.add(List.copyOf(plan));
            }
        }
        if (CANDIDATES.isEmpty()) {
            List<Step> plan = new ArrayList<>();
            if (plan(item, need, 0, plan)) {
                CANDIDATES.add(List.copyOf(plan));
            }
        }
    }

    private static boolean plan(String item, int need, int depth, List<Step> steps) {
        return planWith(item, need, depth, steps, null);
    }

    private static boolean planWith(String item, int need, int depth, List<Step> steps, String forcedRecipe) {
        if (PATH.contains(item)) {
            NOTES.add(item + " 褰㈡垚寰幆渚濊禆");
            return false;
        }
        PATH.add(item);
        try {
            return planInner(item, need, depth, steps, forcedRecipe);
        } finally {
            PATH.remove(item);
        }
    }

    private static boolean planInner(String item, int need, int depth, List<Step> steps, String forcedRecipe) {
        if (depth > MAX_DEPTH || steps.size() > MAX_STEPS) {
            NOTES.add("宓屽澶繁鎴栨楠ゅお澶?);
            return false;
        }
        int missing = need - have(item);
        if (missing <= 0) {
            return true;
        }
        boolean smeltable = !SmeltRecipes.byOutput(item).isEmpty();
        List<CraftRecipes.Recipe> recipes = new ArrayList<>(smeltable
                ? List.of() : CraftRecipes.find(item));
        recipes.sort(Comparator.comparingInt(AcquireTask::recipeCost));
        for (CraftRecipes.Recipe recipe : recipes) {
            if (forcedRecipe != null && !recipeKey(recipe).equals(forcedRecipe)) {
                continue;
            }
            List<Step> sub = new ArrayList<>();
            int crafts = Math.max(1, (missing + recipe.count() - 1) / recipe.count());
            Map<String, Integer> ingredients = new LinkedHashMap<>();
            for (String cell : recipe.cells()) {
                if (cell != null) {
                    ingredients.merge(cell, 1, Integer::sum);
                }
            }
            boolean ok = true;
            for (Map.Entry<String, Integer> ingredient : ingredients.entrySet()) {
                String resolved = resolveToken(ingredient.getKey());
                if (resolved == null || !plan(resolved, ingredient.getValue() * crafts, depth + 1, sub)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            steps.addAll(sub);
            if (recipe.width() > 2 || recipe.height() > 2) {
                List<Step> table = new ArrayList<>();
                if (count(MinecraftClient.getInstance(), "crafting_table") <= 0
                        && !plan("crafting_table", 1, depth + 1, table)) {
                    NOTES.add(item + " 闇€瑕佸伐浣滃彴锛屼絾鍋氫笉鍑烘潵");
                    continue;
                }
                steps.addAll(table);
                steps.add(new Step(Op.PLACE, "crafting_table", "crafting_table", 1));
            }
            steps.add(new Step(Op.CRAFT, item, "", crafts));
            return true;
        }
        for (SmeltRecipes.Entry entry : SmeltRecipes.byOutput(item)) {
            String ore = normalize(entry.inputToken());
            if (ore.isEmpty()) {
                continue;
            }
            List<Step> sub = new ArrayList<>();
            if (!plan(ore, missing, depth + 1, sub)) {
                continue;
            }
            boolean fuel = plan("oak_planks", Math.max(1, missing), depth + 1, sub)
                    || plan("coal", Math.max(1, missing), depth + 1, sub)
                    || plan("charcoal", Math.max(1, missing), depth + 1, sub);
            if (!fuel) {
                NOTES.add(item + " 缂虹噧鏂?);
                continue;
            }
            steps.addAll(sub);
            steps.add(new Step(Op.SMELT, item, ore, missing));
            return true;
        }
        String block = blockFor(item);
        if (block != null && (smeltable || ITEM_TO_BLOCK.containsKey(item)
                || Registries.BLOCK.containsId(Identifier.of("minecraft", item)))) {
            List<Step> sub = new ArrayList<>();
            String tool = TOOL_TIER.get(block);
            if (tool != null && count(MinecraftClient.getInstance(), tool) <= 0
                    && !plan(tool, 1, depth + 1, sub)) {
                NOTES.add("鎸?" + block + " 闇€瑕?" + tool + "锛屽仛涓嶅嚭鏉?);
                return false;
            }
            steps.addAll(sub);
            steps.add(new Step(Op.MINE, item, block, missing));
            return true;
        }
        String mob = ITEM_TO_MOB.get(item);
        if (mob != null) {
            steps.add(new Step(Op.HUNT, item, mob, missing));
            return true;
        }
        NOTES.add("涓嶇煡閬?" + item + " 浠庡摢鏉?);
        return false;
    }

    // ------------------------------------------------------------ execution

    private static void loadCandidate(MinecraftClient mc) {
        PLAN.clear();
        if (candidateIndex >= CANDIDATES.size()) {
            return;
        }
        PLAN.addAll(CANDIDATES.get(candidateIndex));
        stepStarted = false;
        placeAttempts = 0;
        stepSince = System.currentTimeMillis();
        statusText = "鑾峰彇 " + goalItem + "锛堣矾绾?" + (candidateIndex + 1) + "/" + CANDIDATES.size() + "锛?;
    }

    private static void nextCandidate(MinecraftClient mc, String reason) {
        NOTES.add("璺嚎 " + (candidateIndex + 1) + " 澶辫触锛? + reason);
        candidateIndex++;
        if (candidateIndex >= CANDIDATES.size()) {
            fail(mc, "鎵€鏈夎矾绾块兘璇曡繃浜?);
            return;
        }
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire {}: route {} failed ({}), trying {}",
                goalItem, candidateIndex, reason, candidateIndex + 1);
        EventLog.note("鎹㈣矾绾?, goalItem + "锛? + reason);
        loadCandidate(mc);
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
            fail(mc, "鎬绘椂闀胯秴杩?7 鍒嗛挓");
            return;
        }
        if (PLAN.isEmpty()) {
            if (count(mc, goalItem) > 0) {
                finish(mc);
            } else {
                nextCandidate(mc, "璺嚎璺戝畬浣嗘病鏈変骇鍑?);
            }
            return;
        }
        Step step = PLAN.peek();
        boolean busy = ContainerController.isBusy() || MiningController.isBusy()
                || CombatController.isHunting() || TaskTracker.isBusy();
        if (stepStarted) {
            if (step.op() == Op.PLACE && busy) {
                return;
            }
            if (busy) {
                if (now - stepSince > STEP_TIMEOUT_MS) {
                    nextCandidate(mc, step + " 瓒呮椂");
                }
                return;
            }
            // 楠屾敹锛氬悎鎴?鐔旂偧/鎸栨帢蹇呴』鐪熺殑浜у嚭
            int have = count(mc, step.item());
            boolean produced = have > 0;
            PLAN.poll();
            stepStarted = false;
            stepSince = now;
            if (!produced && (step.op() == Op.CRAFT || step.op() == Op.SMELT || step.op() == Op.MINE)) {
                nextCandidate(mc, step + " 娌℃湁浜у嚭");
            }
            return;
        }
        boolean ok = switch (step.op()) {
            case CRAFT -> ContainerController.craft(mc, step.item());
            case SMELT -> ContainerController.cook(mc, step.arg());
            case MINE -> MiningController.start(mc, step.arg(),
                    step.arg().endsWith("_log") || step.arg().endsWith("_stem"), Math.max(1, step.count()));
            case HUNT -> CombatController.startHunt(mc, step.arg());
            case PLACE -> PlacementController.place(mc, step.item());
        };
        stepStarted = true;
        stepSince = now;
        statusText = "鑾峰彇 " + goalItem + "锛? + step;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire step {} ok={}", step, ok);
        if (!ok) {
            if (step.op() == Op.PLACE && ++placeAttempts <= PLACE_ATTEMPTS) {
                stepStarted = false; // place 鏈夎嚜宸辩殑寤跺悗閲嶈瘯锛岀粰瀹冨嚑娆℃満浼?                return;
            }
            PLAN.poll();
            stepStarted = false;
            nextCandidate(mc, step + " 璧蜂笉鏉?);
        }
    }

    private static void finish(MinecraftClient mc) {
        running = false;
        int have = count(mc, goalItem);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire finished: {} (now {}, route {})",
                goalItem, have, candidateIndex + 1);
        EventLog.note("鑾峰彇瀹屾垚", goalItem + " 鐜板湪 " + have + " 涓?);
        BotBrain.tell("寮勫畬浜嗭細" + goalItem + "锛? + have + " 涓級");
    }

    private static void fail(MinecraftClient mc, String reason) {
        running = false;
        PLAN.clear();
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire failed: {} ({})", goalItem, reason);
        Memory.noteFailure("鑾峰彇 " + goalItem, reason);
        EventLog.note("鑾峰彇澶辫触", goalItem + "锛? + reason);
        BotBrain.tell("寮勪笉鍒?" + goalItem + "锛? + reason);
    }

    public static void cancel(MinecraftClient mc) {
        running = false;
        PLAN.clear();
        CANDIDATES.clear();
        MiningController.cancel(mc);
        ContainerController.cancel(mc);
        TaskTracker.clear();
    }

    // -------------------------------------------------------------- helpers

    /** 配方唯一标识，用来在顶层强制"就试这一条"。 */
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
        int have = have(item);
        if (have > 0) {
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

    private static String blockFor(String item) {
        String mapped = ITEM_TO_BLOCK.get(item);
        if (mapped != null) {
            return mapped;
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
                if (!CraftRecipes.find(id).isEmpty()
                        || Registries.BLOCK.containsId(Identifier.of("minecraft", id))) {
                    return id;
                }
            }
            return fallback;
        }
        int colon = token.indexOf(':');
        return colon < 0 ? token : token.substring(colon + 1);
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
