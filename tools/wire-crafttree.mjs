// Wire CraftTree into ContainerController: craft() now plans the whole dependency tree and
// executes the steps one by one with the existing single-craft machinery.
import { readFileSync, writeFileSync } from 'node:fs';

const path = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/ContainerController.java';
let s = readFileSync(path, 'utf8');
const before = s;

// 1) fields for the tree queue
if (!s.includes('treeQueue')) {
  s = s.replace(
    '    /** 合成网格宽度：工作台 3，自己背包 2。 */\n    private static int gridWidth = 3;',
    '    /** 合成网格宽度：工作台 3，自己背包 2。 */\n    private static int gridWidth = 3;\n    /** 合成树：树模式下的待执行步骤。 */\n    private static final Deque<io.github.xiaod.deepseekcompanion.integrations.CraftTree.Step> treeQueue =\n            new ArrayDeque<>();\n    private static boolean treeMode;'
  );
  console.log('fields added');
}

// 2) craft() becomes the tree entry point; the old body becomes startSingleCraft()
const ctorFrom = '    public static boolean craft(MinecraftClient mc, String itemId) {\n        if (mc.player == null) {\n            return false;\n        }';
const ctorTo = `    public static boolean craft(MinecraftClient mc, String itemId) {
        if (mc.player == null) {
            return false;
        }
        List<io.github.xiaod.deepseekcompanion.integrations.CraftTree.Step> plan =
                io.github.xiaod.deepseekcompanion.integrations.CraftTree.plan(mc, itemId);
        if (!plan.isEmpty()) {
            treeQueue.clear();
            treeQueue.addAll(plan);
            treeMode = true;
            BotBrain.tell("合成链：" + plan);
            return startTreeStep(mc);
        }
        treeMode = false;
        return startSingleCraft(mc, itemId);
    }

    private static boolean startTreeStep(MinecraftClient mc) {
        io.github.xiaod.deepseekcompanion.integrations.CraftTree.Step step = treeQueue.poll();
        if (step == null) {
            treeMode = false;
            BotBrain.tell("合成链跑完了");
            return true;
        }
        targetItem = step.item();
        BlockPos table = findBlock(mc, Blocks.CRAFTING_TABLE, 6);
        boolean big = false;
        for (var recipe : io.github.xiaod.deepseekcompanion.integrations.CraftRecipes.find(step.item())) {
            if (recipe.width() > 2 || recipe.height() > 2) {
                big = true;
                break;
            }
        }
        if (table != null && big) {
            blockPos = table;
            gridWidth = 3;
            mode = Mode.CRAFT;
        } else if (!big) {
            gridWidth = 2;
            mode = Mode.CRAFT_INV;
        } else {
            treeQueue.clear();
            treeMode = false;
            BotBrain.tell("要合 " + step.item() + " 得有工作台，先 .xd place crafting_table");
            return false;
        }
        phase = Phase.FIND;
        BotBrain.tell("合成 " + step.item() + "（" + step.crafts() + " 次）");
        return true;
    }

    private static boolean startSingleCraft(MinecraftClient mc, String itemId) {`;
if (s.includes(ctorFrom)) {
  s = s.replace(ctorFrom, ctorTo);
  console.log('craft() wrapped');
} else {
  console.log('MISS craft() anchor');
}

// 3) when a craft finishes, continue the tree
const clicksFrom = `                    if (mode == Mode.SMELT) {
                        phase = Phase.WAIT;
                        until = now + 12_000L;
                    } else {
                        mode = Mode.NONE;
                    }`;
const clicksTo = `                    if (mode == Mode.SMELT) {
                        phase = Phase.WAIT;
                        until = now + 12_000L;
                    } else if (treeMode) {
                        mode = Mode.NONE;
                        startTreeStep(mc);
                    } else {
                        mode = Mode.NONE;
                    }`;
if (s.includes(clicksFrom)) {
  s = s.replace(clicksFrom, clicksTo);
  console.log('click completion hooked');
} else {
  console.log('MISS clicks anchor');
}

// 4) cancel() clears the tree state
const cancelFrom = '    public static void cancel(MinecraftClient mc) {\n        QUEUE.clear();\n        mode = Mode.NONE;';
const cancelTo = '    public static void cancel(MinecraftClient mc) {\n        QUEUE.clear();\n        treeQueue.clear();\n        treeMode = false;\n        mode = Mode.NONE;';
if (s.includes(cancelFrom)) {
  s = s.replace(cancelFrom, cancelTo);
  console.log('cancel hooked');
} else {
  console.log('MISS cancel anchor');
}

writeFileSync(path, s, 'utf8');
console.log('changed:', before !== s);
