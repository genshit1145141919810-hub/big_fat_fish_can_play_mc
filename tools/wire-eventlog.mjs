// Wire the event log: hooks at real points + prompt/snapshot/query exposure.
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

// 1) action + executor
patch('bot/BotAction.java',
  '    PLAYERS("players", "看看附近有哪些玩家、拿着什么", Kind.QUERY, ArgKind.NONE);',
  '    PLAYERS("players", "看看附近有哪些玩家、拿着什么", Kind.QUERY, ArgKind.NONE),\n    EVENTS("events", "看看刚才发生了什么（受伤/死亡/换维度/任务成败）", Kind.QUERY, ArgKind.NONE);',
  'action events');

patch('bot/ActionExecutor.java',
  '            case PLAYERS -> Perception.players(mc);',
  '            case PLAYERS -> Perception.players(mc);\n            case EVENTS -> EventLog.recent(10);',
  'executor events');

// 2) tick: health watcher + reset on world null
patch('bot/BotBrain.java',
  'AcquireTask.tick(mc); Butler.tick(mc, CompanionConfig.get()); Memory.flushIfDirty();',
  'AcquireTask.tick(mc); Butler.tick(mc, CompanionConfig.get()); Memory.flushIfDirty(); EventLog.watchHealth(mc);',
  'watch health');

patch('bot/BotBrain.java',
  '            DimensionWatch.reset(); avoidanceApplied = false;',
  '            DimensionWatch.reset(); avoidanceApplied = false; EventLog.reset();',
  'reset events');

// 3) snapshot: recent events (state + change, side by side)
patch('bot/GameSnapshot.java',
  '        sb.append(Perception.snapshotSuffix(mc));',
  `        String recentEvents = EventLog.summary();
        if (!recentEvents.isBlank()) {
            sb.append(" | 刚刚：").append(recentEvents);
        }
        sb.append(Perception.snapshotSuffix(mc));`,
  'snapshot events');

// 4) hooks: dimension change, chat, plan failure, acquire failure/success, mining
patch('bot/DimensionWatch.java',
  '        DeepseekCompanionClient.LOGGER.info("[XiaoD] dimension {} -> {}", from, now);',
  '        DeepseekCompanionClient.LOGGER.info("[XiaoD] dimension {} -> {}", from, now);\n        EventLog.note("换维度", "从 " + shortDim(from) + " 到 " + shortDim(now));',
  'hook dimension');

patch('bot/DimensionWatch.java',
  '    /** 断线/回主菜单后，下一次进世界重新认维度。 */',
  `    private static String shortDim(String dim) {
        if (dim == null || dim.isBlank()) {
            return "?";
        }
        int colon = dim.indexOf(':');
        return colon < 0 ? dim : dim.substring(colon + 1);
    }

    /** 断线/回主菜单后，下一次进世界重新认维度。 */`,
  'dimension short name');

patch('chat/ChatCoordinator.java',
  '        BotBrain.onPlayerSpoke(line.sender(), addressed);',
  '        io.github.xiaod.deepseekcompanion.bot.EventLog.note("聊天", line.sender() + " 对小D 说：" + shorten(addressed));\n        BotBrain.onPlayerSpoke(line.sender(), addressed);',
  'hook chat');

patch('chat/ChatCoordinator.java',
  '    /** 自检：{@code .xd chat} 打印最近一条聊天原文 + 判定结果。 */',
  `    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 30 ? text : text.substring(0, 30) + "…";
    }

    /** 自检：{@code .xd chat} 打印最近一条聊天原文 + 判定结果。 */`,
  'chat shorten helper');

patch('bot/PlanRunner.java',
  '        Perception.note("计划失败：" + reason);',
  '        Perception.note("计划失败：" + reason);\n        EventLog.note("任务失败", goal + " → " + reason);',
  'hook plan failure');

patch('bot/AcquireTask.java',
  '        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire done: {} (now {})", itemId, have);',
  '        EventLog.note("获取成功", itemId + " 现在 " + have + " 个");\n        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire done: {} (now {})", itemId, have);',
  'hook acquire success');

patch('bot/AcquireTask.java',
  '        Memory.noteFailure("获取 " + itemId, reason);',
  '        Memory.noteFailure("获取 " + itemId, reason);\n        EventLog.note("获取失败", itemId + "：" + reason);',
  'hook acquire failure');

// 5) command: .xd events
patch('command/LocalCommandHandler.java',
  'case "see" -> see();',
  'case "see" -> see();\n            case "events" -> events();',
  'command events');

patch('command/LocalCommandHandler.java',
  '    /** 感知层自检：一次打印模型会看到的那些感知信息。 */',
  `    /** 看看最近发生了什么。 */
    private static void events() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            say("§c还没进世界");
            return;
        }
        for (String line : io.github.xiaod.deepseekcompanion.bot.EventLog.recent(12).split("\\n")) {
            say("§7" + line);
        }
    }

    /** 感知层自检：一次打印模型会看到的那些感知信息。 */`,
  'command events body');

console.log(out.join('\n'));
