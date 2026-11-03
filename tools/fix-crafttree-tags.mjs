// Tag ingredients (tag:minecraft:planks) were only resolved against the current inventory,
// so "有原木没木板" failed. Resolve tags through the real item registry: pick a member we
// already hold, otherwise a member that is itself craftable (e.g. oak_planks from oak_log).
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/integrations/CraftTree.java';
let s = readFileSync(p, 'utf8');

const from = `        if (kind.equals("tag") || token.startsWith("#")) {
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = mc.player.getInventory().getStack(slot);
                if (!stack.isEmpty() && CraftRecipes.matches("tag:" + (kind.equals("tag") ? rest : token.substring(1)), stack)) {
                    return Registries.ITEM.getId(stack.getItem()).getPath();
                }
            }
            return null;
        }`;

const to = `        if (kind.equals("tag") || token.startsWith("#")) {
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
        }`;

if (s.includes(from)) {
  writeFileSync(p, s.replace(from, to), 'utf8');
  console.log('tag resolution upgraded');
} else {
  console.log('MISS tag anchor');
}
