package io.github.xiaod.deepseekcompanion.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方块掉落表：直接读原版 jar 里的 {@code data/minecraft/loot_table/blocks/*.json}。
 *
 * <p>不写死"铁矿石→粗铁"这种映射：原版文件里怎么写就怎么算，
 * 所以深板岩铁矿、模组矿石都自动包含（它们也都掉粗铁）。多人游戏里读不到也不影响，
 * 还有一份很小的名字兜底。</p>
 */
public final class BlockDrops {
    private static final String LOOT_ROOT = "/data/minecraft/loot_table/blocks/";
    /** 物品 id -> 掉它的方块 id。 */
    private static final Map<String, List<String>> BY_ITEM = new LinkedHashMap<>();
    private static boolean loaded;

    private BlockDrops() {
    }

    public static List<String> blocksFor(String itemId) {
        ensureLoaded();
        List<String> blocks = BY_ITEM.get(itemId);
        if (blocks == null || blocks.isEmpty()) {
            return fallbackBlocks(itemId);
        }
        return blocks;
    }

    /** 能挖到这种物品、且离得最近的方块；没有掉落表时用手写兜底。 */
    public static String bestBlockFor(String itemId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String best = null;
        double bestScore = Double.MAX_VALUE;
        for (String block : blocksFor(itemId)) {
            double dist = MiningController.nearestDistance(mc, block, 24);
            double score = dist < 0 ? 1000.0 : dist;
            if (score < bestScore) {
                bestScore = score;
                best = block;
            }
        }
        return best;
    }

    public static boolean fromMining(String itemId) {
        return !blocksFor(itemId).isEmpty();
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        int blocks = 0;
        int links = 0;
        for (Identifier id : Registries.BLOCK.getIds()) {
            String name = id.getPath();
            JsonObject json = read(name);
            if (json == null) {
                continue;
            }
            blocks++;
            for (String item : itemsIn(json)) {
                List<String> owners = BY_ITEM.computeIfAbsent(item, k -> new ArrayList<>());
                if (!owners.contains(name)) {
                    owners.add(name);
                    links++;
                }
            }
        }
        DeepseekCompanionClient.LOGGER.info("[XiaoD] 掉落表：读了 {} 个方块的 json，{} 种物品、{} 条掉落",
                blocks, BY_ITEM.size(), links);
    }

    private static JsonObject read(String blockName) {
        try (var stream = BlockDrops.class.getResourceAsStream(LOOT_ROOT + blockName + ".json")) {
            if (stream == null) {
                return null;
            }
            return JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 掉落表里出现的所有物品（silk touch 的、普通的都算，反正都是这块掉出来的）。 */
    private static List<String> itemsIn(JsonObject table) {
        List<String> out = new ArrayList<>();
        if (!table.has("pools")) {
            return out;
        }
        JsonArray pools = table.getAsJsonArray("pools");
        for (JsonElement pool : pools) {
            JsonObject poolObject = pool.getAsJsonObject();
            if (!poolObject.has("entries")) {
                continue;
            }
            for (JsonElement entry : poolObject.getAsJsonArray("entries")) {
                collect(entry.getAsJsonObject(), out);
            }
        }
        return out;
    }

    private static void collect(JsonObject entry, List<String> out) {
        String type = entry.has("type") ? entry.get("type").getAsString() : "";
        if (type.endsWith(":item") && entry.has("name")) {
            addItem(entry.get("name").getAsString(), out);
        }
        if (entry.has("children")) {
            for (JsonElement child : entry.getAsJsonArray("children")) {
                if (child.isJsonObject()) {
                    collect(child.getAsJsonObject(), out);
                }
            }
        }
    }

    private static void addItem(String raw, List<String> out) {
        String name = raw.startsWith("minecraft:") ? raw.substring("minecraft:".length()) : raw;
        if (!name.isEmpty() && !out.contains(name)) {
            out.add(name);
        }
    }

    /** 掉落表读不到时的极小兜底：只覆盖最要紧的几个矿。 */
    private static List<String> fallbackBlocks(String itemId) {
        List<String> out = new ArrayList<>();
        if (itemId == null) {
            return out;
        }
        switch (itemId) {
            case "raw_iron" -> out.addAll(List.of("iron_ore", "deepslate_iron_ore"));
            case "raw_gold" -> out.addAll(List.of("gold_ore", "deepslate_gold_ore"));
            case "raw_copper" -> out.addAll(List.of("copper_ore", "deepslate_copper_ore"));
            case "coal" -> out.addAll(List.of("coal_ore", "deepslate_coal_ore"));
            case "diamond" -> out.addAll(List.of("diamond_ore", "deepslate_diamond_ore"));
            case "emerald" -> out.addAll(List.of("emerald_ore", "deepslate_emerald_ore"));
            case "redstone" -> out.addAll(List.of("redstone_ore", "deepslate_redstone_ore"));
            case "lapis_lazuli" -> out.addAll(List.of("lapis_ore", "deepslate_lapis_ore"));
            case "quartz" -> out.add("nether_quartz_ore");
            default -> {
                if (Registries.BLOCK.containsId(Identifier.of("minecraft", itemId))) {
                    out.add(itemId);
                }
            }
        }
        return out;
    }
}
