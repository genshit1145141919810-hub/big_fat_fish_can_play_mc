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
        sb.append("4. arg 按动作填，不用的留空字符串：mine/scan/use=方块 ID；"
                + "goto=三个整数坐标（如 100 64 -200）；craft/give/hold/place/acquire/brew/recipe/uses=物品 ID；"
                + "cook/store/take=物品 ID 或逗号分隔的列表；hunt=生物类型；station/enchant/portal=字符串；其余留空。\n");
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
        sb.append("10. 做不成别硬来：状态里「刚刚」和「正在做」写着的失败原因要当回事，"
                + "同一个动作+同一参数失败过就别再重复，换材料/换位置/换动作，"
                + "或者 need_plan 交给人来拆步骤。\n");
        sb.append("11. 状态里显示「正在做」时说明手上还有活：这时只回答、别塞新的长动作，"
                + "除非玩家明确让你停下或改做别的。\n");
        if (autonomous) {
            sb.append("12. 现在没人跟你说话，你自己判断当下该做什么，别重复上一件事。\n");
        }
        return sb.toString();
    }

    public static String correction(String reason) {
        return "你上一次的输出不合法（" + reason + "）。只允许上面列出的动作，"
                + "现在重新只输出一个 JSON 对象，不要任何其它文字。";
    }

    /**
     * 动作没做成时喂回给模型的一问：把"做了什么、为什么失败"讲清楚，
     * 让它自己换一个做法，而不是闷头重复同一个动作。
     */
    public static String retryAfterFailure(String original, String action, String arg, String reason) {
        StringBuilder sb = new StringBuilder(original == null ? "" : original.strip());
        sb.append("\n【上一动作没做成】").append(action);
        if (arg != null && !arg.isBlank()) {
            sb.append(" ").append(arg.strip());
        }
        sb.append(" 失败了");
        if (reason != null && !reason.isBlank()) {
            sb.append("，原因：").append(reason.strip());
        }
        sb.append("。\n现在换一个做法：换动作、换位置或换参数（别原样重试同一个）；"
                + "如果确实一步做不到，就输出 {\"need_plan\":\"目标\"} 让系统拆步骤。");
        return sb.toString();
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
                + "\n上一次没规划好：" + reason + "\n补充规则：\n"
                + "- 上面状态里背包没有的东西，你手上就没有：别写 give/take 这种假设已经拿到的步骤，"
                + "要就先写挖/合/取。\n"
                + "- 已经在挖、还没到手的目标，就继续挖或者换个位置挖，不要改成「送回去」。\n"
                + "- 每步都要能验证结果（挖到什么、合出什么、走到哪），别写空泛的步骤。\n"
                + "\n只输出一个合法的 JSON 计划对象 {\"say\":\"...\",\"plan\":[{\"act\":\"...\",\"arg\":\"...\"}]}，"
                + "不要任何其它文字。\n";
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
