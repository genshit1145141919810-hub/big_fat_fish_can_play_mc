package io.github.xiaod.deepseekcompanion.integrations;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 合成树：把"要一把木镐"展开成"先合木板 → 再合木棍 → 最后合镐"的步骤序列。
 *
 * <p>之前只会看一眼配方、材料不够就报错，所以"背包里有木头但没木板"这种常见情况
 * 直接失败。现在递归解析依赖：缺什么就先合什么，叶子步骤排在最前面。</p>
 *
 * <p>只看原版配方表（{@link CraftRecipes}），标签材料（{@code #planks}）会在背包里
 * 找一件现有的当代表；找不到就换下一个候选配方，都不行才失败。</p>
 */
public final class CraftTree {
    private static final int MAX_DEPTH = 5;
    private static final int MAX_STEPS = 12;

    public record Step(String item, int crafts) {
        @Override
        public String toString() {
            return item + "x" + crafts;
        }
    }

    private CraftTree() {
    }

    /** 返回按顺序执行的合成步骤；无法完成时返回空列表。 */
    public static List<Step> plan(MinecraftClient mc, String target) {
        List<Step> steps = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        if (!ensure(mc, target, 1, steps, visiting, 0)) {
            return List.of();
        }
        if (steps.size() > MAX_STEPS) {
            return List.of();
        }
        return steps;
    }

    private static boolean ensure(MinecraftClient mc, String item, int need,
                                  List<Step> steps, Set<String> visiting, int depth) {
        if (depth > MAX_DEPTH || steps.size() > MAX_STEPS) {
            return false;
        }
        if (have(mc, item) >= need) {
            return true;
        }
        if (!visiting.add(item)) {
            return false; // 循环配方，放弃
        }
        try {
            for (CraftRecipes.Recipe recipe : CraftRecipes.find(item)) {
                List<Step> probe = new ArrayList<>();
                if (!coverIngredients(mc, recipe, need, probe, new LinkedHashSet<>(visiting), depth + 1)) {
                    continue;
                }
                int crafts = Math.max(1, (need - have(mc, item) + recipe.count() - 1) / recipe.count());
                steps.addAll(probe);
                steps.add(new Step(item, crafts));
                return true;
            }
            return false;
        } finally {
            visiting.remove(item);
        }
    }

    /** 检查并预先排布某个配方的所有原料（先合中间产物）。 */
    private static boolean coverIngredients(MinecraftClient mc, CraftRecipes.Recipe recipe, int need,
                                            List<Step> steps, Set<String> visiting, int depth) {
        int crafts = Math.max(1, (need - have(mc, recipe.result()) + recipe.count() - 1) / recipe.count());
        Map<String, Integer> tokens = new LinkedHashMap<>();
        for (String cell : recipe.cells()) {
            if (cell == null) {
                continue;
            }
            tokens.merge(cell, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : tokens.entrySet()) {
            int amount = entry.getValue() * crafts;
            String resolved = resolveToken(mc, entry.getKey());
            if (resolved == null) {
                return false;
            }
            if (have(mc, resolved) >= amount) {
                continue;
            }
            if (!ensure(mc, resolved, amount, steps, visiting, depth)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把配方格子里的 token 解析成一个具体物品 id。
     * token 形如 {@code item:minecraft:oak_log} 或 {@code tag:minecraft:planks}；
     * 标签会在背包里找一件现成的代表（原版标签解析需要运行时注册表）。
     */
    private static String resolveToken(MinecraftClient mc, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String kind = token;
        String rest = "";
        int split = token.indexOf(':');
        if (split > 0) {
            kind = token.substring(0, split);
            rest = token.substring(split + 1);
        }
        if (kind.equals("item")) {
            int colon = rest.indexOf(':');
            return colon < 0 ? rest : rest.substring(colon + 1);
        }
        if (kind.equals("tag") || token.startsWith("#")) {
            String tagText = kind.equals("tag") ? rest : token.substring(1);
            // 1) 背包里已经有这个标签的成员，直接用
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = mc.player.getInventory().getStack(slot);
                if (!stack.isEmpty() && CraftRecipes.matches("tag:" + tagText, stack)) {
                    return Registries.ITEM.getId(stack.getItem()).getPath();
                }
            }
            // 2) 没有就从注册表里挑一个"本身能合出来"的成员（比如木板）
            int colon = tagText.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : tagText.substring(0, colon);
            String path = colon < 0 ? tagText : tagText.substring(colon + 1);
            net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> key =
                    net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.ITEM,
                            net.minecraft.util.Identifier.of(namespace, path));
            String fallback = null;
            for (var item : Registries.ITEM) {
                if (item == net.minecraft.item.Items.AIR) {
                    continue;
                }
                try {
                    if (!new ItemStack(item).isIn(key)) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    continue;
                }
                String id = Registries.ITEM.getId(item).getPath();
                if (fallback == null) {
                    fallback = id;
                }
                if (!CraftRecipes.find(id).isEmpty()) {
                    return id; // 能自己合出来的优先
                }
            }
            return fallback;
        }
        // 裸物品 id：原样当物品名
        int colon = token.indexOf(':');
        return colon < 0 ? token : token.substring(colon + 1);
    }

    private static int have(MinecraftClient mc, String itemId) {
        if (mc.player == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (Registries.ITEM.getId(stack.getItem()).getPath().equals(itemId.toLowerCase(Locale.ROOT))) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
