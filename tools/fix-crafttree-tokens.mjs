// Recipe cells use the token format `item:<ns>:<path>` / `tag:<ns>:<path>` (see
// CraftRecipes.matches). CraftTree was treating them as bare item ids, so every plan
// came back empty. Fix the resolution.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/integrations/CraftTree.java';
let s = readFileSync(p, 'utf8');
const before = s;

const from = `    /** {@code #tag} 在背包里找一件现成的代表；普通物品直接返回 id。 */
    private static String resolveToken(MinecraftClient mc, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (!token.startsWith("#")) {
            return token;
        }
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && CraftRecipes.matches(token, stack)) {
                return Registries.ITEM.getId(stack.getItem()).getPath();
            }
        }
        return null;
    }`;

const to = `    /**
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
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = mc.player.getInventory().getStack(slot);
                if (!stack.isEmpty() && CraftRecipes.matches("tag:" + (kind.equals("tag") ? rest : token.substring(1)), stack)) {
                    return Registries.ITEM.getId(stack.getItem()).getPath();
                }
            }
            return null;
        }
        // 裸物品 id：原样当物品名
        int colon = token.indexOf(':');
        return colon < 0 ? token : token.substring(colon + 1);
    }`;

if (s.includes(from)) {
  s = s.replace(from, to);
  writeFileSync(p, s, 'utf8');
  console.log('resolveToken fixed');
} else {
  console.log('MISS resolveToken anchor');
}
console.log('changed:', before !== s);
