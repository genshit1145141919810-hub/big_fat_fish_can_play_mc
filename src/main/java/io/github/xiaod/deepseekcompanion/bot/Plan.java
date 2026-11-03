package io.github.xiaod.deepseekcompanion.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划：目标 + 一串按顺序执行的动作。这是"MC 本土 agent"的中间表示。
 *
 * <p>关键约束：计划里的每一步仍然是 {@link BotAction} 枚举 + 同样严格的参数校验，
 * 所以规划层<b>没有</b>扩大模型的权限——它只是把"一次一个动作"变成"一串合法动作"。
 * 查询动作不允许进计划（计划是盲执行的，拿不到中途读数）。</p>
 *
 * <p>模型输出：{@code {"say":"...","plan":[{"act":"goto","arg":"100 64 -200"}, ...]}}</p>
 */
public record Plan(String say, List<Step> steps) {

    public record Step(BotAction action, String arg) {
        @Override
        public String toString() {
            return arg == null || arg.isBlank() ? action.id() : action.id() + " " + arg;
        }
    }

    /** 计划最多几步（硬上限，防模型写长篇小说）。 */
    public static final int HARD_MAX_STEPS = 20;

    public static Plan parse(String raw, int maxSteps) throws ActionValidator.FormatException {
        String json = extractJson(raw);
        if (json == null) {
            throw new ActionValidator.FormatException("没有 JSON（要 {\"plan\":[...]}）");
        }
        JsonObject obj;
        try {
            obj = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new ActionValidator.FormatException("JSON 解析失败");
        }
        String say = obj.has("say") && !obj.get("say").isJsonNull()
                ? ActionValidator.sanitizeSay(obj.get("say").getAsString()) : "";
        if (!obj.has("plan") || !obj.get("plan").isJsonArray()) {
            throw new ActionValidator.FormatException("缺少 plan 数组");
        }
        JsonArray array = obj.getAsJsonArray("plan");
        int limit = Math.max(1, Math.min(maxSteps, HARD_MAX_STEPS));
        List<Step> steps = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new ActionValidator.FormatException("plan 里每一项都必须是对象");
            }
            JsonObject item = element.getAsJsonObject();
            String actRaw = item.has("act") && !item.get("act").isJsonNull() ? item.get("act").getAsString() : "";
            String argRaw = item.has("arg") && !item.get("arg").isJsonNull() ? item.get("arg").getAsString() : "";
            BotAction action = BotAction.byId(actRaw);
            if (action == null) {
                throw new ActionValidator.FormatException("计划里有未知动作: " + actRaw);
            }
            if (action.isQuery()) {
                throw new ActionValidator.FormatException("计划里不能放查询动作(" + action.id() + ")，要信息就先单独查");
            }
            if (action == BotAction.IDLE) {
                continue; // 空步骤直接丢掉，不该让计划白等
            }
            steps.add(new Step(action, ActionValidator.validateArg(action, argRaw)));
        }
        if (steps.isEmpty()) {
            throw new ActionValidator.FormatException("plan 里一个有效步骤都没有");
        }
        if (steps.size() > limit) {
            throw new ActionValidator.FormatException("步数太多（最多 " + limit + " 步）");
        }
        return new Plan(say, List.copyOf(steps));
    }

    /** 从可能带 markdown 围栏/前后缀的回复里抠出第一个 JSON 对象。 */
    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                sb.append(" → ");
            }
            sb.append(i + 1).append('.').append(steps.get(i));
        }
        return sb.toString();
    }
}
