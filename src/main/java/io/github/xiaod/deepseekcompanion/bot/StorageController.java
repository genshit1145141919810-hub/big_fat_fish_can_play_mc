package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.command.LocalCommandHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Deposits junk into, or withdraws items from, the nearest chest. Both directions
 * are a queue of shift-clicks on the real chest screen, one per tick.
 */
public final class StorageController {
    private enum Mode { NONE, STORE, TAKE }

    private enum Phase { FIND, OPENING, CLICKS, CLOSE }

    private record Click(int slot, int button, SlotActionType type) {
    }

    private static final Deque<Click> QUEUE = new ArrayDeque<>();
    private static final Set<String> WANTED = new HashSet<>();
    private static Mode mode = Mode.NONE;
    private static Phase phase = Phase.FIND;
    private static BlockPos chestPos;
    private static boolean explicit;
    private static long until;

    private StorageController() {
    }

    public static boolean isBusy() {
        return mode != Mode.NONE;
    }

    /** {@code items} empty means "whatever I do not use". */
    public static boolean store(MinecraftClient mc, String items) {
        if (mc.player == null) {
            return false;
        }
        if (!prepare(mc, items)) {
            return false;
        }
        mode = Mode.STORE;
        phase = Phase.FIND;
        return true;
    }

    public static boolean take(MinecraftClient mc, String items) {
        if (mc.player == null || items == null || items.isBlank()) {
            BotBrain.say("要说取什么，比如 .xd take iron_ingot");
            return false;
        }
        if (!prepare(mc, items)) {
            return false;
        }
        explicit = true;
        mode = Mode.TAKE;
        phase = Phase.FIND;
        return true;
    }

    private static boolean prepare(MinecraftClient mc, String items) {
        BlockPos pos = findChest(mc);
        if (pos == null) {
            BotBrain.say("附近没有箱子");
            return false;
        }
        chestPos = pos;
        WANTED.clear();
        explicit = items != null && !items.isBlank();
        if (explicit) {
            for (String part : items.split(",")) {
                String id = part.strip();
                if (id.startsWith("minecraft:")) {
                    id = id.substring("minecraft:".length());
                }
                if (!id.isEmpty()) {
                    WANTED.add(id);
                }
            }
        }
        return true;
    }

    public static void cancel(MinecraftClient mc) {
        QUEUE.clear();
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
                p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(chestPos));
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false));
                phase = Phase.OPENING;
                until = now + 3000L;
            }
            case OPENING -> {
                if (!(p.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
                    if (now > until) {
                        fail("箱子没打开");
                    }
                    return;
                }
                buildClicks(mc, handler);
                phase = Phase.CLICKS;
            }
            case CLICKS -> {
                Click click = QUEUE.poll();
                if (click == null) {
                    phase = Phase.CLOSE;
                    until = now + 200L;
                    return;
                }
                mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, click.slot(),
                        click.button(), click.type(), p);
            }
            case CLOSE -> {
                if (now >= until) {
                    p.closeHandledScreen();
                    mode = Mode.NONE;
                    DeepseekCompanionClient.LOGGER.info("[XiaoD] storage action done");
                }
            }
        }
    }

    private static void buildClicks(MinecraftClient mc, ScreenHandler handler) {
        PlayerInventory inventory = mc.player.getInventory();
        int limit = 24;
        if (mode == Mode.STORE) {
            for (int index = 0; index < 36 && QUEUE.size() < limit; index++) {
                ItemStack stack = inventory.getStack(index);
                if (stack.isEmpty() || !shouldStore(stack)) {
                    continue;
                }
                int slot = handlerSlot(handler, inventory, index);
                if (slot >= 0) {
                    QUEUE.add(new Click(slot, 0, SlotActionType.QUICK_MOVE));
                }
            }
        } else {
            for (Slot slot : handler.slots) {
                if (QUEUE.size() >= limit) {
                    break;
                }
                if (slot.inventory == inventory || slot.getStack().isEmpty()) {
                    continue;
                }
                if (WANTED.contains(Registries.ITEM.getId(slot.getStack().getItem()).getPath())) {
                    QUEUE.add(new Click(slot.id, 0, SlotActionType.QUICK_MOVE));
                }
            }
        }
        if (QUEUE.isEmpty()) {
            fail(mode == Mode.STORE ? "没有要存的东西" : "箱子里没有你要的");
        }
    }

    private static boolean shouldStore(ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        if (explicit) {
            return WANTED.contains(id);
        }
        return !isKeep(stack);
    }

    /** Things the bot keeps on it: gear, food, light, arrows, valuables. */
    private static boolean isKeep(ItemStack stack) {
        var item = stack.getItem();
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)
                || stack.isIn(net.minecraft.registry.tag.ItemTags.AXES)
                || stack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)
                || stack.isIn(net.minecraft.registry.tag.ItemTags.SHOVELS)
                || stack.isIn(net.minecraft.registry.tag.ItemTags.HOES)
                || stack.get(net.minecraft.component.DataComponentTypes.EQUIPPABLE) != null) {
            return true;
        }
        if (stack.contains(net.minecraft.component.DataComponentTypes.FOOD)) {
            return true;
        }
        String id = Registries.ITEM.getId(item).getPath();
        return id.contains("torch") || id.equals("arrow") || id.endsWith("bucket")
                || id.contains("diamond") || id.contains("emerald") || id.contains("netherite")
                || id.equals("iron_ingot") || id.equals("gold_ingot");
    }

    private static int handlerSlot(ScreenHandler handler, PlayerInventory inventory, int index) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == inventory && slot.getIndex() == index) {
                return slot.id;
            }
        }
        return -1;
    }

    private static BlockPos findChest(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        BlockPos origin = mc.player.getBlockPos();
        int steps = 0;
        for (BlockPos pos : BlockPos.iterateOutwards(origin, 8, 8, 8)) {
            if (++steps > 5000) {
                break;
            }
            var state = mc.world.getBlockState(pos);
            if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private static void fail(String why) {
        DeepseekCompanionClient.LOGGER.warn("[XiaoD] storage failed: {}", why);
        BotBrain.say(why);
        QUEUE.clear();
        mode = Mode.NONE;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }
    }
}

