package io.github.xiaod.deepseekcompanion.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xiaod.deepseekcompanion.bot.ActionValidator;
import io.github.xiaod.deepseekcompanion.bot.BotAction;

import java.util.Locale;

/**
 * A validated decision. {@code action} is null when the bot only talks.
 * Instances can only be produced through {@link #parse(String)} or
 * {@link #keyword(String)}, both of which validate every field against the
 * game registry and the closed action enum.
 */
public record Intent(String say, BotAction action, String arg, String pluginAction) {

    public Intent(String say, BotAction action, String arg) {
        this(say, action, arg, null);
    }

    public static Intent sayOnly(String say) {
        return new Intent(ActionValidator.sanitizeSay(say), null, "");
    }

    /**
     * Strict parser for model output. Throws when the model invents an action
     * or passes an argument the game registry does not know.
     */
    public static Intent parse(String raw) throws ActionValidator.FormatException {
        if (raw == null || raw.isBlank()) {
            return sayOnly("");
        }
        String cleaned = raw.strip();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            // Prose reply: allowed, but it can never carry an action.
            return sayOnly(cleaned);
        }
        JsonObject obj;
        try {
            obj = JsonParser.parseString(cleaned.substring(start, end + 1)).getAsJsonObject();
        } catch (Exception e) {
            return sayOnly(cleaned);
        }
        String say = obj.has("say") && !obj.get("say").isJsonNull() ? obj.get("say").getAsString() : "";
        String actRaw = obj.has("act") && !obj.get("act").isJsonNull() ? obj.get("act").getAsString() : "idle";
        String argRaw = obj.has("arg") && !obj.get("arg").isJsonNull() ? obj.get("arg").getAsString() : "";

        String act = actRaw.strip().toLowerCase(Locale.ROOT);
        if (act.isEmpty() || act.equals("none") || act.equals("null")) {
            return new Intent(ActionValidator.sanitizeSay(say), null, "");
        }
        BotAction action = BotAction.byId(act);
        if (action == null) {
            io.github.xiaod.deepseekcompanion.api.ActionProvider provider =
                    io.github.xiaod.deepseekcompanion.bot.ActionRegistry.find(act);
            if (provider == null) {
                throw new ActionValidator.FormatException("未知动作: " + actRaw);
            }
            String validated;
            try {
                validated = provider.validate(argRaw);
            } catch (IllegalArgumentException bad) {
                throw new ActionValidator.FormatException(bad.getMessage());
            }
            return new Intent(ActionValidator.sanitizeSay(say), null, validated, provider.id());
        }
        String arg = ActionValidator.validateArg(action, argRaw);
        return new Intent(ActionValidator.sanitizeSay(say), action, arg);
    }

    /**
     * No-API-key / network-failure fallback. Uses the same closed action
     * vocabulary as the model, so behaviour stays consistent.
     */
    public static Intent keyword(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (containsAny(t, "跟我", "过来", "来这", "跟上", "follow me", "come here", "come")) {
            return new Intent(pick(t, "来了来了。", "跟上了，你慢点。", "别跑那么快啊。"), BotAction.FOLLOW, "");
        }
        if (containsAny(t, "别动", "等着", "停下", "待着", "stop", "stay", "wait")) {
            return new Intent(pick(t, "行，我在这儿等着。", "好，原地待命。"), BotAction.STOP, "");
        }
        if (containsAny(t, "砍树", "木头", "chop", "wood", "tree")) {
            return new Intent(pick(t, "砍树去了，等我把这一片清干净。"), BotAction.CHOP, "");
        }
        if (containsAny(t, "挖", "矿", "煤", "铁", "金", "钻石", "mine", "dig", "ore")) {
            return new Intent(pick(t, "行，我找个矿点。", "开挖，你先自己玩会儿。"), BotAction.MINE, blockFor(t));
        }
        if (containsAny(t, "吃", "饿", "eat", "hungry", "food")) {
            return new Intent(pick(t, "我也吃点，别饿死了。"), BotAction.EAT, "");
        }
        if (containsAny(t, "打", "怪", "保护", "救我", "fight", "help", "guard")) {
            return new Intent(pick(t, "来了，交给我。", "别怕，我顶上。"), BotAction.FIGHT, "");
        }
        return sayOnly("");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String pick(String seed, String... options) {
        return options[Math.abs(seed.hashCode()) % options.length];
    }

    /** Maps loose player words ("钻石", "diamond") to a real block id. */
    public static String blockFor(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (t.contains("钻石") || t.contains("diamond")) {
            return "diamond_ore";
        }
        if (t.contains("铁") || t.contains("iron")) {
            return "iron_ore";
        }
        if (t.contains("金") || t.contains("gold")) {
            return "gold_ore";
        }
        if (t.contains("煤") || t.contains("coal")) {
            return "coal_ore";
        }
        if (t.contains("铜") || t.contains("copper")) {
            return "copper_ore";
        }
        if (t.contains("红石") || t.contains("redstone")) {
            return "redstone_ore";
        }
        if (t.contains("青金") || t.contains("lapis")) {
            return "lapis_ore";
        }
        if (t.contains("绿宝石") || t.contains("emerald")) {
            return "emerald_ore";
        }
        return "iron_ore";
    }
}
