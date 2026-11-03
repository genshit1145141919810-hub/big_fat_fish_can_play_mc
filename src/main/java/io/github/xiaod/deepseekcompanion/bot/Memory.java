package io.github.xiaod.deepseekcompanion.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨会话记忆：家在哪、哪个箱子存过东西、上次为什么失败。
 *
 * <p>不存"对话"，只存**下次还会用到的事实**。JSON 落在 {@code config/bigfatfish-memory.json}，
 * 人类可读、可手改、损坏就重建，永远不会让游戏启动失败。</p>
 */
public final class Memory {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_FAILURES = 20;
    private static final int MAX_STASHES = 32;

    /** 落盘结构。字段保持简单，方便人手改。 */
    public static final class Data {
        public String homeDimension = "";
        public int homeX;
        public int homeY;
        public int homeZ;
        public boolean homeSet;
        public String lastOwner = "";
        public List<Stash> stashes = new ArrayList<>();
        public List<Failure> failures = new ArrayList<>();
    }

    public static final class Stash {
        public String dimension = "";
        public int x;
        public int y;
        public int z;
        public String hint = "";
        public long updatedAt;
    }

    public static final class Failure {
        public String goal = "";
        public String reason = "";
        public long at;
    }

    private static Data data;
    private static boolean dirty;

    private Memory() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("bigfatfish-memory.json");
    }

    public static synchronized Data get() {
        if (data == null) {
            load();
        }
        return data;
    }

    private static synchronized void load() {
        Path path = file();
        Data loaded = null;
        try {
            if (Files.isRegularFile(path)) {
                loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
            }
        } catch (Exception error) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] memory file unreadable, starting fresh", error);
        }
        if (loaded == null) {
            loaded = new Data();
        }
        if (loaded.stashes == null) {
            loaded.stashes = new ArrayList<>();
        }
        if (loaded.failures == null) {
            loaded.failures = new ArrayList<>();
        }
        data = loaded;
    }

    public static synchronized void save() {
        if (data == null) {
            return;
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(data), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception error) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] cannot write memory file", error);
        }
    }

    public static synchronized void flushIfDirty() {
        if (dirty) {
            save();
        }
    }

    // ---------------------------------------------------------------- home

    public static synchronized void setHome(BlockPos pos, String dimension) {
        Data d = get();
        d.homeSet = true;
        d.homeDimension = dimension == null ? "" : dimension;
        d.homeX = pos.getX();
        d.homeY = pos.getY();
        d.homeZ = pos.getZ();
        dirty = true;
        save();
    }

    public static synchronized void clearHome() {
        Data d = get();
        d.homeSet = false;
        d.homeDimension = "";
        dirty = true;
        save();
    }

    // -------------------------------------------------------------- stashes

    /** 记录一个存过东西的箱子位置（同坐标会覆盖）。 */
    public static synchronized void noteStash(BlockPos pos, String dimension, String hint) {
        Data d = get();
        for (Stash stash : d.stashes) {
            if (stash.x == pos.getX() && stash.y == pos.getY() && stash.z == pos.getZ()
                    && stash.dimension.equals(dimension)) {
                stash.hint = hint == null ? "" : hint;
                stash.updatedAt = System.currentTimeMillis();
                dirty = true;
                save();
                return;
            }
        }
        Stash stash = new Stash();
        stash.dimension = dimension == null ? "" : dimension;
        stash.x = pos.getX();
        stash.y = pos.getY();
        stash.z = pos.getZ();
        stash.hint = hint == null ? "" : hint;
        stash.updatedAt = System.currentTimeMillis();
        d.stashes.add(stash);
        while (d.stashes.size() > MAX_STASHES) {
            d.stashes.remove(0);
        }
        dirty = true;
        save();
    }

    /** 记忆里有没有哪个箱子提过这种物品（给"去箱子拿"提供依据）。 */
    public static synchronized boolean hasStashWith(String itemId) {
        if (itemId == null) {
            return false;
        }
        for (Stash stash : get().stashes) {
            if (stash.hint != null && stash.hint.toLowerCase(java.util.Locale.ROOT).contains(itemId)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized List<Stash> stashes() {
        return new ArrayList<>(get().stashes);
    }

    // ------------------------------------------------------------- failures

    public static synchronized void noteFailure(String goal, String reason) {
        Data d = get();
        Failure failure = new Failure();
        failure.goal = goal == null ? "" : goal;
        failure.reason = reason == null ? "" : reason;
        failure.at = System.currentTimeMillis();
        d.failures.add(failure);
        while (d.failures.size() > MAX_FAILURES) {
            d.failures.remove(0);
        }
        dirty = true;
        save();
    }

    public static synchronized List<Failure> failures() {
        return new ArrayList<>(get().failures);
    }

    public static synchronized void setOwner(String name) {
        Data d = get();
        if (name != null && !name.isBlank() && !name.equals(d.lastOwner)) {
            d.lastOwner = name;
            dirty = true;
        }
    }

    /** 给模型看的一行摘要（很短，容易进提示词）。 */
    public static synchronized String summary() {
        Data d = get();
        StringBuilder sb = new StringBuilder();
        if (d.homeSet) {
            sb.append("家 ").append(d.homeX).append(' ').append(d.homeY).append(' ').append(d.homeZ);
        } else {
            sb.append("家未设");
        }
        sb.append(" | 已知箱子 ").append(d.stashes.size()).append(" 个");
        if (!d.failures.isEmpty()) {
            Failure last = d.failures.get(d.failures.size() - 1);
            sb.append(" | 上次失败：").append(last.goal).append("（").append(last.reason).append("）");
        }
        return sb.toString();
    }
}
