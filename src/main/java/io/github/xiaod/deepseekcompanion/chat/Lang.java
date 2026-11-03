package io.github.xiaod.deepseekcompanion.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.client.MinecraftClient;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 i18n：中文是"源语言"，其它语言用一张"中文 → 译文"的表来替换。
 *
 * <p>这样做的好处是不用改几百个调用点：所有给玩家看的文字都经过
 * {@link io.github.xiaod.deepseekcompanion.bot.BotBrain#say} /
 * {@link io.github.xiaod.deepseekcompanion.bot.BotBrain#tell} 和
 * {@code LocalCommandHandler.say}，在这三处统一翻译即可。</p>
 *
 * <p>译文文件放在 {@code assets/big_fat_fish_can_play_mc/lang/<语言>.json}，
 * 语言代码来自游戏设置（如 {@code en_us}）。缺条目时回退中文原文。</p>
 */
public final class Lang {
    private static final String ROOT = "/assets/big_fat_fish_can_play_mc/lang/";
    /** 带占位符的规则：%s 表示"任意内容"，%d 表示数字；命中后按顺序替换。 */
    private static final List<Rule> RULES = new ArrayList<>();
    private static Map<String, String> table = Map.of();
    private static String loadedCode = "";
    private static boolean unavailable;

    private record Rule(Pattern pattern, String english) {
    }

    private Lang() {
    }

    /** 当前游戏语言代码，例如 {@code en_us} / {@code zh_cn}。 */
    public static String languageCode() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String code = mc != null && mc.options != null ? mc.options.language : "zh_cn";
        if (code == null || code.isBlank()) {
            code = "zh_cn";
        }
        return code.toLowerCase(Locale.ROOT);
    }

    /** 中文源文 → 当前语言。没有译文就原样返回。 */
    public static String t(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        ensureLoaded();
        if (table.isEmpty()) {
            return source;
        }
        String hit = table.get(source);
        if (hit != null) {
            return hit;
        }
        // 拼出来的句子：用规则表兜一层（"弄不到 X：Y" 之类）
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(source);
            if (m.matches()) {
                String out = rule.english();
                for (int i = 1; i <= m.groupCount(); i++) {
                    String group = m.group(i);
                    if (group == null) {
                        group = "";
                    }
                    out = out.replaceFirst("%s", Matcher.quoteReplacement(group));
                }
                return out;
            }
        }
        return source;
    }

    /** 带参数的模板：{@code Lang.f("目标：%s", goal)}。参数缺失时保留 %s。 */
    public static String f(String template, Object... args) {
        String text = template;
        for (Object arg : args) {
            String value = String.valueOf(arg);
            int idx = text.indexOf("%s");
            if (idx < 0) {
                break;
            }
            text = text.substring(0, idx) + value + text.substring(idx + 2);
        }
        return t(text);
    }

    private static void ensureLoaded() {
        String code = languageCode();
        if (code.equals(loadedCode)) {
            return;
        }
        loadedCode = code;
        table = Map.of();
        RULES.clear();
        if (code.startsWith("zh")) {
            return; // 源语言，不用翻
        }
        Map<String, String> loaded = load(code);
        if (loaded == null && !code.startsWith("en")) {
            loaded = load("en_us"); // 别的语言还没翻：先用英文垫着
        }
        if (loaded == null) {
            unavailable = true;
            DeepseekCompanionClient.LOGGER.info("[XiaoD] no translation for {}, keeping Chinese", code);
            return;
        }
        table = loaded;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] language {} loaded ({} strings)", code, table.size());
    }

    private static Map<String, String> load(String code) {
        try (var stream = Lang.class.getResourceAsStream(ROOT + code + ".json")) {
            if (stream == null) {
                return null;
            }
            JsonObject json = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> map = new LinkedHashMap<>();
            JsonObject strings = json.has("strings") ? json.getAsJsonObject("strings") : json;
            for (var entry : strings.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    map.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            if (json.has("rules")) {
                for (var element : json.getAsJsonArray("rules")) {
                    JsonObject rule = element.getAsJsonObject();
                    String zh = rule.get("zh").getAsString();
                    String en = rule.get("en").getAsString();
                    String regex = Pattern.quote(zh);
                    StringBuilder builder = new StringBuilder("^");
                    int from = 0;
                    while (true) {
                        int at = zh.indexOf("%s", from);
                        if (at < 0) {
                            builder.append(Pattern.quote(zh.substring(from)));
                            break;
                        }
                        builder.append(Pattern.quote(zh.substring(from, at))).append("(.+?)");
                        from = at + 2;
                    }
                    builder.append('$');
                    RULES.add(new Rule(Pattern.compile(builder.toString()), en));
                }
            }
            return map;
        } catch (Throwable t) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] cannot read lang/{}", code, t);
            return null;
        }
    }

    /** 当前是不是英文环境（只用于日志/调试判断）。 */
    public static boolean isEnglish() {
        ensureLoaded();
        return languageCode().startsWith("en") && !table.isEmpty();
    }

    /** 有没有译文表（诊断用）。 */
    public static boolean hasTable() {
        ensureLoaded();
        return !table.isEmpty();
    }

    /** 数量，给 {@code tools/missing-lang.cjs} 之类的工具对照用。 */
    public static int size() {
        ensureLoaded();
        return table.size();
    }

    /** 未翻译时 {@link #unavailable} 的只读视图，避免编译器把字段当常量。 */
    public static boolean unavailable() {
        return unavailable;
    }
}
