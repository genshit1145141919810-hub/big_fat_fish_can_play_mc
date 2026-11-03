// Wire AcquireTask + Butler + Memory into the mod.
import { readFileSync, writeFileSync } from 'node:fs';

const base = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/';
const out = [];
function patch(file, from, to, label) {
  const p = base + file;
  let s = readFileSync(p, 'utf8');
  if (!s.includes(from)) { out.push('MISS ' + label); return; }
  writeFileSync(p, s.replace(from, to), 'utf8');
  out.push('ok   ' + label);
}

// 1) config switches
patch('config/CompanionConfig.java',
  '    public boolean enableChat = true;',
  '    public boolean enableChat = true;\n    /** 常驻杂役：捡掉落物、卡住自救、清垃圾。 */\n    public boolean enableButler = true;',
  'config butler flag');

// 2) new action
patch('bot/BotAction.java',
  '    PLACE("place", "把手上的方块放到脚边（工作台之类，arg=方块 ID）", Kind.PHYSICAL, ArgKind.ITEM),',
  '    PLACE("place", "把手上的方块放到脚边（工作台之类，arg=方块 ID）", Kind.PHYSICAL, ArgKind.ITEM),\n    ACQUIRE("acquire", "弄到某种东西（会自己挖/烧/合/从箱子取/打猎，arg=物品 ID）", Kind.PHYSICAL, ArgKind.ITEM),',
  'action ACQUIRE');

// 3) executor
patch('bot/ActionExecutor.java',
  '            case PLACE -> PlacementController.place(mc, intent.arg());',
  '            case PLACE -> PlacementController.place(mc, intent.arg());\n            case ACQUIRE -> AcquireTask.start(mc, intent.arg(), 1);',
  'executor ACQUIRE');

// 4) tick: acquire + butler, and memory flush
patch('bot/BotBrain.java',
  'PlanRunner.tick(mc); ActionRegistry.tickAll(PlanRunner.anyBusy());',
  'PlanRunner.tick(mc); ActionRegistry.tickAll(PlanRunner.anyBusy()); AcquireTask.tick(mc); Butler.tick(mc, CompanionConfig.get()); Memory.flushIfDirty();',
  'tick acquire/butler');

// 5) plan budget sees acquire
patch('bot/PlanRunner.java',
  '        return BlockInController.isBusy()',
  '        return AcquireTask.isBusy()\n                || BlockInController.isBusy()',
  'plan waits for acquire');

// 6) perception: show acquire state
patch('bot/Perception.java',
  '        sb.append(" | 挖掘 ").append(MiningController.isBusy() ? "进行中" : "空闲");',
  '        sb.append(" | 挖掘 ").append(MiningController.isBusy() ? "进行中" : "空闲");\n        if (AcquireTask.isBusy()) {\n            sb.append(" | ").append(AcquireTask.status());\n        }',
  'perception acquire');

// 7) memory hooks
patch('bot/HomeZone.java',
  '        CompanionConfig.save();',
  '        CompanionConfig.save();\n        Memory.setHome(pos, dim);',
  'memory home hook');

patch('bot/PlanRunner.java',
  '        Perception.note("计划失败：" + reason);',
  '        Perception.note("计划失败：" + reason);\n        io.github.xiaod.deepseekcompanion.bot.Memory.noteFailure(goal, reason);',
  'memory failure hook');

// 8) snapshot includes memory summary
patch('bot/GameSnapshot.java',
  '        sb.append(Perception.snapshotSuffix(mc));',
  '        sb.append(" | 记忆 ").append(Memory.summary());\n        sb.append(Perception.snapshotSuffix(mc));',
  'snapshot memory');

// 9) commands: .xd get <item>
patch('command/LocalCommandHandler.java',
  'case "craft" -> craft(arg);',
  `case "craft" -> craft(arg);
            case "get" -> get(arg);`,
  'command get');

patch('command/LocalCommandHandler.java',
  '    /** 合成（会自动展开原料树：缺木板/木棍会先合出来）。 */',
  `    /** 获取物品：背包→合成→熔炼→挖→箱子→打猎。 */
    private static void get(String arg) {
        try {
            String item = ActionValidator.validateItem(arg);
            boolean ok = io.github.xiaod.deepseekcompanion.bot.AcquireTask
                    .start(MinecraftClient.getInstance(), item, 1);
            say(ok ? "§7开始弄 " + item : "§c不知道要弄什么");
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    /** 合成（会自动展开原料树：缺木板/木棍会先合出来）。 */`,
  'command get body');

console.log(out.join('\n'));
