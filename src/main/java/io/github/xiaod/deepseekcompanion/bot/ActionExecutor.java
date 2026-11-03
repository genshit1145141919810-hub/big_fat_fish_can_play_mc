package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.chat.Intent;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import io.github.xiaod.deepseekcompanion.integrations.LitematicaSchematics;
import io.github.xiaod.deepseekcompanion.integrations.Recipes;
import io.github.xiaod.deepseekcompanion.integrations.SeedcrackerX;
import io.github.xiaod.deepseekcompanion.integrations.XaeroWaypoints;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Executes exactly one validated intent. Physical actions go through the
 * whitelisted Baritone bridge; query actions read game state and return an
 * observation for the model to phrase.
 */
public final class ActionExecutor {
    private static String cachedTrees;

    private ActionExecutor() {
    }

    /** {@code observation} is non-null only for query actions. */
    public record Result(boolean ok, String observation) {
        public static Result physical(boolean ok) {
            return new Result(ok, null);
        }

        public static Result observed(String text) {
            return new Result(true, text);
        }
    }

    public static Result execute(MinecraftClient mc, Intent intent) {
        if (intent.pluginAction() != null) {
            return Result.physical(ActionRegistry.run(ActionRegistry.find(intent.pluginAction()), intent.arg()));
        }
        BotAction action = intent.action();
        if (action == null) {
            return Result.physical(false);
        }
        if (action.isQuery()) {
            return Result.observed(query(mc, action, intent.arg()));
        }
        CompanionConfig cfg = CompanionConfig.get();
        return Result.physical(switch (action) {
            case FOLLOW -> {
                PlayerEntity owner = GameSnapshot.findOwner(mc, cfg);
                if (owner == null) {
                    BotBrain.say("你在哪？我找不到你，报个坐标吧");
                    yield false;
                }
                yield BaritoneBridge.followPlayer(owner.getName().getString());
            }
            case COME -> BaritoneBridge.come();
            case STOP -> {
                BotActions.stopAll(mc);
                yield true;
            }
            case MINE -> {
                String id = firstBlockId(intent.arg());
                boolean ok = false;
                boolean nearProtected = HomeZone.protectedTargetNear(mc, id, false, 24) != null;
                if (BaritoneBridge.available() && !nearProtected) {
                    ok = BaritoneBridge.mine(id);
                    if (ok) {
                        TaskTracker.mark("mine", 240_000L);
                        FleeController.remember("mine " + id);
                    }
                }
                if (!ok) {
                    // 本地挖掘会跳过家的保护区，只有它才可能在保护区旁边干活
                    ok = MiningController.start(mc, id, false, 3);
                }
                yield ok;
            }
            case CHOP -> {
                String log = nearestLog(mc);
                boolean ok = false;
                if (!log.isBlank()) {
                    boolean nearProtected = HomeZone.protectedTargetNear(mc, log, true, 24) != null;
                    if (BaritoneBridge.available() && !nearProtected) {
                        ok = BaritoneBridge.mine(log);
                        if (ok) {
                            TaskTracker.mark("chop", 240_000L);
                            FleeController.remember("chop");
                        }
                    }
                    if (!ok) {
                        ok = MiningController.start(mc, null, true, 8);
                    }
                }
                yield ok;
            }
            case GOTO -> {
                String[] xyz = intent.arg().split(" ");
                boolean ok = BaritoneBridge.gotoPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
                if (ok) {
                    TaskTracker.mark("goto", 90_000L);
                }
                yield ok;
            }
            case GIVE -> GiveController.start(mc, intent.arg());
            case HUNT -> CombatController.startHunt(mc, intent.arg());
            case COOK -> ContainerController.cook(mc, intent.arg());
            case CRAFT -> ContainerController.craft(mc, intent.arg());
            case STORE -> StorageController.store(mc, intent.arg());
            case TAKE -> StorageController.take(mc, intent.arg());
            case HOLD -> InteractController.hold(mc, intent.arg());
            case USE -> InteractController.use(mc, firstBlockId(intent.arg()));
            case BREW -> InteractController.brew(mc, intent.arg());
            case STATION -> StationController.start(mc, intent.arg());
            case ENCHANT -> EnchantController.start(mc, intent.arg());
            case ENCHANT_BEST -> EnchantPicker.start(mc, intent.arg());
            case EAT -> BotActions.eat(mc);
            case PORTAL -> PortalController.start(mc, intent.arg());
            case PLACE -> PlacementController.place(mc, intent.arg());
            case ACQUIRE -> AcquireTask.start(mc, intent.arg(), 1);
            case FIGHT -> BotActions.attackNearest(mc, 16);
            default -> false;
        });
    }

    private static String query(MinecraftClient mc, BotAction action, String arg) {
        return switch (action) {
            case RECIPE -> Recipes.crafting(mc, arg);
            case USES -> Recipes.uses(mc, arg);
            case NEARBY -> nearby(mc);
            case INVENTORY -> inventory(mc);
            case STATS -> stats(mc);
            case BIOME -> biome(mc);
            case SCHEMATIC -> LitematicaSchematics.query(mc, arg);
            case STRUCTURES -> SeedcrackerX.report(mc, distance(arg, 200));
            case WAYPOINTS -> waypoints(mc);
            case LOOK -> Perception.look(mc);
            case SCAN -> Perception.scan(mc, arg);
            case DANGER -> Perception.danger(mc);
            case CONTAINERS -> Perception.containers(mc);
            case PATH -> Perception.path(mc);
            case DROPS -> Perception.drops(mc);
            case PLAYERS -> Perception.players(mc);
            case EVENTS -> EventLog.recent(10);
            default -> "";
        };
    }

    private static String waypoints(MinecraftClient mc) {
        List<XaeroWaypoints.Point> points = XaeroWaypoints.list(mc);
        if (points.isEmpty()) {
            return XaeroWaypoints.available()
                    ? "小地图里还没有航点"
                    : "没装 Xaero 小地图，读不到航点";
        }
        StringBuilder sb = new StringBuilder("航点：");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(points.get(i));
        }
        XaeroWaypoints.Point nearest = XaeroWaypoints.nearest(mc, points);
        if (nearest != null) {
            sb.append("（最近的是 ").append(nearest.name()).append("）");
        }
        return sb.toString();
    }

    private static String biome(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            return "还没进世界";
        }
        BlockPos pos = mc.player.getBlockPos();
        return mc.world.getBiome(pos).getKey()
                .map(key -> "当前生物群系：" + key.getValue().getPath())
                .orElse("当前生物群系：未知");
    }

    private static String nearby(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return "还没进世界";
        }
        List<String> items = new ArrayList<>();
        for (PlayerEntity other : mc.world.getPlayers()) {
            if (other != p && other.isAlive()) {
                items.add("玩家 " + other.getName().getString() + "(" + Math.round(Math.sqrt(other.squaredDistanceTo(p))) + "格)");
            }
        }
        List<Entity> monsters = new ArrayList<>(mc.world.getEntitiesByClass(
                net.minecraft.entity.LivingEntity.class,
                p.getBoundingBox().expand(24),
                e -> e instanceof Monster && e.isAlive()));
        monsters.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
        for (int i = 0; i < Math.min(monsters.size(), 6); i++) {
            Entity e = monsters.get(i);
            items.add(e.getName().getString() + "(" + Math.round(Math.sqrt(e.squaredDistanceTo(p))) + "格)");
        }
        return items.isEmpty() ? "附近没什么东西" : "附近：" + String.join("、", items);
    }

    private static String inventory(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) {
            return "还没进世界";
        }
        List<String> items = new ArrayList<>();
        for (ItemStack stack : p.getInventory().getMainStacks()) {
            if (!stack.isEmpty()) {
                items.add(stack.getName().getString() + "x" + stack.getCount());
            }
        }
        return items.isEmpty() ? "背包是空的" : "背包：" + String.join("、", items);
    }

    private static String stats(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) {
            return "还没进世界";
        }
        return "血量 " + Math.round(p.getHealth()) + "/20，饱食度 " + p.getHungerManager().getFoodLevel()
                + "/20，经验等级 " + p.experienceLevel + "，护甲 " + p.getArmor();
    }

    /** "300" -> 300; anything unparsable falls back. */
    public static int distance(String arg, int fallback) {
        if (arg == null || arg.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(arg.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** "iron_ore,coal_ore" -> "iron_ore"; the miner works one type at a time. */
    public static String firstBlockId(String list) {        if (list == null || list.isBlank()) {
            return "iron_ore";
        }
        String first = list.split(",")[0].trim().toLowerCase(java.util.Locale.ROOT);
        return first.startsWith("minecraft:") ? first.substring("minecraft:".length()) : first;
    }

    /**
     * Picks the log type that actually grows around the player. Baritone's
     * {@code mine} takes one block per command, so "chop any tree" means
     * "chop the nearest kind, then re-evaluate".
     */
    public static String nearestLog(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            return "oak_log";
        }
        BlockPos origin = mc.player.getBlockPos();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        int radius = 20;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -8; dy <= 16; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = mc.world.getBlockState(cursor);
                    if (!state.isIn(BlockTags.LOGS)) {
                        continue;
                    }
                    if (HomeZone.isProtected(cursor)) {
                        continue; // 保护区里的树不算
                    }
                    double distance = origin.getSquaredDistance(cursor);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = Registries.BLOCK.getId(state.getBlock()).getPath();
                    }
                }
            }
        }
        return best != null ? best : "";
    }

    public static String treeBlocks(CompanionConfig cfg) {
        if (cachedTrees != null) {
            return cachedTrees;
        }
        try {
            cachedTrees = ActionValidator.validateBlocks(cfg.treeBlocks);
        } catch (ActionValidator.FormatException e) {
            cachedTrees = "oak_log";
        }
        return cachedTrees;
    }

    /**
     * Drops the requested item toward the owner. Hotbar stacks are thrown
     * directly; a main-inventory stack is first swapped into the hotbar with a
     * slot click (syncId 0 is the player's own inventory handler).
     */
    private static boolean give(MinecraftClient mc, String itemId) {
        var p = mc.player;
        if (p == null || mc.interactionManager == null) {
            return false;
        }
        var owner = GameSnapshot.findOwner(mc, CompanionConfig.get());
        if (owner != null) {
            p.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, owner.getEyePos());
        }
        var inventory = p.getInventory();
        int dropped = 0;
        for (int slot = 0; slot < 9 && dropped < 4; slot++) {
            if (matchesItem(inventory.getStack(slot), itemId)) {
                inventory.setSelectedSlot(slot);
                p.dropSelectedItem(true);
                dropped++;
            }
        }
        if (dropped > 0) {
            return true;
        }
        for (int slot = 9; slot < 36; slot++) {
            if (!matchesItem(inventory.getStack(slot), itemId)) {
                continue;
            }
            int hotbar = freeHotbarSlot(inventory);
            if (hotbar < 0) {
                BotBrain.say("快捷栏满了，腾个位置给我");
                return false;
            }
            try {
                mc.interactionManager.clickSlot(0, slot, hotbar,
                        net.minecraft.screen.slot.SlotActionType.SWAP, p);
            } catch (Throwable t) {
                DeepseekCompanionClient.LOGGER.warn("[XiaoD] hotbar swap failed", t);
                return false;
            }
            inventory.setSelectedSlot(hotbar);
            p.dropSelectedItem(true);
            return true;
        }
        BotBrain.say("我身上没有 " + itemId);
        return false;
    }

    private static boolean matchesItem(ItemStack stack, String itemId) {
        return !stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getPath().equals(itemId);
    }

    private static int freeHotbarSlot(net.minecraft.entity.player.PlayerInventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getStack(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }
}

