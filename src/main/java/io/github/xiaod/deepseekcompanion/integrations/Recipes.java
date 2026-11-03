package io.github.xiaod.deepseekcompanion.integrations;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Recipe hints for 1.21.11.
 *
 * <p>The client no longer receives the full crafting recipe table, so crafting
 * answers come from {@link CraftRecipes} (bundled vanilla data) and smelting
 * answers from {@link SmeltRecipes}.</p>
 */
public final class Recipes {
    private Recipes() {
    }

    public static String crafting(MinecraftClient mc, String itemId) {
        String id = strip(itemId);
        var into = SmeltRecipes.byOutput(id);
        if (!into.isEmpty()) {
            String input = SmeltRecipes.display(into.get(0).inputToken());
            return "熔炼：" + input + " → " + itemName(id) + "（" + stations(into) + "）";
        }
        var out = SmeltRecipes.byInputName(id);
        if (out != null) {
            return "熔炼：" + itemName(id) + " → " + itemName(strip(out.output())) + "（" + SmeltRecipes.stationName(out.station()) + "）";
        }
        if (!CraftRecipes.find(id).isEmpty()) {
            return "这个要用工作台合成（材料我没法从聊天里列全，常见工具/方块我可以直接做）";
        }
        return "查不到 " + itemName(id) + " 的配方";
    }

    private static String stations(java.util.List<SmeltRecipes.Entry> entries) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (var entry : entries) {
            names.add(SmeltRecipes.stationName(entry.station()));
        }
        return String.join("/", names);
    }

    public static String uses(MinecraftClient mc, String itemId) {
        String id = strip(itemId);
        var into = SmeltRecipes.byOutput(id);
        if (!into.isEmpty()) {
            return itemName(id) + " 可以用 " + SmeltRecipes.display(into.get(0).inputToken()) + " 熔炼出来";
        }
        var out = SmeltRecipes.byInputName(id);
        if (out != null) {
            return itemName(id) + " 可以烧成 " + itemName(strip(out.output()));
        }
        return "1.21.11 客户端不再同步完整配方表，用途查询暂时不可用";
    }

    private static String strip(String id) {
        String value = id == null ? "" : id.strip();
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    private static String itemName(String id) {
        Item item = Registries.ITEM.get(Identifier.of("minecraft", id));
        return item.getName().getString();
    }
}
