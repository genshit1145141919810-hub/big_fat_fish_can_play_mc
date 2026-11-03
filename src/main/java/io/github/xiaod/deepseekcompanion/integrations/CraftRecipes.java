package io.github.xiaod.deepseekcompanion.integrations;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Crafting shapes copied out of the vanilla 1.21.11 data pack (the client no
 * longer receives the recipe table over the network), so any vanilla crafting
 * recipe can be laid out and clicked without asking a server.
 *
 * <p>Tokens: {@code item:namespace:path} or {@code tag:namespace:path}; tags are
 * checked against the client's synced item tags.</p>
 */
public final class CraftRecipes {
    /** One grid shape; row-major, null cells are empty. */
    public record Recipe(int width, int height, String[] cells, String result, int count) {
    }

    private static final Map<String, List<Recipe>> BY_RESULT = new HashMap<>();
    private static boolean loaded;

    private CraftRecipes() {
    }

    public static List<Recipe> find(String itemId) {
        ensureLoaded();
        return BY_RESULT.getOrDefault(normalizeResult(itemId), List.of());
    }

    public static boolean matches(String token, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Identifier id = Registries.ITEM.getId(stack.getItem());
        int split = token.indexOf(':');
        if (split < 0) {
            return false;
        }
        String kind = token.substring(0, split);
        String rest = token.substring(split + 1);
        int colon = rest.indexOf(':');
        if (colon < 0) {
            return false;
        }
        Identifier target = Identifier.of(rest.substring(0, colon), rest.substring(colon + 1));
        if (kind.equals("item")) {
            return id.equals(target);
        }
        if (kind.equals("tag")) {
            try {
                return stack.isIn(TagKey.of(RegistryKeys.ITEM, target));
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static String normalizeResult(String itemId) {
        String id = itemId == null ? "" : itemId.strip();
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        return id;
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try (var stream = CraftRecipes.class.getResourceAsStream("/assets/big_fat_fish_can_play_mc/crafting.json")) {
            if (stream == null) {
                DeepseekCompanionClient.LOGGER.warn("[XiaoD] crafting table resource missing");
                return;
            }
            JsonArray array = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement element : array) {
                try {
                    parse(element.getAsJsonObject());
                } catch (Throwable ignored) {
                    // skip malformed entries
                }
            }
            DeepseekCompanionClient.LOGGER.info("[XiaoD] loaded {} crafting results", BY_RESULT.size());
        } catch (Throwable t) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] cannot read crafting table", t);
        }
    }

    private static void parse(JsonObject entry) {
        String result = stripNamespace(entry.get("r").getAsString());
        int count = entry.has("c") ? entry.get("c").getAsInt() : 1;
        if (entry.has("p")) {
            JsonArray pattern = entry.getAsJsonArray("p");
            JsonObject key = entry.getAsJsonObject("k");
            int height = pattern.size();
            int width = 0;
            for (JsonElement row : pattern) {
                width = Math.max(width, row.getAsString().length());
            }
            String[] cells = new String[width * height];
            for (int row = 0; row < height; row++) {
                String line = pattern.get(row).getAsString();
                for (int col = 0; col < width && col < line.length(); col++) {
                    char symbol = line.charAt(col);
                    if (symbol == ' ') {
                        continue;
                    }
                    JsonElement value = key.get(String.valueOf(symbol));
                    if (value != null) {
                        cells[row * width + col] = normalizeToken(value.getAsString());
                    }
                }
            }
            add(result, new Recipe(width, height, cells, result, count));
        } else if (entry.has("s")) {
            JsonArray ingredients = entry.getAsJsonArray("s");
            int n = ingredients.size();
            int width = Math.min(3, Math.max(1, n));
            int height = (n + width - 1) / width;
            String[] cells = new String[width * height];
            for (int i = 0; i < n; i++) {
                cells[i] = normalizeToken(ingredients.get(i).getAsString());
            }
            add(result, new Recipe(width, height, cells, result, count));
        }
    }

    private static void add(String result, Recipe recipe) {
        BY_RESULT.computeIfAbsent(result, k -> new ArrayList<>()).add(recipe);
    }

    private static String normalizeToken(String raw) {
        String token = raw.strip();
        if (token.startsWith("#")) {
            return "tag:" + stripHash(token.substring(1));
        }
        return "item:" + stripHash(token);
    }

    private static String stripHash(String value) {
        String id = value.strip();
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static String stripNamespace(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
