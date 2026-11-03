package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.screen.LoomScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One generic driver for the workstation GUIs: place items into the input slots,
 * optionally press the GUI buttons until the result slot shows what we want, then
 * shift-click the result out.
 *
 * <p>Stations whose choice is a button list (stonecutter, loom, enchanting) are
 * handled by scanning buttons instead of depending on internal recipe order.</p>
 */
public final class StationController {
    /** One station description: block, screen handler, input slots, result slot. */
    private record Def(String name, Block block, Class<? extends ScreenHandler> handler,
                       int[] inputs, int result, int maxButtons) {
    }

    private static final List<Def> DEFS = List.of(
            new Def("grindstone", Blocks.GRINDSTONE, GrindstoneScreenHandler.class,
                    new int[]{0, 1}, 2, 0),
            new Def("smithing", Blocks.SMITHING_TABLE, SmithingScreenHandler.class,
                    new int[]{0, 1, 2}, 3, 0),
            new Def("cartography", Blocks.CARTOGRAPHY_TABLE, CartographyTableScreenHandler.class,
                    new int[]{0, 1}, 2, 0),
            new Def("stonecutter", Blocks.STONECUTTER, StonecutterScreenHandler.class,
                    new int[]{0}, 1, 64),
            new Def("loom", Blocks.LOOM, LoomScreenHandler.class,
                    new int[]{0, 1, 2}, 3, 16),
            new Def("anvil", Blocks.ANVIL, AnvilScreenHandler.class,
                    new int[]{0, 1}, 2, 0),
            new Def("enchanting", Blocks.ENCHANTING_TABLE, EnchantmentScreenHandler.class,
                    new int[]{0}, 0, 1));

    private enum Phase { FIND, OPENING, PLACE, SELECT, TAKE, CLOSE }

    private static final Deque<Click> QUEUE = new ArrayDeque<>();
    private static final List<String> wanted = new ArrayList<>();
    private static Def def;
    private static String desiredOutput = "";
    private static BlockPos blockPos;
    private static Phase phase = Phase.FIND;
    private static boolean active;
    private static int button;
    private static long until;
    private static long lastGoto;

    private record Click(int slot, int button, SlotActionType type) {
    }

    private StationController() {
    }

    public static boolean isBusy() {
        return active;
    }

    /** {@code args} is a comma list: station name first, then item ids. */
    public static boolean start(MinecraftClient mc, String args) {
        if (mc.player == null || args == null || args.isBlank()) {
            return false;
        }
        String[] parts = args.split(",");
        String name = parts[0].strip();
        Def found = DEFS.stream().filter(d -> d.name().equals(name)).findFirst().orElse(null);
        if (found == null) {
            BotBrain.say("不认识的站点: " + name);
            return false;
        }
        wanted.clear();
        for (int i = 1; i < parts.length; i++) {
            String id = parts[i].strip();
            if (!id.isEmpty()) {
                wanted.add(id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id);
            }
        }
        desiredOutput = wanted.size() > 1 && found.name().equals("stonecutter") ? wanted.get(1) : "";
        def = found;
        blockPos = findBlock(mc, found);
        if (blockPos == null) {
            BotBrain.say("附近没有 " + name);
            return false;
        }
        active = true;
        phase = Phase.FIND;
        button = 0;
        return true;
    }

    public static void cancel(MinecraftClient mc) {
        active = false;
        QUEUE.clear();
        phase = Phase.FIND;
        if (mc.player != null && mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }
    }

    public static void tick(MinecraftClient mc) {
        if (!active) {
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
                p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(blockPos));
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(blockPos), Direction.UP, blockPos, false));
                phase = Phase.OPENING;
                until = now + 3000L;
            }
            case OPENING -> {
                if (!def.handler().isInstance(p.currentScreenHandler)) {
                    if (now > until) {
                        fail("界面没打开");
                    }
                    return;
                }
                planPlacement(mc, p.currentScreenHandler);
                phase = Phase.PLACE;
            }
            case PLACE -> {
                Click click = QUEUE.poll();
                if (click != null) {
                    mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, click.slot(),
                            click.button(), click.type(), p);
                    return;
                }
                if (def.maxButtons() > 0) {
                    phase = Phase.SELECT;
                    button = 0;
                    until = now;
                    return;
                }
                phase = Phase.TAKE;
            }
            case SELECT -> {
                if (now < until) {
                    return;
                }
                if (!def.handler().isInstance(p.currentScreenHandler)) {
                    fail("界面关了");
                    return;
                }
                // stonecutter: the client has the recipe list, so jump straight to the
                // right button instead of scanning
                if (def.name().equals("stonecutter") && button == 0 && !desiredOutput.isEmpty()) {
                    int index = stonecutterIndex(mc, wanted.get(0), desiredOutput);
                    if (index >= 0 && mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(
                                new ButtonClickC2SPacket(p.currentScreenHandler.syncId, index));
                        button = index + 1;
                        phase = Phase.TAKE;
                        until = now + 350L;
                        return;
                    }
                }
                if (button >= def.maxButtons()) {
                    fail("按钮试完了也没出结果");
                    return;
                }
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(
                            new ButtonClickC2SPacket(p.currentScreenHandler.syncId, button));
                }
                button++;
                phase = Phase.TAKE;
                until = now + 350L;
            }
            case TAKE -> {
                if (now < until) {
                    return;
                }
                if (!def.handler().isInstance(p.currentScreenHandler)) {
                    fail("界面关了");
                    return;
                }
                ItemStack result = p.currentScreenHandler.getSlot(def.result()).getStack();
                boolean good = desiredOutput.isEmpty()
                        ? !result.isEmpty()
                        : Registries.ITEM.getId(result.getItem()).getPath().equals(desiredOutput);
                if (!good && def.maxButtons() > 0) {
                    phase = Phase.SELECT;
                    return;
                }
                if (result.isEmpty()) {
                    fail("没有产物（材料或条件不满足）");
                    return;
                }
                QUEUE.add(new Click(def.result(), 0, SlotActionType.QUICK_MOVE));
                phase = Phase.CLOSE;
                until = now + 400L;
            }
            case CLOSE -> {
                Click click = QUEUE.poll();
                if (click != null) {
                    mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, click.slot(),
                            click.button(), click.type(), p);
                    return;
                }
                if (now >= until) {
                    p.closeHandledScreen();
                    active = false;
                    DeepseekCompanionClient.LOGGER.info("[XiaoD] station {} done", def.name());
                }
            }
        }
    }

    private static void planPlacement(MinecraftClient mc, ScreenHandler handler) {
        var inventory = mc.player.getInventory();
        for (int i = 0; i < def.inputs().length; i++) {
            String id = i < wanted.size() ? wanted.get(i) : null;
            if (id == null) {
                continue;
            }
            int slot = findSlot(inventory, id);
            if (slot < 0) {
                fail("身上没有 " + id);
                return;
            }
            int handlerSlot = handlerSlot(handler, inventory, slot);
            if (handlerSlot >= 0) {
                QUEUE.add(new Click(handlerSlot, 0, SlotActionType.QUICK_MOVE));
            }
        }
        // enchanting also needs lapis in slot 1
        if (def.name().equals("enchanting")) {
            int lapis = findSlot(inventory, "lapis_lazuli");
            if (lapis >= 0) {
                int handlerSlot = handlerSlot(handler, inventory, lapis);
                if (handlerSlot >= 0) {
                    QUEUE.add(new Click(handlerSlot, 0, SlotActionType.QUICK_MOVE));
                }
            }
        }
    }

    /** Index of the stonecutter button that produces {@code outputId} from {@code inputId}. */
    private static int stonecutterIndex(MinecraftClient mc, String inputId, String outputId) {
        try {
            int inputSlot = findSlot(mc.player.getInventory(), inputId);
            if (inputSlot < 0) {
                return -1;
            }
            ItemStack input = mc.player.getInventory().getStack(inputSlot);
            var grouping = mc.world.getRecipeManager().getStonecutterRecipes();
            var params = net.minecraft.recipe.display.SlotDisplayContexts.createParameters(mc.world);
            int index = 0;
            for (var entry : grouping.filter(input).entries()) {
                ItemStack out = entry.recipe().optionDisplay().getFirst(params);
                if (!out.isEmpty() && Registries.ITEM.getId(out.getItem()).getPath().equals(outputId)) {
                    return index;
                }
                index++;
            }
        } catch (Throwable t) {
            DeepseekCompanionClient.LOGGER.warn("[XiaoD] stonecutter index lookup failed", t);
        }
        return -1;
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

    private static int handlerSlot(ScreenHandler handler, net.minecraft.entity.player.PlayerInventory inventory, int index) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == inventory && slot.getIndex() == index) {
                return slot.id;
            }
        }
        return -1;
    }

    private static BlockPos findBlock(MinecraftClient mc, Def def) {
        for (BlockPos pos : BlockPos.iterateOutwards(mc.player.getBlockPos(), 6, 6, 6)) {
            Block block = mc.world.getBlockState(pos).getBlock();
            if (block == def.block()
                    || (def.name().equals("anvil")
                    && (block == Blocks.CHIPPED_ANVIL || block == Blocks.DAMAGED_ANVIL))) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private static void fail(String why) {
        DeepseekCompanionClient.LOGGER.warn("[XiaoD] station failed: {}", why);
        BotBrain.say(why);
        active = false;
        QUEUE.clear();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }
    }

    public static String stationList() {
        return "grindstone/smithing/cartography/stonecutter/loom/anvil/enchanting";
    }
}
