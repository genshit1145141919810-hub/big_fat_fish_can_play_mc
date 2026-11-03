// Wire the perception layer: new query actions, snapshot enrichment, prompt hint, event notes.
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

// 1) new query actions
patch('bot/BotAction.java',
  '    WAYPOINTS("waypoints", "列出小地图里的航点", Kind.QUERY, ArgKind.NONE);',
  `    WAYPOINTS("waypoints", "列出小地图里的航点", Kind.QUERY, ArgKind.NONE),
    LOOK("look", "看看自己正对着什么方块或生物", Kind.QUERY, ArgKind.NONE),
    SCAN("scan", "扫附近某种方块的位置和数量（arg=方块 ID）", Kind.QUERY, ArgKind.BLOCKS),
    DANGER("danger", "列出附近的敌对生物、距离和血量", Kind.QUERY, ArgKind.NONE),
    CONTAINERS("containers", "附近有哪些箱子、熔炉、工作台等站点", Kind.QUERY, ArgKind.NONE),
    PATH("path", "看自己当前任务/计划状态，判断是不是卡住了", Kind.QUERY, ArgKind.NONE),
    DROPS("drops", "看脚边掉落的物品（挖了没捡会误判材料）", Kind.QUERY, ArgKind.NONE),
    PLAYERS("players", "看看附近有哪些玩家、拿着什么", Kind.QUERY, ArgKind.NONE);`,
  'query actions');

// 2) executor
patch('bot/ActionExecutor.java',
  '            case WAYPOINTS -> waypoints(mc);',
  `            case WAYPOINTS -> waypoints(mc);
            case LOOK -> Perception.look(mc);
            case SCAN -> Perception.scan(mc, arg);
            case DANGER -> Perception.danger(mc);
            case CONTAINERS -> Perception.containers(mc);
            case PATH -> Perception.path(mc);
            case DROPS -> Perception.drops(mc);
            case PLAYERS -> Perception.players(mc);`,
  'executor cases');

// 3) snapshot enrichment
patch('bot/GameSnapshot.java',
  `        String env = ModLinks.summary();`,
  `        sb.append(Perception.snapshotSuffix(mc));
        String env = ModLinks.summary();`,
  'snapshot suffix');

// 4) prompt hint: query before acting when uncertain
patch('chat/Prompts.java',
  '        sb.append("8. 要换维度（下界/末地）就用 portal 走门',
  `        sb.append("8. 不确定就先查，再动手：look（正对着什么）、danger（附近危险）、"
                + "scan（某种方块在哪）、containers（附近有什么站点）、path（是不是卡住了）、"
                + "drops（脚边掉落）。查完再决定做什么，比瞎猜省事。\\n");
        sb.append("9. 要换维度（下界/末地）就用 portal 走门`,
  'prompt hint');

// 5) event notes
patch('bot/PlanRunner.java',
  '        DeepseekCompanionClient.LOGGER.info("[XiaoD] plan replan #{}/{}: {}", replans, max, reason);',
  '        Perception.note("计划失败：" + reason);\n        DeepseekCompanionClient.LOGGER.info("[XiaoD] plan replan #{}/{}: {}", replans, max, reason);',
  'note plan failure');

patch('bot/FleeController.java',
  '        DeepseekCompanionClient.LOGGER.info("[XiaoD] mining interrupted by {}, fleeing", threat.getName().getString());',
  '        Perception.note("被 " + threat.getName().getString() + " 打断，先躲");\n        DeepseekCompanionClient.LOGGER.info("[XiaoD] mining interrupted by {}, fleeing", threat.getName().getString());',
  'note flee');

patch('bot/BlockInController.java',
  '        DeepseekCompanionClient.LOGGER.info("[XiaoD] block-in against {}", target.getName().getString());',
  '        Perception.note("被 " + target.getName().getString() + " 逼住，垒墙");\n        DeepseekCompanionClient.LOGGER.info("[XiaoD] block-in against {}", target.getName().getString());',
  'note block-in');

console.log(out.join('\n'));
