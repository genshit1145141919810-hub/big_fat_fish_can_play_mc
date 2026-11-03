package io.github.xiaod.deepseekcompanion.bot;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 事件日志：记录"刚刚发生了什么"，按相对时间给模型看。
 *
 * <p>和 {@link Perception} 的区别：Perception 是**当前状态**（我看到什么、附近有什么），
 * EventLog 是**变化**（我受伤了、我死了、我挖到东西、任务失败了）。线性循环要重规划，
 * 最需要的就是"变化"——否则模型看到的是一个没有前因的当前帧。</p>
 *
 * <p>只保留最近 {@link #KEEP} 条、去重同类、按秒数折算成"12 秒前"这种短文本，
 * 避免把提示词撑大。</p>
 */
public final class EventLog {
    private static final int KEEP = 40;
    private static final int PROMPT_TAIL = 5;
    private static final long DEDUPE_MS = 3000L;

    public record Event(long at, String kind, String text) {
    }

    private static final Deque<Event> EVENTS = new ArrayDeque<>();
    private static String lastKind = "";
    private static String lastText = "";
    private static long lastAt;

    /** 上次血量，用来检测"受伤/死亡"。 */
    private static float lastHealth = -1.0f;

    private EventLog() {
    }

    public static synchronized void note(String kind, String text) {
        if (kind == null || text == null || text.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (kind.equals(lastKind) && text.equals(lastText) && now - lastAt < DEDUPE_MS) {
            return;
        }
        lastKind = kind;
        lastText = text;
        lastAt = now;
        EVENTS.addLast(new Event(now, kind, text.strip()));
        while (EVENTS.size() > KEEP) {
            EVENTS.removeFirst();
        }
    }

    /** 给模型的一行摘要（最近 3~5 条，带相对时间）。 */
    public static synchronized String summary() {
        if (EVENTS.isEmpty()) {
            return "";
        }
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        var it = EVENTS.descendingIterator();
        while (it.hasNext() && shown < PROMPT_TAIL) {
            Event event = it.next();
            if (shown++ > 0) {
                sb.append("；");
            }
            sb.append(ago(now, event.at())).append(' ').append(event.text());
        }
        return sb.toString();
    }

    /** 给查询动作/命令用的详细列表。 */
    public static synchronized String recent(int limit) {
        if (EVENTS.isEmpty()) {
            return "还没有记录到什么事件";
        }
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("最近发生：" );
        int shown = 0;
        var it = EVENTS.descendingIterator();
        while (it.hasNext() && shown < Math.max(1, limit)) {
            Event event = it.next();
            sb.append("\n- ").append(ago(now, event.at())).append(" [").append(event.kind()).append("] ")
                    .append(event.text());
            shown++;
        }
        return sb.toString();
    }

    /** 每 tick 调用：检测血量下降（受伤/死亡）。 */
    public static synchronized void watchHealth(net.minecraft.client.MinecraftClient mc) {
        var player = mc.player;
        if (player == null) {
            lastHealth = -1.0f;
            return;
        }
        float health = player.getHealth();
        if (lastHealth >= 0.0f && health < lastHealth - 0.05f) {
            int delta = Math.round(lastHealth - health);
            if (health <= 0.0f) {
                note("死亡", "被击倒，位置 " + player.getBlockPos().getX() + " "
                        + player.getBlockPos().getY() + " " + player.getBlockPos().getZ());
            } else {
                note("受伤", "血量 -" + delta + "（现在 " + Math.round(health) + "/20）");
            }
        } else if (lastHealth >= 0.0f && health > lastHealth + 1.0f) {
            note("回血", "血量 +" + Math.round(health - lastHealth));
        }
        lastHealth = health;
    }

    /** 清空（换世界时用）。 */
    public static synchronized void reset() {
        EVENTS.clear();
        lastHealth = -1.0f;
        lastKind = "";
        lastText = "";
    }

    private static String ago(long now, long at) {
        long seconds = Math.max(0L, (now - at) / 1000L);
        if (seconds < 60) {
            return seconds + "秒前";
        }
        long minutes = seconds / 60;
        return minutes < 60 ? minutes + "分钟前" : (minutes / 60) + "小时前";
    }
}
