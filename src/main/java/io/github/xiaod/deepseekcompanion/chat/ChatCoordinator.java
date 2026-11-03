package io.github.xiaod.deepseekcompanion.chat;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.bot.BotBrain;
import io.github.xiaod.deepseekcompanion.bot.LoginController;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从聊天栏里认出"有人在对小D 说话"。
 *
 * <p>为什么不能只认 {@code Name: text}：服务器插件太爱改格式了——
 * 加等级/世界/称号前缀（{@code [LVL 3] Steve: hi}）、把分隔符换成 {@code »}、
 * 把 {@code @名字} 渲染成可点击组件、甚至整行换个模板。
 * 所以这里做<b>多级降级解析</b>：越靠后的规则越宽松，但都会做边界检查，
 * 不会因为消息里恰好出现 bot 名字就乱回。</p>
 */
public final class ChatCoordinator {
    private static final Pattern ANGLE = Pattern.compile("^<([A-Za-z0-9_]{1,16})>\\s+(.*)$", Pattern.DOTALL);
    private static final Pattern COLON = Pattern.compile(
            "^([A-Za-z0-9_]{1,16})\\s*[:：»>]\\s*(.*)$", Pattern.DOTALL);
    /** 插件常见前缀：{@code [世界]} {@code [LVL 3]} {@code 【VIP】} {@code (队伍)} {@code <系统>} */
    private static final Pattern DECORATION = Pattern.compile(
            "^(?:\\[[^\\]]{0,40}\\]|\\([^)]{0,40}\\)|【[^】]{0,40}】|〔[^〕]{0,40}〕|<[^>]{0,40}>)\\s*");
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");
    private static final String SEPARATORS = "：:，,、»>";

    /** 最近一条聊天原文与解析结果，给 {@code .xd chat} 自检用。 */
    private static String lastRaw = "";
    private static String lastParse = "（还没收到聊天）";

    private ChatCoordinator() {
    }

    private record Line(String sender, String body) {
    }

    public static void onIncoming(Text message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || message == null) {
            return;
        }
        if (!BotBrain.isActiveAccount(mc)) {
            return; // dormant on every account except the configured bot
        }
        String raw = message.getString();
        if (raw == null || raw.isBlank()) {
            return;
        }
        CompanionConfig cfg = CompanionConfig.get();
        LoginController.onChat(raw, cfg);

        // 不管调试开没开，都记住最后一条：服务器改了聊天格式时 .xd chat 能立刻看清
        lastRaw = raw;
        boolean debug = cfg.chatDebug;
        if (debug) {
            lastParse = "未处理";
        }

        String clean = normalize(raw);
        if (cfg.enableAdvancementReaction && reactToAdvancement(clean, cfg)) {
            lastParse = "成就广播";
            return;
        }
        if (!cfg.enableChat) {
            lastParse = "聊天关闭（enableChat=false）";
            return;
        }
        Line line = parse(raw, clean, cfg);
        if (line == null) {
            lastParse = "没认出是玩家消息";
            return;
        }
        if (line.sender().equalsIgnoreCase(mc.player.getName().getString())) {
            lastParse = "自己的回声，忽略";
            return;
        }
        String addressed = stripTrigger(line.body(), cfg.botName);
        if (addressed == null && cfg.chatTriggerAnywhere) {
            // 宽松模式：只要句子里有独立的小D 名字就算叫我（插件把 @ 吃掉时救急用）
            addressed = stripLoose(line.body(), cfg.botName);
        }
        if (addressed == null) {
            lastParse = "来自 " + line.sender() + "，但没叫小D：" + line.body();
            return;
        }
        lastParse = "交给模型 | 发送者=" + line.sender() + " | 内容=" + addressed;
        if (debug) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] chat raw='{}' -> sender={} body='{}'",
                    raw, line.sender(), line.body());
        }
        io.github.xiaod.deepseekcompanion.bot.EventLog.note("聊天", line.sender() + " 对小D 说：" + shorten(addressed));
        BotBrain.onPlayerSpoke(line.sender(), addressed);
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 30 ? text : text.substring(0, 30) + "…";
    }

    /** 自检：{@code .xd chat} 打印最近一条聊天原文 + 判定结果。 */
    public static String lastSeen() {
        return "原文: " + lastRaw + "  §7|§f  判定: " + lastParse;
    }

    private static boolean reactToAdvancement(String clean, CompanionConfig cfg) {
        String owner = cfg.ownerName == null ? "" : cfg.ownerName.strip();
        for (Pattern pattern : new Pattern[]{
                Pattern.compile("^([A-Za-z0-9_]{1,16}) has (?:made the advancement|completed the challenge|reached the goal) \\[(.+)]$"),
                Pattern.compile("^([A-Za-z0-9_]{1,16}) (?:达成了进度|达成了目标|完成了挑战|获得了进度) \\[(.+)]$")}) {
            Matcher matcher = pattern.matcher(clean);
            if (matcher.matches() && matcher.group(1).equalsIgnoreCase(owner)) {
                BotBrain.onOwnerAdvancement(matcher.group(2));
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 解析

    private static String normalize(String raw) {
        String text = ZERO_WIDTH.matcher(raw).replaceAll("");
        return text.replace('\uFF20', '@').replace('\u00A0', ' ').strip();
    }

    private static Line parse(String raw, String clean, CompanionConfig cfg) {
        // 1) 原版 <Name> body
        Line line = matchAngle(clean);
        if (line != null) {
            return line;
        }
        // 2) 纯 Name: body
        line = matchColon(clean);
        if (line != null) {
            return line;
        }
        // 3) 剥掉插件前缀后再试
        String undecorated = stripDecorations(clean);
        if (!undecorated.equals(clean)) {
            line = matchAngle(undecorated);
            if (line == null) {
                line = matchColon(undecorated);
            }
            if (line != null) {
                return line;
            }
        }
        // 4) 行里出现"主人名 + 分隔符"（前缀/后缀都能忍）
        String owner = cfg.ownerName == null ? "" : cfg.ownerName.strip();
        if (!owner.isEmpty()) {
            int index = indexOfIgnoreCase(clean, owner);
            while (index >= 0) {
                String after = clean.substring(index + owner.length()).strip();
                if (!after.isEmpty() && "：:»>".indexOf(after.charAt(0)) >= 0) {
                    String body = after.substring(1).strip();
                    if (!body.isEmpty()) {
                        return new Line(owner, body);
                    }
                }
                index = indexOfIgnoreCase(clean, owner, index + owner.length());
            }
        }
        // 5) 最后兜底：整行里出现了 @botName（被插件改过格式也能救）
        String bot = cfg.botName == null || cfg.botName.isBlank() ? "XiaoD" : cfg.botName.strip();
        if (containsMention(clean, bot)) {
            String sender = owner.isEmpty() ? "某人" : owner;
            return new Line(sender, undecorated);
        }
        return null;
    }

    private static Line matchAngle(String text) {
        Matcher matcher = ANGLE.matcher(text);
        return matcher.matches() ? new Line(matcher.group(1), matcher.group(2).strip()) : null;
    }

    private static Line matchColon(String text) {
        Matcher matcher = COLON.matcher(text);
        return matcher.matches() ? new Line(matcher.group(1), matcher.group(2).strip()) : null;
    }

    private static String stripDecorations(String text) {
        String result = text;
        for (int i = 0; i < 6; i++) {
            Matcher matcher = DECORATION.matcher(result);
            if (!matcher.find() || matcher.end() == 0) {
                break;
            }
            String next = result.substring(matcher.end());
            if (next.isBlank()) {
                break;
            }
            result = next;
        }
        return result.strip();
    }

    /**
     * 判断这句话有没有在叫 {@code name}，返回该给模型的内容（去掉提及）。
     *
     * <p>两条通路：{@code @名字} 可以出现在任意位置；不带 @ 的名字必须出现在句首。
     * 两种都要做边界检查，避免 "MinecoinftFan" 这种误触发。</p>
     */
    static String stripTrigger(String raw, String name) {
        if (raw == null || name == null || name.isEmpty()) {
            return null;
        }
        String text = raw.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        String target = name.toLowerCase(Locale.ROOT);

        int at = lower.indexOf("@" + target);
        if (at >= 0) {
            int nameEnd = at + 1 + target.length();
            boolean leftOk = at == 0 || isBoundary(text.charAt(at - 1));
            boolean rightOk = nameEnd >= text.length() || isBoundary(text.charAt(nameEnd));
            if (leftOk && rightOk) {
                String before = text.substring(0, at).strip();
                String after = text.substring(nameEnd).strip();
                String payload = stripLeadingSeparators(after.isEmpty() ? before : after);
                return payload.isEmpty() ? null : payload;
            }
        }
        if (lower.startsWith(target)) {
            String rest = text.substring(name.length());
            if (!rest.isEmpty() && !isBoundary(rest.charAt(0))) {
                return null;
            }
            rest = stripLeadingSeparators(rest);
            return rest.isEmpty() ? null : rest;
        }
        return null;
    }

    private static String stripLeadingSeparators(String text) {        String rest = text.strip();
        while (!rest.isEmpty() && SEPARATORS.indexOf(rest.charAt(0)) >= 0) {
            rest = rest.substring(1).strip();
        }
        return rest;
    }

    /** 宽松模式：句子里任何位置的独立名字都算，返回去掉名字后的内容。 */
    private static String stripLoose(String raw, String name) {
        if (raw == null || raw.isBlank() || name == null || name.isBlank()) {
            return null;
        }
        String text = raw.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        String target = name.toLowerCase(Locale.ROOT);
        int index = lower.indexOf(target);
        while (index >= 0) {
            int end = index + target.length();
            boolean leftOk = index == 0 || isBoundary(text.charAt(index - 1));
            boolean rightOk = end >= text.length() || isBoundary(text.charAt(end));
            if (leftOk && rightOk) {
                String before = text.substring(0, index).strip();
                String after = text.substring(end).strip();
                String payload = after.isEmpty() ? before : after;
                return payload.isEmpty() ? null : payload;
            }
            index = lower.indexOf(target, index + 1);
        }
        return null;
    }

    private static boolean isBoundary(char c) {
        return Character.isWhitespace(c) || SEPARATORS.indexOf(c) >= 0
                || c == '。' || c == '，' || c == '！' || c == '？'
                || c == '!' || c == '?';
    }

    private static boolean containsMention(String text, String name) {
        String lower = text.toLowerCase(Locale.ROOT);
        String target = name.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("@" + target);
        while (index >= 0) {
            int end = index + 1 + target.length();
            boolean leftOk = index == 0 || isBoundary(text.charAt(index - 1));
            boolean rightOk = end >= text.length() || isBoundary(text.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            index = lower.indexOf("@" + target, index + 1);
        }
        return false;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return indexOfIgnoreCase(text, needle, 0);
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        if (needle.isEmpty() || from >= text.length()) {
            return -1;
        }
        return text.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT), from);
    }
}
