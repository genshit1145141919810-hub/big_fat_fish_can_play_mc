package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import io.github.xiaod.deepseekcompanion.integrations.CraftRecipes;
import io.github.xiaod.deepseekcompanion.integrations.SmeltRecipes;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 获取任务：把"我要 N 个 X"当成一个可失败、可降级的确定性目标，而不是让模型拆步骤。
 *
 * <p>来源按成本和可靠性排序，逐个尝试，每一步都有独立超时：</p>
 * <pre>
 *   背包已有 → 合成（含递归原料）→ 熔炼 → 挖取 → 从箱子取 → 打猎掉落 → 明确报缺什么
 * </pre>
 *
 * <p>这是 Altoclef 的 {@code TaskCatalogue} 思路的最小可用版：机制归代码，意图归模型。
 * 模型只需要说"弄把铁镐"，剩下的路线由这里决定。</p>
 */
public final class AcquireTask {
    private static final long SOURCE_TIMEOUT_MS = 45_000L;
    private static final long TOTAL_TIMEOUT_MS = 180_000L;

    private enum Source {
        INVENTORY("背包已有"),
        CRAFT("合成"),
        SMELT("熔炼"),
        MINE("挖"),
        TAKE("从箱子取"),
        HUNT("打猎"),
        DONE("完成"),
        FAILED("放弃");

        final String label;

        Source(String label) {
            this.label = label;
        }
    }

    /** 物品 → 要挖的方块（缺省用同名方块）。 */
    private static final Map<String, String> ITEM_TO_BLOCK = new LinkedHashMap<>();
    /** 食物 → 掉它的生物。 */
    private static final Map<String, String> ITEM_TO_MOB = new LinkedHashMap<>();

    static {
        ITEM_TO_BLOCK.put("cobblestone", "stone");
        ITEM_TO_BLOCK.put("iron_ingot", "iron_ore");
        ITEM_TO_BLOCK.put("gold_ingot", "gold_ore");
        ITEM_TO_BLOCK.put("copper_ingot", "copper_ore");
        ITEM_TO_BLOCK.put("coal", "coal_ore");
        ITEM_TO_BLOCK.put("diamond", "diamond_ore");
        ITEM_TO_BLOCK.put("emerald", "emerald_ore");
        ITEM_TO_BLOCK.put("redstone", "redstone_ore");
        ITEM_TO_BLOCK.put("lapis_lazuli", "lapis_ore");
        ITEM_TO_BLOCK.put("charcoal", "oak_log");
        ITEM_TO_MOB.put("beef", "cow");
        ITEM_TO_MOB.put("leather", "cow");
        ITEM_TO_MOB.put("porkchop", "pig");
        ITEM_TO_MOB.put("chicken", "chicken");
        ITEM_TO_MOB.put("feather", "chicken");
        ITEM_TO_MOB.put("mutton", "sheep");
        ITEM_TO_MOB.put("wool", "sheep");
        ITEM_TO_MOB.put("rabbit", "rabbit");
        ITEM_TO_MOB.put("rotten_flesh", "zombie");
        ITEM_TO_MOB.put("bone", "skeleton");
        ITEM_TO_MOB.put("string", "spider");
        ITEM_TO_MOB.put("gunpowder", "creeper");
        ITEM_TO_MOB.put("slime_ball", "slime");
    }

    private static String itemId;
    private static int want;
    private static int startedCount;
    private static Source source = Source.INVENTORY;
    private static long sourceSince;
    private static long startedAt;
    private static boolean running;

    private AcquireTask() {
    }

    public static boolean isBusy() {
        return running;
    }

    public static String status() {
        if (!running) {
            return "";
        }
        return "获取 " + itemId + " x" + want + "（" + source.label + "）";
    }

    public static boolean start(MinecraftClient mc, String target, int count) {
        if (mc.player == null) {
            return false;
        }
        String id = normalize(target);
        if (id.isEmpty()) {
            BotBrain.tell("没说要弄什么");
            return false;
        }
        itemId = id;
        want = Math.max(1, count);
        startedCount = count(mc, itemId);
        source = Source.INVENTORY;
        sourceSince = System.currentTimeMillis();
        startedAt = sourceSince;
        running = true;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire {} x{} (have {})", itemId, want, startedCount);
        return true;
    }

    public static void cancel(MinecraftClient mc) {
        running = false;
        MiningController.cancel(mc);
        ContainerController.cancel(mc);
        TaskTracker.clear();
    }

    public static void tick(MinecraftClient mc) {
        if (!running) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            running = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - startedAt > TOTAL_TIMEOUT_MS) {
            fail(mc, "超过 3 分钟还没弄到");
            return;
        }
        int have = count(mc, itemId);
        if (have >= startedCount + want) {
            succeeded(mc, have);
            return;
        }
        // 上一个来源在忙就等它；不忙了再判定是否换来源
        if (PlanRunner.anyBusy() || MiningController.isBusy() || ContainerController.isBusy()
                || StorageController.isBusy() || CombatController.isHunting()) {
            return;
        }
        if (now - sourceSince > SOURCE_TIMEOUT_MS) {
            DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire: {} gave nothing in {}s, next source",
                    source.label, SOURCE_TIMEOUT_MS / 1000);
            advance(mc);
            return;
        }
        if (source == Source.INVENTORY) {
            advance(mc); // 背包没有，直接进入下一来源
            return;
        }
        if (source == Source.CRAFT) {
            if (!HasRecipe(itemId)) {
                advance(mc);
                return;
            }
            if (!tryCraft(mc)) {
                advance(mc);
            }
            return;
        }
        if (source == Source.SMELT) {
            if (!trySmelt(mc)) {
                advance(mc);
            }
            return;
        }
        if (source == Source.MINE) {
            if (!tryMine(mc)) {
                advance(mc);
            }
            return;
        }
        if (source == Source.TAKE) {
            if (!tryTake(mc)) {
                advance(mc);
            }
            return;
        }
        if (source == Source.HUNT) {
            if (!tryHunt(mc)) {
                advance(mc);
            }
            return;
        }
        running = false;
    }

    private static boolean HasRecipe(String id) {
        return !CraftRecipes.find(id).isEmpty();
    }

    private static boolean tryCraft(MinecraftClient mc) {
        // ContainerController 内部就是递归合成树（缺原料会先合）
        return ContainerController.craft(mc, itemId);
    }

    private static boolean trySmelt(MinecraftClient mc) {
        List<SmeltRecipes.Entry> entries = SmeltRecipes.byOutput(itemId);
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        for (SmeltRecipes.Entry entry : entries) {
            String input = entry.inputToken();
            if (input == null || input.isBlank()) {
                continue;
            }
            if (count(mc, input) <= 0) {
                continue;
            }
            if (ContainerController.cook(mc, input)) {
                DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire {}: smelting {}", itemId, input);
                return true;
            }
        }
        return false;
    }

    private static boolean tryMine(MinecraftClient mc) {
        String block = ITEM_TO_BLOCK.getOrDefault(itemId, itemId);
        if (Perception.scan(mc, block).startsWith(block + "：")) {
            return MiningController.start(mc, block, false, want);
        }
        // 树/木头：用 chop 模式更稳
        if (block.endsWith("_log") || block.endsWith("_stem")) {
            return MiningController.start(mc, null, true, want);
        }
        return false;
    }

    private static boolean tryTake(MinecraftClient mc) {
        if (Memory.hasStashWith(itemId)) {
            return StorageController.take(mc, itemId);
        }
        return StorageController.take(mc, itemId);
    }

    private static boolean tryHunt(MinecraftClient mc) {
        String mob = ITEM_TO_MOB.get(itemId);
        if (mob == null) {
            return false;
        }
        return CombatController.startHunt(mc, mob);
    }

    private static void advance(MinecraftClient mc) {
        Source next = switch (source) {
            case INVENTORY -> Source.CRAFT;
            case CRAFT -> Source.SMELT;
            case SMELT -> Source.MINE;
            case MINE -> Source.TAKE;
            case TAKE -> Source.HUNT;
            default -> Source.FAILED;
        };
        if (next == Source.FAILED) {
            fail(mc, "背包、合成、熔炼、挖矿、箱子、打猎都没有办法");
            return;
        }
        source = next;
        sourceSince = System.currentTimeMillis();
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire {}: trying source {}", itemId, source.label);
    }

    private static void succeeded(MinecraftClient mc, int have) {
        running = false;
        EventLog.note("获取成功", itemId + " 现在 " + have + " 个");
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire done: {} (now {})", itemId, have);
        BotBrain.tell("弄到了 " + itemId + "（现在 " + have + " 个）");
    }

    private static void fail(MinecraftClient mc, String reason) {
        running = false;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] acquire failed: {} ({})", itemId, reason);
        Memory.noteFailure("获取 " + itemId, reason);
        EventLog.note("获取失败", itemId + "：" + reason);
        BotBrain.tell("弄不到 " + itemId + "：" + reason);
    }

    /** 背包里（含副手）该物品的总数。 */
    public static int count(MinecraftClient mc, String id) {
        ClientPlayerEntity p = mc.player;
        if (p == null || id == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = p.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (Registries.ITEM.getId(stack.getItem()).getPath().equals(id)) {
                total += stack.getCount();
            }
        }
        if (!p.getOffHandStack().isEmpty()
                && Registries.ITEM.getId(p.getOffHandStack().getItem()).getPath().equals(id)) {
            total += p.getOffHandStack().getCount();
        }
        return total;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String id = raw.strip().toLowerCase(Locale.ROOT);
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
