package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Right-click interactions (doors, buttons, beds, cauldrons, composters, note
 * blocks, jukeboxes...) plus the brewing stand GUI.
 *
 * <p>Simple blocks are used by holding the right item and right-clicking; the
 * game itself decides what happens (a water bucket fills a cauldron, a glass
 * bottle takes water, leather armour gets washed...). The brewing stand needs
 * its own slot plan: three bottles, one ingredient, one fuel.</p>
 */
public final class InteractController {
    private enum Mode { NONE, HOLD, USE, BREW }

    private enum Phase { FIND, APPROACH, ACT, OPENING, CLICKS, WAIT, TAKE, CLOSE }

    private record Click(int slot, int button, SlotActionType type) {
    }

    private static final Deque<Click> QUEUE = new ArrayDeque<>();
    private static Mode mode = Mode.NONE;
    private static Phase phase = Phase.FIND;
    private static String blockId = "";
    private static String itemId = "";
    private static BlockPos blockPos;
    private static long until;
    private static long lastGoto;
    private static long lastSeen;

    private InteractController() {
    }

    public static boolean isBusy() {
        return mode != Mode.NONE;
    }

    public static boolean hold(MinecraftClient mc, String id) {
        if (mc.player == null) {
            return false;
        }
        int hotbar = ensureHotbar(mc, mc.player, id);
        if (hotbar < 0) {
            BotBrain.say("手上拿不到 " + id);
            return false;
        }
        mc.player.getInventory().setSelectedSlot(hotbar);
        return true;
    }

    public static boolean use(MinecraftClient mc, String targetBlock) {
        if (mc.player == null) {
            return false;
        }
        Block block = Registries.BLOCK.get(Identifier.of("minecraft", strip(targetBlock)));
        if (block == Blocks.AIR) {
            BotBrain.say("没有这个方块: " + targetBlock);
            return false;
        }
        blockId = Registries.BLOCK.getId(block).getPath();
        blockPos = null;
        mode = Mode.USE;
        phase = Phase.FIND;
        return true;
    }

    public static boolean brew(MinecraftClient mc, String ingredient) {
        if (mc.player == null) {
            return false;
        }
        itemId = strip(ingredient);
        blockPos = findBlock(mc, Blocks.BREWING_STAND, 6);
        if (blockPos == null) {
            BotBrain.say("附近没有酿造台");
            return false;
        }
        mode = Mode.BREW;
        phase = Phase.FIND;
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
                if (mode == Mode.USE) {
                    BlockPos found = findBlockByName(mc, blockId, 8);
                    if (found == null) {
                        BotBrain.say("附近没有 " + blockId);
                        cancel(mc);
                        return;
                    }
                    blockPos = found;
                }
                phase = Phase.APPROACH;
            }
            case APPROACH -> {
                double distance = Math.sqrt(p.squaredDistanceTo(
                        blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5));
                if (distance > 4.0) {
                    if (now - lastGoto > 2500L && BaritoneBridge.available()) {
                        BaritoneBridge.gotoPos(blockPos.getX(), blockPos.getY() + 1, blockPos.getZ());
                        lastGoto = now;
                    }
                    return;
                }
                if (BaritoneBridge.available()) {
                    BaritoneBridge.stop();
                }
                if (mode == Mode.USE) {
                    p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(blockPos));
                    mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                            new BlockHitResult(Vec3d.ofCenter(blockPos), Direction.UP, blockPos, false));
                    DeepseekCompanionClient.LOGGER.info("[XiaoD] used {}", blockId);
                    cancel(mc);
                    return;
                }
                phase = Phase.OPENING;
                until = now + 3000L;
                p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(blockPos));
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(blockPos), Direction.UP, blockPos, false));
            }
            case OPENING -> {
                if (!(p.currentScreenHandler instanceof BrewingStandScreenHandler handler)) {
                    if (now > until) {
                        fail("酿造台没打开");
                    }
                    return;
                }
                buildBrewClicks(mc, handler);
                phase = Phase.CLICKS;
            }
            case CLICKS -> {
                Click click = QUEUE.poll();
                if (click == null) {
                    p.closeHandledScreen();
                    phase = Phase.WAIT;
                    until = now + 25_000L; // brewing takes 20s
                    return;
                }
                mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, click.slot(),
                        click.button(), click.type(), p);
            }
            case WAIT -> {
                if (now < until) {
                    return;
                }
                if (!(p.currentScreenHandler instanceof BrewingStandScreenHandler)) {
                    p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(blockPos));
                    mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                            new BlockHitResult(Vec3d.ofCenter(blockPos), Direction.UP, blockPos, false));
                }
                phase = Phase.TAKE;
                until = now + 3000L;
            }
            case TAKE -> {
                if (!(p.currentScreenHandler instanceof BrewingStandScreenHandler)) {
                    if (now > until) {
                        fail("酿造台没打开");
                    }
                    return;
                }
                for (int slot = 0; slot <= 2; slot++) {
                    QUEUE.add(new Click(slot, 0, SlotActionType.QUICK_MOVE));
                }
                phase = Phase.CLOSE;
                until = now + 1000L;
            }
            case CLOSE -> {
                if (now < until) {
                    return;
                }
                Click click = QUEUE.poll();
                if (click != null) {
                    mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, click.slot(),
                            click.button(), click.type(), p);
                    return;
                }
                p.closeHandledScreen();
                mode = Mode.NONE;
            }
            default -> mode = Mode.NONE;
        }
    }

    private static void buildBrewClicks(MinecraftClient mc, ScreenHandler handler) {
        var inventory = mc.player.getInventory();
        int bottles = 0;
        for (int slot = 0; slot < 36 && bottles < 3; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !Registries.ITEM.getId(stack.getItem()).getPath().contains("potion")) {
                continue;
            }
            int handlerSlot = handlerSlot(handler, inventory, slot);
            if (handlerSlot >= 0) {
                QUEUE.add(new Click(handlerSlot, 0, SlotActionType.QUICK_MOVE));
                bottles++;
            }
        }
        if (bottles == 0) {
            fail("身上没有药水瓶");
            return;
        }
        int ingredient = findSlot(inventory, itemId);
        if (ingredient < 0) {
            fail("身上没有 " + itemId);
            return;
        }
        int ingredientHandler = handlerSlot(handler, inventory, ingredient);
        if (ingredientHandler >= 0) {
            QUEUE.add(new Click(ingredientHandler, 0, SlotActionType.QUICK_MOVE));
        }
        int fuel = findSlot(inventory, "blaze_powder");
        if (fuel >= 0) {
            int fuelHandler = handlerSlot(handler, inventory, fuel);
            if (fuelHandler >= 0) {
                QUEUE.add(new Click(fuelHandler, 0, SlotActionType.QUICK_MOVE));
            }
        }
    }

    private static int findSlot(net.minecraft.entity.player.PlayerInventory inventory, String id) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getPath().equals(id)) {
                return slot;
            }
        }
        return -1;
    }

    private static int ensureHotbar(MinecraftClient mc, ClientPlayerEntity p, String id) {
        int slot = findSlot(p.getInventory(), id);
        if (slot < 0) {
            return -1;
        }
        if (slot < 9) {
            return slot;
        }
        int free = -1;
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isEmpty()) {
                free = i;
                break;
            }
        }
        if (free < 0) {
            return -1;
        }
        try {
            mc.interactionManager.clickSlot(0, slot, free, SlotActionType.SWAP, p);
        } catch (Throwable t) {
            return -1;
        }
        return free;
    }

    private static int handlerSlot(ScreenHandler handler, net.minecraft.entity.player.PlayerInventory inventory, int index) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == inventory && slot.getIndex() == index) {
                return slot.id;
            }
        }
        return -1;
    }

    private static BlockPos findBlockByName(MinecraftClient mc, String id, int radius) {
        for (BlockPos pos : BlockPos.iterateOutwards(mc.player.getBlockPos(), radius, radius, radius)) {
            if (Registries.BLOCK.getId(mc.world.getBlockState(pos).getBlock()).getPath().equals(id)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos findBlock(MinecraftClient mc, Block block, int radius) {
        for (BlockPos pos : BlockPos.iterateOutwards(mc.player.getBlockPos(), radius, radius, radius)) {
            if (mc.world.getBlockState(pos).isOf(block)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private static String strip(String id) {
        String value = id == null ? "" : id.strip();
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    private static void fail(String why) {
        DeepseekCompanionClient.LOGGER.warn("[XiaoD] interact failed: {}", why);
        BotBrain.say(why);
        QUEUE.clear();
        mode = Mode.NONE;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }
    }
}
