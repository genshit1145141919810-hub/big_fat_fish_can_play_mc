// Plan with a simulated inventory ledger, then coalesce adjacent identical steps:
//   - plan(item, need) consumes from (real inventory + planned production)
//   - production is added to the ledger before the calling recipe consumes its ingredients
//   - consecutive MINE/CRAFT of the same target are merged into one step with a total count
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/AcquireTask.java';
let s = readFileSync(p, 'utf8');
const out = [];

// 1) ledger helpers
if (!s.includes('private static int avail(')) {
  s = s.replace(
    '    private static final Set<String> PATH = new LinkedHashSet<>();',
    `    private static final Set<String> PATH = new LinkedHashSet<>();
    /** 规划期的"计划库存"：已有 + 计划产出 - 计划消耗，避免重复挖同一批木头。 */
    private static final Map<String, Integer> SIM = new LinkedHashMap<>();`
  );
  s = s.replace(
    '    private static int recipeCost(CraftRecipes.Recipe recipe) {',
    `    private static int avail(String item) {
        return have(item) + SIM.getOrDefault(item, 0);
    }

    private static void supply(String item, int n) {
        if (n <= 0) {
            return;
        }
        SIM.merge(item, n, Integer::sum);
    }

    private static void consume(String item, int n) {
        int left = SIM.getOrDefault(item, 0) - n;
        if (left > 0) {
            SIM.put(item, left);
        } else {
            SIM.remove(item);
        }
    }

    private static int recipeCost(CraftRecipes.Recipe recipe) {`
  );
  out.push('ok ledger helpers');
}

// 2) reset ledger per candidate
s = s.replace(
  '        CANDIDATES.clear();\n        NOTES.clear();\n        PATH.clear();',
  '        CANDIDATES.clear();\n        NOTES.clear();\n        PATH.clear();\n        SIM.clear();'
);

// 3) rewrite the plan entry to use the ledger
if (!s.includes('int missing = need - avail(item)')) {
  s = s.replace(
    `        int missing = need - have(item);
        if (missing <= 0) {
            return true;
        }`,
    `        int available = avail(item);
        if (available >= need) {
            consume(item, need);
            return true;
        }
        int missing = need - available;
        consume(item, available);`
  );
  out.push('ok ledger entry');
}

// 4) craft branch: supply output before consuming ingredients, then consume what we need
s = s.replace(
  `            steps.add(new Step(Op.CRAFT, item, "", crafts));
            return true;
        }
        for (SmeltRecipes.Entry entry : SmeltRecipes.byOutput(item)) {`,
  `            steps.add(new Step(Op.CRAFT, item, "", crafts));
            supply(item, crafts * recipe.count());
            consume(item, need);
            return true;
        }
        for (SmeltRecipes.Entry entry : SmeltRecipes.byOutput(item)) {`
);

s = s.replace(
  `            steps.add(new Step(Op.SMELT, item, ore, missing));
            return true;`,
  `            steps.add(new Step(Op.SMELT, item, ore, missing));
            supply(item, missing);
            consume(item, need);
            return true;`
);

s = s.replace(
  `            steps.add(new Step(Op.MINE, item, block, missing));
            return true;`,
  `            steps.add(new Step(Op.MINE, item, block, missing));
            supply(item, missing);
            consume(item, need);
            return true;`
);

s = s.replace(
  `            steps.add(new Step(Op.HUNT, item, mob, missing));
            return true;`,
  `            steps.add(new Step(Op.HUNT, item, mob, missing));
            supply(item, missing);
            consume(item, need);
            return true;`
);

// 5) merge adjacent identical steps after a candidate is built
if (!s.includes('private static List<Step> coalesce')) {
  s = s.replace(
    '    private static void loadCandidate() {',
    `    /** 合并相邻的同类步骤：挖三根木头算一步，合两次木板算一步。 */
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

    private static void loadCandidate() {`
  );
  out.push('ok coalesce');
}

s = s.replace('CANDIDATES.add(List.copyOf(plan));\n            }\n        }\n        if (CANDIDATES.isEmpty()) {',
  'CANDIDATES.add(List.copyOf(coalesce(plan)));\n            }\n        }\n        if (CANDIDATES.isEmpty()) {');
s = s.replace('if (planWith(item, need, 0, plan, null)) {\n                CANDIDATES.add(List.copyOf(plan));',
  'if (planWith(item, need, 0, plan, null)) {\n                CANDIDATES.add(List.copyOf(coalesce(plan)));');

writeFileSync(p, s, 'utf8');
console.log(out.join('\n'));
