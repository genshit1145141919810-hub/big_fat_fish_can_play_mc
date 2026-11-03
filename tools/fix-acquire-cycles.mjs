// Fix the recursive planner: no cycles, smelting preferred over crafting, and never
// take the "decompress a block back into ingots" recipe.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/AcquireTask.java';
let s = readFileSync(p, 'utf8');
const out = [];

// 1) cycle guard set
if (!s.includes('PATH = new java.util.HashSet')) {
  s = s.replace(
    '    private static final Deque<Step> PLAN = new ArrayDeque<>();',
    '    private static final Deque<Step> PLAN = new ArrayDeque<>();\n    /** 当前递归路径上的物品，用来掐断 铁锭→铁粒→铁锭 这类循环。 */\n    private static final java.util.Set<String> PATH = new java.util.HashSet<>();'
  );
  s = s.replace('        PLAN.clear();\n        NOTES.clear();', '        PLAN.clear();\n        NOTES.clear();\n        PATH.clear();');
  out.push('ok cycle set');
}

// 2) rename the recursive body and wrap it with the cycle guard
if (!s.includes('planInner')) {
  s = s.replace(
    '    private static boolean plan(String item, int need, int depth, List<Step> steps) {',
    `    private static boolean plan(String item, int need, int depth, List<Step> steps) {
        if (PATH.contains(item)) {
            NOTES.add(item + " 形成循环依赖");
            return false;
        }
        PATH.add(item);
        try {
            return planInner(item, need, depth, steps);
        } finally {
            PATH.remove(item);
        }
    }

    private static boolean planInner(String item, int need, int depth, List<Step> steps) {`
  );
  out.push('ok cycle wrapper');
}

// 3) smelting wins over crafting when both exist (iron_ingot must come from ore, not a block)
if (!s.includes('boolean smeltable')) {
  s = s.replace(
    '        for (CraftRecipes.Recipe recipe : CraftRecipes.find(item)) {',
    `        boolean smeltable = !SmeltRecipes.byOutput(item).isEmpty();
        for (CraftRecipes.Recipe recipe : smeltable ? java.util.List.<CraftRecipes.Recipe>of() : CraftRecipes.find(item)) {`
  );
  out.push('ok smelt-first');
}

// 4) do not mine a "block" for an item that is itself craftable output only
if (!s.includes('MINE 只用于原料')) {
  s = s.replace(
    '        String block = blockFor(item);\n        if (block != null) {',
    `        // MINE 只用于"原料型"物品：如果它本身是合成产物（铁块、铁锭之类），
        // 挖它的方块只会得到方块本身，不能倒推出原料。
        String block = blockFor(item);
        if (block != null && !item.endsWith("_block") && !SmeltRecipes.byOutput(item).isEmpty()) {
            block = null; // 能熔炼的说明该走熔炼，不走挖方块
        }
        if (block != null) {`
  );
  out.push('ok mine guard');
}

writeFileSync(p, s, 'utf8');
console.log(out.join('\n'));
