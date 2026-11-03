package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 变种表：原版里"可以互相顶替"的东西归成一族。
 *
 * <p>橡木、云杉、白桦都是木头；铁矿石和深板岩铁矿石都掉粗铁；石头和深板岩都能做石镐。
 * 这些关系来自原版 item tag，所以模组加了新木头也会自动进族。</p>
 */
public final class ItemFamilies {
    /** 这些 tag 里的东西是可以互换的（只挑"材料类"的，不管颜色/装饰那种强绑定的）。 */
    private static final List<String> INTERESTING = List.of(
            "logs", "planks", "wooden_tool_materials", "stone_tool_materials", "stone_crafting_materials",
            "iron_ores", "gold_ores", "copper_ores", "coal_ores", "diamond_ores", "emerald_ores",
            "redstone_ores", "lapis_ores", "coals", "wools", "sand", "smelts_to_glass");

    private static final Map<String, List<String>> BY_ITEM = new LinkedHashMap<>();
    private static boolean loaded;

    private ItemFamilies() {
    }

    /** 和它互换的其它物品（包含它自己）。 */
    public static List<String> variants(String itemId) {
        ensureLoaded();
        List<String> hit = BY_ITEM.get(itemId);
        if (hit == null) {
            return List.of(itemId);
        }
        return hit;
    }

    /** 它们算不算同一种材料。 */
    public static boolean sameFamily(String a, String b) {
        return variants(a).contains(b);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        int groups = 0;
        try {
            for (var named : Registries.ITEM.streamTags().toList()) {
                TagKey<Item> tag = named.getTag();
                String path = tag.id().getPath();
                if (!interesting(path)) {
                    continue;
                }
                List<String> members = new ArrayList<>();
                for (RegistryEntry<Item> entry : named) {
                    String id = Registries.ITEM.getId(entry.value()).getPath();
                    if (!id.equals("air") && !members.contains(id)) {
                        members.add(id);
                    }
                }
                if (members.size() < 2) {
                    continue;
                }
                groups++;
                for (String member : members) {
                    Set<String> merged = new LinkedHashSet<>(BY_ITEM.getOrDefault(member, List.of(member)));
                    merged.addAll(members);
                    BY_ITEM.put(member, new ArrayList<>(merged));
                }
            }
        } catch (Throwable t) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] 变种表读不出来", t);
        }
        DeepseekCompanionClient.LOGGER.info("[XiaoD] 变种表：{} 组，覆盖 {} 种物品", groups, BY_ITEM.size());
    }

    private static boolean interesting(String path) {
        for (String key : INTERESTING) {
            if (path.equals(key) || path.endsWith("_" + key) || path.startsWith(key + "_")) {
                return true;
            }
        }
        return false;
    }
}
