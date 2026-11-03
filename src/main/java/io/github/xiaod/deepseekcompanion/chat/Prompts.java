package io.github.xiaod.deepseekcompanion.chat;

import io.github.xiaod.deepseekcompanion.bot.BotAction;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;

/** Prompt construction. The action catalogue is generated from the enum, so
 * the model can never be told about an action the code cannot execute. */
public final class Prompts {
    private Prompts() {
    }

    public static String system(String snapshot, boolean autonomous) {
        CompanionConfig cfg = CompanionConfig.get();
        StringBuilder sb = new StringBuilder();
        sb.append(cfg.persona).append("\n\n");
        sb.append("你在游戏里的实时状态：\n").append(snapshot).append("\n\n");
        sb.append("你能做的动作（只能从这里选，禁止发明新动作或输出命令）：\n");
        sb.append(BotAction.catalogue());
        sb.append(io.github.xiaod.deepseekcompanion.bot.ActionRegistry.catalogueSuffix()).append('\n');
        sb.append("输出规则：\n");
        sb.append("1. 只输出一个 JSON 对象，不要 markdown、不要代码块、不要解释。\n");
        sb.append("2. 格式：{\"say\":\"对玩家说的话\",\"act\":\"动作\",\"arg\":\"参数\"}\n");
        sb.append("3. say 用中文口语，像队友说话，不超过 40 字；不行动时 act 填 idle。\n");
        sb.append("4. arg 只在 mine 时填方块 ID（如 iron_ore），其它动作留空字符串。\n");
        sb.append("5. 永远不要输出以 # 或 / 开头的内容，永远不要自己拼命令。\n");
        sb.append("6. 家在游戏状态里给了坐标，家周围那块正方形是保护区：区内只能交互（开箱子、用工作台/熔炉/床等），"
                + "严禁挖掘；mine/chop 在保护区内会被直接拦下，要挖就走远一点。\n");
        sb.append("7. 默认只做一件事：一个动作就能推进，就用 act。只有你确信「一步做不到」"
                + "（得先去别处、要连着好几个动作、或者单步失败过），才输出 "
                + "{\"say\":\"...\",\"need_plan\":\"目标描述\"}，由系统去做多步规划——"
                + "不要自己在 act 里塞多步，也不要输出计划。\n");
        sb.append("8. 不确定就先查，再动手：look（正对着什么）、danger（附近危险）、"
                + "scan（某种方块在哪）、containers（附近有什么站点）、path（是不是卡住了）、"
                + "drops（脚边掉落）。查完再决定做什么，比瞎猜省事。\n");
        sb.append("9. 要换维度（下界/末地）就用 portal 走门，不要凭空 goto 另一边的坐标；"
                + "换维度后之前的坐标全部失效，要用新维度里的坐标。\n");
        if (autonomous) {
            sb.append("9. 现在没人跟你说话，你自己判断当下该做什么，别重复上一件事。\n");
        }
        return sb.toString();
    }

    public static String correction(String reason) {
        return "你上一次的输出不合法（" + reason + "）。只允许上面列出的动作，"
                + "现在重新只输出一个 JSON 对象，不要任何其它文字。";
    }

    /**
     * 规划提示：只在单步确实做不到时才会用到（agent 层的升级路径）。
     * 计划和单步共用同一套动作词表，只是允许把它们串起来。
     */
    public static String plan(String snapshot, String goal) {
        CompanionConfig cfg = CompanionConfig.get();
        int maxSteps = Math.max(1, Math.min(cfg.planMaxSteps, io.github.xiaod.deepseekcompanion.bot.Plan.HARD_MAX_STEPS));
        return cfg.persona + "\n\n"
                + "你在游戏里的实时状态：\n" + snapshot + "\n\n"
                + "玩家要你完成的事：\n" + goal + "\n\n"
                + "这件事一步做不到，所以现在把它拆成按顺序执行的动作。只能从这个列表里选物理动作：\n"
                + BotAction.physicalCatalogue() + '\n'
                + "输出规则：\n"
                + "1. 只输出一个 JSON 对象，不要 markdown、不要解释。\n"
                + "2. 格式：{\"say\":\"对玩家说的话\",\"plan\":[{\"act\":\"动作\",\"arg\":\"参数\"}, ...]}\n"
                + "3. 2~" + maxSteps + " 步，每一步只做一件事，按顺序执行；失败的步骤会整单重来。\n"
                + "4. 计划里不能出现查询动作（nearby/inventory/stats 之类）；需要信息就先单独问一次。\n"
                + "5. 坐标不要瞎编：不确定位置就用会自动找目标的形式（如 mine/chop/store/take 只给物品或留空）。\n"
                + "6. 要跨维度就先写一步 portal，进门之后再写新维度的坐标（旧维度的坐标进门就作废）。\n"
                + "7. say 用中文口语，不超过 40 字。\n";
    }

    public static String planCorrection(String snapshot, String goal, String reason) {
        return plan(snapshot, goal)
                + "\n你上一次的计划不合法（" + reason + "）。现在重新输出一个合法的 JSON 计划，不要任何其它文字。\n";
    }

    /** 从单步回复里识别"我要升级成计划"。 */
    public static String extractNeedPlan(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser
                    .parseString(reply.substring(start, end + 1)).getAsJsonObject();
            for (String key : new String[]{"need_plan", "needPlan", "plan_request", "goal"}) {
                if (obj.has(key) && !obj.get(key).isJsonNull()) {
                    String value = obj.get(key).getAsString();
                    if (!value.isBlank() && obj.has("act") == false) {
                        return value.strip();
                    }
                }
            }
        } catch (Exception ignored) {
            // not JSON, or not a plan request
        }
        return null;
    }
}
