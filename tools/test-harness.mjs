// Test-harness improvements: longer settle time, visible craft plan, and `.xd inv`.
import { readFileSync, writeFileSync } from 'node:fs';

const base = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/';
const edits = [];

function patch(file, from, to, label) {
  const path = base + file;
  let s = readFileSync(path, 'utf8');
  if (!s.includes(from)) {
    edits.push('MISS ' + label);
    return;
  }
  writeFileSync(path, s.replace(from, to), 'utf8');
  edits.push('ok   ' + label);
}

// 1) let the world settle (and the bot stop dying) before the command queue runs
patch('bot/TestDriver.java', 'START_DELAY_MS = 6000L', 'START_DELAY_MS = 12000L', 'test start delay');
patch('bot/TestDriver.java', 'STEP_MS = 2500L', 'STEP_MS = 3000L', 'test step delay');

// 2) log the planned craft tree so failures are diagnosable from the log alone
patch(
  'bot/ContainerController.java',
  `        List<io.github.xiaod.deepseekcompanion.integrations.CraftTree.Step> plan =
                io.github.xiaod.deepseekcompanion.integrations.CraftTree.plan(mc, itemId);`,
  `        List<io.github.xiaod.deepseekcompanion.integrations.CraftTree.Step> plan =
                io.github.xiaod.deepseekcompanion.integrations.CraftTree.plan(mc, itemId);
        io.github.xiaod.deepseekcompanion.DeepseekCompanionClient.LOGGER.info(
                "[XiaoD] craft plan for {}: {}", itemId, plan);`,
  'log craft plan'
);

// 3) `.xd inv` so the harness can see what the bot actually holds
patch(
  'command/LocalCommandHandler.java',
  'case "craft" -> craft(arg);',
  `case "craft" -> craft(arg);
            case "inv" -> say(io.github.xiaod.deepseekcompanion.bot.ActionExecutor.execute(
                    MinecraftClient.getInstance(),
                    new io.github.xiaod.deepseekcompanion.chat.Intent("",
                            io.github.xiaod.deepseekcompanion.bot.BotAction.INVENTORY, "")).observation());`,
  '.xd inv'
);

console.log(edits.join('\n'));
