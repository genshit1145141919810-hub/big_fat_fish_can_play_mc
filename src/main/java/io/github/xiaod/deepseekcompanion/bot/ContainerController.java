package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Drives a real crafting table / furnace: opens the block, queues slot clicks,
 * then closes the screen. One click per tick keeps it in sync with the server.
 */
public final class ContainerController {
    private enum Mode { NONE, CRAFT, CRAFT_INV, SMELT }

    private enum Phase { FIND, OPENING, CLICKS, WAIT, TAKE, CLOSE, PLACE, COLLECT }

    private record Click(int slot, int button, SlotActionType type) {
    }

    private static final Set<String> RAW_FOODS = Set.of(
            "beef", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon", "potato", "kelp");
    private static final Set<String> FUELS = Set.of("coal", "charcoal", "oak_planks", "birch_planks",
            "spruce_planks", "jungle_planks", "acacia_planks", "dark_oak_planks", "cherry_planks",
            "mangrove_planks", "crimson_planks", "warped_planks", "bamboo_planks", "stick");

    private static final Deque<Click> QUEUE = new ArrayDeque<>();
    private static Mode mode = Mode.NONE;
    private static Phase phase = Phase.FIND;
    private static BlockPos blockPos;
    private static String targetItem = "";
    private static final List<String> smeltTokens = new ArrayList<>();
    private static io.github.xiaod.deepseekcompanion.integrations.SmeltRecipes.Station station =
            io.github.xiaod.deepseekcompanion.integrations.SmeltRecipes.Station.FURNACE;
    private static int campfirePlaced;
    private static int foodSlotIndex;
    private static long collectStartAt;
    private static long lastCollectGoto;
    private static long lastSeenDropAt;
    private static long until;
    /** 合成网格宽度：工作台 3，自己背包 2。 */
    private static int gridWidth = 3;
    /** 合成树：树模式下的待执行步骤。 */
    private static final Deque<io.github.xiaod.deepseekcompanion.integrations.CraftTree.Step> treeQueue =
            new ArrayDeque<>();
    private static boolean treeMode;

    private ContainerController() {
    }

    public static boolean isBusy() {
        return mode != Mode.NONE;
    }

    public static boolean craft(MinecraftClient mc, String itemId) {
        if (mc.player == null) {
            return false;
        }
        List<io.github.xiaod.deepseekcompanion.integrations.CraftTree.Step> plan =
                io.github.xiaod.deepseekcompanion.integrations.CraftTree.plan(mc, itemId);
        io.github.xiaod.deepseekcompanion.DeepseekCompanionClient.LOGGER.info(
                "[XiaoD] craft plan for {}: {}", itemId, plan);
        if (!plan.isEmpty()) {
            treeQueue.clear();
            treeQueue.addAll(plan);
            treeMode = true;
            BotBrain.tell("合成链：" + plan);
            return startTreeStep(mc);
        }
        treeMode = false;
        return startSingleCraft(mc, itemId);
    }

    private static boolean startTreeStep(MinecraftClient mc) {
        io.github.xiaod.deepseekcompanion.integrations.CraftTree.Step step = treeQueue.poll();
        if (step == null) {
            treeMode = false;
            BotBrain.tell("合成链跑完了");
            return true;
        }
        targetItem = step.item();
        BlockPos table = findBlock(mc, Blocks.CRAFTING_TABLE, 6);
        boolean big = false;
        for (var recipe : io.github.xiaod.deepseekcompanion.integrations.CraftRecipes.find(step.item())) {
            if (recipe.width() > 2 || recipe.height() > 2) {
                big = true;
                break;
            }
        }
        if (table != null && big) {
            blockPos = table;
            gridWidth = 3;
            mode = Mode.CRAFT;
        } else if (!big) {
            gridWidth = 2;
            mode = Mode.CRAFT_INV;
        } else {
            treeQueue.clear();
            treeMode = false;
            BotBrain.tell("要合 " + step.item() + " 得有工作台，先 .xd place crafting_table");
            return false;
        }
        phase = Phase.FIND;
        BotBrain.tell("合成 " + step.item() + "（" + step.crafts() + " 次）");
        return true;
    }

    private static boolean startSingleCraft(MinecraftClient mc, String itemId) {
        if (io.github.xiaod.deepseekcompanion.integrations.CraftRecipes.find(itemId).isEmpty()) {
            BotBrain.say("我不知道 " + itemId + " 怎么合（原版配方表里没有）");
            return false;
        }
        BlockPos table = findBlock(mc, Blocks.CRAFTING_TABLE, 6);
        if (table != null) {
            blockPos = table;
            targetItem = itemId;
            gridWidth = 3;
            mode = Mode.CRAFT;
            phase = Phase.FIND;
            return true;
        }
        // 没有工作台：宽高都 ≤2 的配方可以用自己背包里那 2x2 合成
        if (!fitsInventory(itemId)) {
            BotBrain.tell("附近没工作台，" + itemId + " 得用 3x3 的台子，先给我个工作台");
            return false;
        }
        targetItem = itemId;
        gridWidth = 2;
        mode = Mode.CRAFT_INV;
        phase = Phase.FIND;
        return true;
    }

    /** 有没有宽高都 ≤2 的配方（背包 2x2 装得下）。 */
    private static boolean fitsInventory(String itemId) {
        for (var recipe : io.github.xiaod.deepseekcompanion.integrations.CraftRecipes.find(itemId)) {
            if (recipe.width() <= 2 && recipe.height() <= 2) {
                return true;
            }
        }
        return false;
    }

    /** {@code itemArg} empty = smelt whatever is worth smelting (food first). */
    public static boolean cook(MinecraftClient mc, String itemArg) {
        if (mc.player == null) {
            return false;
        }
        smeltTokens.clear();
        if (itemArg != null && !itemArg.isBlank()) {
            String id = itemArg.strip();
            if (id.startsWith("minecraft:")) {
                id = id.substring("minecraft:".length());
            }
            var asInput = io.github.xiaod.deepseekcompanion.integrations.SmeltRecipes.byInputName(id);
            if (asInput != null) {
                smeltTokens.add(asInput.inputToken());
            }
            for (var entry : io.github.xiaod.deepseekcompanion.integrations.SmeltRecipes.byOutput(id)) {
                smeltTokens.add(entry.inputToken());
            }
            if (smeltTokens.isEmpty()) {
                BotBrain.say("没有把 " + id + " 烧出来的配方");
                return false;
            }
        }
        PlayerInventory inventory = mc.player.getInventory();
        int slot = findSmeltInput(inventory);
        if (slot < 0) {
            BotBrain.say(smeltTokens.isEmpty() ? "身上没有能烧的东西" : "身上没有这些材料");
            return false;
        }
        var stations = io.github.xiaod.deepseekcompanion.integrations.SmeltRecipes
                .byInput(inventory.getStack(slot));
        if (stations.isEmpty()) {
            BotBrain.say("这个东西烧不了");
            return false;
        }
        for (var entry : stations) {
            BlockPos candidate = switch (entry.station()) {
                case BLAST -> findBlock(mc, Blocks.BLAST_FURNACE, 6);
                case SMOKER -> findBlock(mc, Blocks.SMOKER, 6);
                case FURNACE -> findBlock(mc, Blocks.FURNACE, 6);
                case CAMPFIRE -> firstCampfire(mc);
            };
            if (candidate != null) {
                if (entry.station()
                        == io.github.xiaod.deepseekcompanion.integrations.SmeltRecipes.Station.CAMPFIRE
                        && !net.minecraft.block.CampfireBlock.isLitCampfire(mc.world.getBlockState(candidate))) {
                    continue; // an unlit campfire never cooks
                }
                station = entry.station();
                blockPos = candidate;
                foodSlotIndex = slot;
                mode = Mode.SMELT;
                phase = Phase.FIND;
                return true;
            }
        }
        BotBrain.say("附近没有能烧它的炉子（熔炉/高炉/烟熏炉/营火）");
        return false;
    }

    private static BlockPos firstCampfire(MinecraftClient mc) {
        BlockPos pos = findBlock(mc, Blocks.CAMPFIRE, 6);
        return pos != null ? pos : findBlock(mc, Blocks.SOUL_CAMPFIRE, 6);
    }

    private static boolean isCampfire(MinecraftClient mc) {
        var state = mc.world.getBlockState(blockPos);
        return state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE);
    }

    public static void cancel(MinecraftClient mc) {
        QUEUE.clear();
        treeQueue.clear();
        treeMode = false;
        mode = Mode.NONE;
        phase = Phase.FIND;
        if (mc.player != null && mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }
    }

    public static void tick(MinecraftClient mc) {
        if (mode == Mode.NONE) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null) {
            cancel(mc);
            return;
        }
        long now = System.currentTimeMillis();

        switch (phase) {
            case FIND -> {
                if (mode == Mode.CRAFT_INV) {
                    // 不打开界面：玩家自己的 PlayerScreenHandler 始终是激活的（syncId 0），
                    // 直接往里点就行。创造模式下客户端不允许保留 InventoryScreen，
                    // 之前就是卡在"界面没打开"。
                    if (!(p.currentScreenHandler instanceof net.minecraft.screen.PlayerScreenHandler)) {
                        if (mc.currentScreen != null) {
                            p.closeHandledScreen();
                        }
                        return;
                    }
                    buildCraftClicks(mc, p.currentScreenHandler);
                    phase = Phase.CLICKS;
                    return;
                }
                p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(blockPos));
                if (mode == Mode.SMELT
                        && station == io.github.xiaod.deepseekcompanion.integrations.SmeltRecipes.Station.CAMPFIRE) {
                    // campfire has no GUI: hold the food and right-click to place
                    int hotbar = ensureHotbar(mc, p, foodSlotIndex);
                    if (hotbar < 0) {
                        fail("快捷栏满了，腾个位置给我");
                        return;
                    }
                    p.getInventory().setSelectedSlot(hotbar);
                    campfirePlaced = 0;
                    until = 0;
                    phase = Phase.PLACE;
                    return;
                }
                BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(blockPos), Direction.UP, blockPos, false);
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
                phase = Phase.OPENING;
                until = now + 3000L;
            }
            case PLACE -> {
                if (campfirePlaced >= 4 || p.getMainHandStack().isEmpty()) {
                    phase = Phase.WAIT;
                    until = now + 32_000L; // campfire cooks one item per 30s
                    return;
                }
                if (now < until) {
                    return;
                }
                p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(blockPos));
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(blockPos), Direction.UP, blockPos, false));
                campfirePlaced++;
                until = now + 300L;
            }
            case COLLECT -> {
                // Campfire cooking pops the finished item out as an item entity
                // (ItemScatterer.spawn in litServerTick), so wait and pick it up -
                // breaking the campfire would only yield the raw leftovers.
                if (now > until) {
                    mode = Mode.NONE;
                    return;
                }
                var drops = mc.world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                        new net.minecraft.util.math.Box(blockPos).expand(4.0),
                        e -> !e.isRemoved() && !e.getStack().isEmpty());
                if (drops.isEmpty()) {
                    if (lastSeenDropAt > 0 && now - lastSeenDropAt > 2000L) {
                        mode = Mode.NONE; // collected
                    } else if (now - collectStartAt > 35_000L) {
                        mode = Mode.NONE; // nothing ever came out
                    }
                    return;
                }
                lastSeenDropAt = now;
                double dx = p.getX() - (blockPos.getX() + 0.5);
                double dz = p.getZ() - (blockPos.getZ() + 0.5);
                if (dx * dx + dz * dz > 4.0 && now - lastCollectGoto > 2500L && BaritoneBridge.available()) {
                    BaritoneBridge.gotoPos(blockPos.getX(), blockPos.getY() + 1, blockPos.getZ());
                    lastCollectGoto = now;
                }
            }
            case OPENING -> {
                ScreenHandler handler = p.currentScreenHandler;
                boolean right = switch (mode) {
                    case CRAFT -> handler instanceof CraftingScreenHandler;
                    case CRAFT_INV -> handler instanceof net.minecraft.screen.PlayerScreenHandler;
                    case SMELT -> handler instanceof net.minecraft.screen.AbstractFurnaceScreenHandler;
                    default -> false;
                };
                if (!right) {
                    if (now > until) {
                        fail("界面没打开");
                    }
                    return;
                }
                io.github.xiaod.deepseekcompanion.DeepseekCompanionClient.LOGGER.info("[XiaoD] opened handler {} (mode={}, gridWidth={})",
                        handler.getClass().getSimpleName(), mode, gridWidth);
                if (mode == Mode.CRAFT || mode == Mode.CRAFT_INV) {
                    buildCraftClicks(mc, handler);
                } else {
                    buildSmeltClicks(mc, handler);
                }
                phase = Phase.CLICKS;
            }
            case CLICKS -> {
                Click click = QUEUE.poll();
                if (click == null) {
                    p.closeHandledScreen();
                    if (mode == Mode.SMELT) {
                        phase = Phase.WAIT;
                        until = now + 12_000L;
                    } else if (treeMode) {
                        mode = Mode.NONE;
                        startTreeStep(mc);
                    } else {
                        mode = Mode.NONE;
                    }
                    return;
                }
                io.github.xiaod.deepseekcompanion.DeepseekCompanionClient.LOGGER.info("[XiaoD] click slot={} button={} type={} stack={} remaining={}",
                        click.slot(), click.button(), click.type(),
                        p.currentScreenHandler.slots.get(click.slot()).getStack().getItem(),
                        QUEUE.size());
                mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, click.slot(),
                        click.button(), click.type(), p);
            }
            case WAIT -> {
                if (now < until) {
                    return;
                }
                if (station == io.github.xiaod.deepseekcompanion.integrations.SmeltRecipes.Station.CAMPFIRE) {
                    phase = Phase.COLLECT;
                    collectStartAt = now;
                    lastSeenDropAt = 0L;
                    until = now + 60_000L;
                    return;
                }
                if (!(p.currentScreenHandler instanceof net.minecraft.screen.AbstractFurnaceScreenHandler)) {
                    p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(blockPos));
                    mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                            new BlockHitResult(Vec3d.ofCenter(blockPos), Direction.UP, blockPos, false));
                }
                phase = Phase.TAKE;
                until = now + 3000L;
            }
            case TAKE -> {
                if (!(p.currentScreenHandler instanceof net.minecraft.screen.AbstractFurnaceScreenHandler)) {
                    if (now > until) {
                        fail("熔炉没打开");
                    }
                    return;
                }
                mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, 2, 0, SlotActionType.QUICK_MOVE, p);
                phase = Phase.CLOSE;
                until = now + 400L;
            }
            case CLOSE -> {
                if (now >= until) {
                    p.closeHandledScreen();
                    mode = Mode.NONE;
                }
            }
        }
    }

    private static void fail(String why) {
        DeepseekCompanionClient.LOGGER.warn("[XiaoD] container action failed: {}", why);
        BotBrain.say(why);
        QUEUE.clear();
        mode = Mode.NONE;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.currentScreen != null) {
            mc.player.closeHandledScreen(); // never leave a screen open behind us
        }
    }

    // ------------------------------------------------------------------ craft

    private static void buildCraftClicks(MinecraftClient mc, ScreenHandler handler) {
        List<io.github.xiaod.deepseekcompanion.integrations.CraftRecipes.Recipe> candidates =
                io.github.xiaod.deepseekcompanion.integrations.CraftRecipes.find(targetItem);
        if (candidates.isEmpty()) {
            fail("不知道 " + targetItem + " 怎么合");
            return;
        }
        PlayerInventory inventory = mc.player.getInventory();
        for (var recipe : candidates) {
            if (recipe.width() > gridWidth || recipe.height() > gridWidth) {
                continue; // 2x2 网格装不下 3x3 的配方
            }
            List<Click> plan = new ArrayList<>();
            boolean ok = true;
            for (int row = 0; row < recipe.height() && ok; row++) {
                for (int col = 0; col < recipe.width() && ok; col++) {
                    String token = recipe.cells()[row * recipe.width() + col];
                    if (token == null) {
                        continue;
                    }
                    int invSlot = findCraftSlot(inventory, token);
                    if (invSlot < 0) {
                        ok = false;
                        break;
                    }
                    int handlerSlot = handlerSlot(handler, inventory, invSlot);
                    if (handlerSlot < 0) {
                        ok = false;
                        break;
                    }
                    int gridSlot = 1 + row * gridWidth + col;
                    plan.add(new Click(handlerSlot, 0, SlotActionType.PICKUP));
                    plan.add(new Click(gridSlot, 1, SlotActionType.PICKUP));
                    plan.add(new Click(handlerSlot, 0, SlotActionType.PICKUP));
                }
            }
            if (ok) {
                io.github.xiaod.deepseekcompanion.DeepseekCompanionClient.LOGGER.info("[XiaoD] craft recipe {} grid={}x{} cells={} clicks={}",
                        recipe.result(), recipe.width(), recipe.height(),
                        java.util.Arrays.toString(recipe.cells()), plan.size());
                QUEUE.addAll(plan);
                QUEUE.add(new Click(0, 0, SlotActionType.QUICK_MOVE));
                return;
            }
        }
        fail("材料不够: " + targetItem);
    }

    private static int findCraftSlot(PlayerInventory inventory, String token) {
        for (int slot = 0; slot < 36; slot++) {
            if (io.github.xiaod.deepseekcompanion.integrations.CraftRecipes
                    .matches(token, inventory.getStack(slot))) {
                return slot;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ smelt

    private static void buildSmeltClicks(MinecraftClient mc, ScreenHandler handler) {
        PlayerInventory inventory = mc.player.getInventory();
        int foodSlot = foodSlotIndex;
        if (foodSlot < 0 || foodSlot > 35 || inventory.getStack(foodSlot).isEmpty()) {
            fail("东西不在背包里了");
            return;
        }
        int fuelSlot = findAnyInventorySlot(inventory, FUELS);
        if (fuelSlot < 0) {
            fail("没有燃料（煤/木炭/木板）");
            return;
        }
        int foodHandler = handlerSlot(handler, inventory, foodSlot);
        int fuelHandler = handlerSlot(handler, inventory, fuelSlot);
        if (foodHandler < 0 || fuelHandler < 0) {
            fail("找不到背包槽位");
            return;
        }
        QUEUE.add(new Click(foodHandler, 0, SlotActionType.PICKUP));
        QUEUE.add(new Click(0, 0, SlotActionType.PICKUP));   // furnace input
        QUEUE.add(new Click(fuelHandler, 0, SlotActionType.PICKUP));
        QUEUE.add(new Click(1, 0, SlotActionType.PICKUP));   // furnace fuel
    }

    private static int ensureHotbar(MinecraftClient mc, ClientPlayerEntity p, int slotIndex) {
        if (slotIndex < 9) {
            return slotIndex;
        }
        var inventory = p.getInventory();
        int free = -1;
        for (int i = 0; i < 9; i++) {
            if (inventory.getStack(i).isEmpty()) {
                free = i;
                break;
            }
        }
        if (free < 0) {
            return -1;
        }
        try {
            mc.interactionManager.clickSlot(0, slotIndex, free, SlotActionType.SWAP, p);
        } catch (Throwable t) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] hotbar swap failed", t);
            return -1;
        }
        return free;
    }

    private static int findSmeltInput(PlayerInventory inventory) {
        if (!smeltTokens.isEmpty()) {
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getStack(slot);
                for (String token : smeltTokens) {
                    if (io.github.xiaod.deepseekcompanion.integrations.CraftRecipes.matches(token, stack)) {
                        return slot;
                    }
                }
            }
            return -1;
        }
        // no explicit item: prefer food, then anything with a smelting recipe
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (stack.isEmpty()
                        || io.github.xiaod.deepseekcompanion.integrations.SmeltRecipes.byInput(stack) == null) {
                    continue;
                }
                if (pass == 0 && !stack.contains(net.minecraft.component.DataComponentTypes.FOOD)) {
                    continue;
                }
                return slot;
            }
        }
        return -1;
    }

    private static int findAnyInventorySlot(PlayerInventory inventory, Set<String> ids) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (ids.contains(Registries.ITEM.getId(stack.getItem()).getPath())) {
                return slot;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- helpers

    private static int handlerSlot(ScreenHandler handler, PlayerInventory inventory, int invIndex) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == inventory && slot.getIndex() == invIndex) {
                return slot.id;
            }
        }
        return -1;
    }

    private static BlockPos findBlock(MinecraftClient mc, net.minecraft.block.Block block, int radius) {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        BlockPos origin = mc.player.getBlockPos();
        int steps = 0;
        for (BlockPos pos : BlockPos.iterateOutwards(origin, radius, radius, radius)) {
            if (++steps > 4000) {
                break;
            }
            if (mc.world.getBlockState(pos).isOf(block)) {
                return pos.toImmutable();
            }
        }
        return null;
    }
}

