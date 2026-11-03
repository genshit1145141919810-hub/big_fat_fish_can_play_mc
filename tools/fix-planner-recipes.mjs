// Planner fixes:
//  A. score candidate recipes by ingredient cost (planks beat bamboo for sticks)
//  B. 3x3 recipes need a crafting table -> plan craft + place of one
//  C. PLACE becomes a primitive op
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/AcquireTask.java';
let s = readFileSync(p, 'utf8');
const out = [];

// C. new op
if (!s.includes('CRAFT, SMELT, MINE, HUNT, PLACE')) {
  s = s.replace('private enum Op { CRAFT, SMELT, MINE, HUNT }', 'private enum Op { CRAFT, SMELT, MINE, HUNT, PLACE }');
  out.push('ok PLACE op');
}

// A. ingredient cost table + helper
if (!s.includes('costOf(')) {
  s = s.replace(
    '    private static final Deque<Step> PLAN = new ArrayDeque<>();',
    `    /** 原料越"便宜"越优先：背包现成 0，砍树 2，合出来 4，要挖矿 6，没头绪 9。 */
    private static final Map<String, Integer> COST = new LinkedHashMap<>();

    static {
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
        if (Registries.BLOCK.containsId(Identifier.of("minecraft", item))) {
            return 6;
        }
        return 8;
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

    private static final Deque<Step> PLAN = new ArrayDeque<>();`
  );
  out.push('ok cost table');
}

// A. choose the cheapest recipe instead of the first workable one
if (!s.includes('bestRecipe')) {
  const from = `        boolean smeltable = !SmeltRecipes.byOutput(item).isEmpty();
        for (CraftRecipes.Recipe recipe : smeltable ? java.util.List.<CraftRecipes.Recipe>of() : CraftRecipes.find(item)) {`;
  const to = `        boolean smeltable = !SmeltRecipes.byOutput(item).isEmpty();
        java.util.List<CraftRecipes.Recipe> candidates = smeltable
                ? java.util.List.<CraftRecipes.Recipe>of() : CraftRecipes.find(item).stream()
                        .sorted(Comparator.comparingInt(AcquireTask::recipeCost))
                        .collect(java.util.stream.Collectors.toList());
        for (CraftRecipes.Recipe recipe : candidates) {`;
  if (s.includes(from)) {
    s = s.replace(from, to);
    out.push('ok recipe scoring');
  } else {
    out.push('MISS recipe scoring');
  }
}

// A. imports for Comparator
if (!s.includes('import java.util.Comparator;')) {
  s = s.replace('import java.util.ArrayList;', 'import java.util.ArrayList;\nimport java.util.Comparator;');
  out.push('ok import Comparator');
}

// B. 3x3 recipes: ensure a crafting table is crafted and placed first
if (!s.includes('needsTable')) {
  const from = `            if (ok) {
                steps.addAll(sub);
                steps.add(new Step(Op.CRAFT, item, "", crafts));
                return true;
            }`;
  const to = `            if (ok) {
                steps.addAll(sub);
                boolean needsTable = recipe.width() > 2 || recipe.height() > 2;
                if (needsTable) {
                    List<Step> table = new ArrayList<>();
                    if (count(MinecraftClient.getInstance(), "crafting_table") <= 0) {
                        if (!plan("crafting_table", 1, depth + 1, table)) {
                            NOTES.add(item + " 需要工作台，但做不出来");
                            return false;
                        }
                    }
                    steps.addAll(table);
                    steps.add(new Step(Op.PLACE, "crafting_table", "crafting_table", 1));
                }
                steps.add(new Step(Op.CRAFT, item, "", crafts));
                return true;
            }`;
  if (s.includes(from)) {
    s = s.replace(from, to);
    out.push('ok table planning');
  } else {
    out.push('MISS table planning');
  }
}

// C. execute PLACE
if (!s.includes('case PLACE -> PlacementController.place')) {
  s = s.replace(
    '            case HUNT -> CombatController.startHunt(mc, step.arg());',
    '            case HUNT -> CombatController.startHunt(mc, step.arg());\n            case PLACE -> PlacementController.place(mc, step.item());'
  );
  out.push('ok PLACE exec');
}

writeFileSync(p, s, 'utf8');
console.log(out.join('\n'));
