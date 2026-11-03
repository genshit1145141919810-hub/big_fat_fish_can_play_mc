package io.github.xiaod.deepseekcompanion.integrations;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.item.ItemStack;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * All four cooking recipe tables, copied from the vanilla data pack.
 *
 * <p>Priority when several stations can cook the same item (fastest first):
 * blast furnace (ores) &gt; smoker (food) &gt; furnace &gt; campfire. The campfire
 * is fuel-free but slow, and it has no GUI: you place with a right-click and
 * collect by breaking it.</p>
 */
public final class SmeltRecipes {
    public enum Station {
        BLAST, SMOKER, FURNACE, CAMPFIRE
    }

    public record Entry(String inputToken, String output, int count, Station station) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean loaded;

    private SmeltRecipes() {
    }

    public static List<Entry> byOutput(String itemId) {
        ensureLoaded();
        String target = normalize(itemId);
        List<Entry> hits = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (normalize(entry.output()).equals(target)) {
                hits.add(entry);
            }
        }
        return hits;
    }

    /** Every station that can cook this stack, best station first. */
    public static List<Entry> byInput(ItemStack stack) {
        ensureLoaded();
        List<Entry> hits = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (CraftRecipes.matches(entry.inputToken(), stack) && !hits.stream()
                    .anyMatch(h -> h.station() == entry.station())) {
                hits.add(entry);
            }
        }
        hits.sort((a, b) -> Integer.compare(priority(a.station()), priority(b.station())));
        return hits;
    }

    public static Entry byInputName(String itemId) {
        ensureLoaded();
        String token = "item:" + normalize(itemId);
        for (Entry entry : ENTRIES) {
            if (entry.inputToken().equals(token)) {
                return entry;
            }
        }
        return null;
    }

    public static int priority(Station station) {
        return switch (station) {
            case BLAST -> 0;
            case SMOKER -> 1;
            case FURNACE -> 2;
            case CAMPFIRE -> 3;
        };
    }

    public static String stationName(Station station) {
        return switch (station) {
            case BLAST -> "高炉";
            case SMOKER -> "烟熏炉";
            case FURNACE -> "熔炉";
            case CAMPFIRE -> "营火";
        };
    }

    public static String display(String token) {
        if (token == null) {
            return "?";
        }
        if (token.startsWith("tag:")) {
            String id = token.substring(4);
            return id.endsWith("logs_that_burn") || id.endsWith("logs") ? "任意原木" : "#" + id;
        }
        String id = token.startsWith("item:") ? token.substring(5) : token;
        var item = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(id));
        return item.getName().getString();
    }

    private static String normalize(String itemId) {
        String id = itemId == null ? "" : itemId.strip();
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        load("smelting.json", Station.FURNACE);
        load("blasting.json", Station.BLAST);
        load("smoking.json", Station.SMOKER);
        load("campfire_cooking.json", Station.CAMPFIRE);
        DeepseekCompanionClient.LOGGER.info("[XiaoD] loaded {} cooking recipes", ENTRIES.size());
    }

    private static void load(String file, Station station) {
        try (var stream = SmeltRecipes.class.getResourceAsStream("/assets/big_fat_fish_can_play_mc/" + file)) {
            if (stream == null) {
                DeepseekCompanionClient.LOGGER.warn("[XiaoD] cooking table missing: {}", file);
                return;
            }
            JsonArray array = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement element : array) {
                try {
                    JsonObject entry = element.getAsJsonObject();
                    String raw = entry.get("i").getAsString();
                    String token = raw.startsWith("#")
                            ? "tag:" + withoutHash(raw.substring(1))
                            : "item:" + withoutHash(raw);
                    ENTRIES.add(new Entry(token, entry.get("o").getAsString(),
                            entry.has("c") ? entry.get("c").getAsInt() : 1, station));
                } catch (Throwable ignored) {
                    // skip malformed entries
                }
            }
        } catch (Throwable t) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] cannot read {}", file, t);
        }
    }

    private static String withoutHash(String value) {
        String id = value.strip();
        return id.contains(":") ? id : "minecraft:" + id;
    }
}
