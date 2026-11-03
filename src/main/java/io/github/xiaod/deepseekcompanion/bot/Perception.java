package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * 感知层：把"我在哪、我看得见什么、谁在威胁我、我卡住了没有"变成模型能读的短句。
 *
 * <p>设计取舍：不做程序化动作（脚本），而是把<b>感知做厚</b>。这样线性循环每次重新规划
 * 都能拿到新鲜状态，"看不到情况"不再是失败的原因，token 也远比一次调用跑几十步便宜。</p>
 */
public final class Perception {
    private static final int EVENT_KEEP = 6;
    private static final Deque<String> EVENTS = new ArrayDeque<>();

    private Perception() {
    }

    /** 记录一条近期事件（伤害、失败、捡到东西…），会进入给模型的快照。 */
    public static void note(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        EVENTS.addLast(text.strip());
        while (EVENTS.size() > EVENT_KEEP) {
            EVENTS.removeFirst();
        }
    }

    public static String eventsTail() {
        return EVENTS.isEmpty() ? "" : String.join("；", EVENTS);
    }

    // ------------------------------------------------------------- snapshot

    /** 追加到状态行末尾的感知信息：光照、附近威胁概览、最近事件。 */
    public static String snapshotSuffix(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // 光照决定会不会刷怪，直接影响"能不能在这儿待着"
        int light = mc.world.getLightLevel(p.getBlockPos());
        sb.append(" | 光照 ").append(light);
        // 只有真危险才写，避免每次都塞一堆
        List<LivingEntity> threats = monsters(mc, 12.0);
        if (!threats.isEmpty()) {
            LivingEntity nearest = threats.get(0);
            sb.append('(').append(threats.size()).append("只，最近 ")
                    .append(nearest.getName().getString()).append(' ')
                    .append(Math.round(Math.sqrt(nearest.squaredDistanceTo(p)))).append("格)");
        }
        String events = eventsTail();
        if (!events.isBlank()) {
            sb.append(" | 最近：").append(events);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- queries

    /** 我正对着什么（方块或生物）。 */
    public static String look(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return "还没进世界";
        }
        Vec3d start = p.getEyePos();
        Vec3d end = start.add(p.getRotationVec(1.0f).multiply(24.0));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(start, end,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            BlockState state = mc.world.getBlockState(pos);
            String id = Registries.BLOCK.getId(state.getBlock()).getPath();
            return "看着 " + id + "（" + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                    + "，面 " + hit.getSide().asString() + "）";
        }
        // 没打到方块就看实体
        Entity nearest = null;
        double best = 24.0 * 24.0;
        for (Entity entity : mc.world.getEntities()) {
            if (entity == p || !entity.isAlive()) {
                continue;
            }
            Vec3d to = entity.getEntityPos().add(0, entity.getHeight() / 2, 0).subtract(start);
            double along = to.normalize().dotProduct(p.getRotationVec(1.0f));
            double dist = p.squaredDistanceTo(entity);
            if (along > 0.95 && dist < best) {
                best = dist;
                nearest = entity;
            }
        }
        if (nearest != null) {
            String type = Registries.ENTITY_TYPE.getId(nearest.getType()).getPath();
            String health = nearest instanceof LivingEntity living
                    ? "，血 " + Math.round(living.getHealth()) + "/" + Math.round(living.getMaxHealth()) : "";
            return "看着 " + type + "（" + Math.round(Math.sqrt(best)) + " 格" + health + "）";
        }
        return "盯着空气（前方 24 格没有方块和生物）";
    }

    /** 附近某种方块有多少、最近的在哪个坐标。 */
    public static String scan(MinecraftClient mc, String blockId) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return "还没进世界";
        }
        String id = blockId == null ? "" : blockId.strip().toLowerCase(java.util.Locale.ROOT);
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        if (id.isBlank()) {
            return "要说扫什么方块";
        }
        BlockPos origin = p.getBlockPos();
        int count = 0;
        BlockPos nearest = null;
        double bestDist = Double.MAX_VALUE;
        int steps = 0;
        for (BlockPos pos : BlockPos.iterateOutwards(origin, 16, 12, 16)) {
            if (++steps > 20000) {
                break;
            }
            BlockState state = mc.world.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (!Registries.BLOCK.getId(state.getBlock()).getPath().equals(id)) {
                continue;
            }
            count++;
            double distance = origin.getSquaredDistance(pos);
            if (distance < bestDist) {
                bestDist = distance;
                nearest = pos.toImmutable();
            }
        }
        if (nearest == null) {
            return "16 格内没有 " + id;
        }
        return id + "：" + count + " 处，最近 (" + nearest.getX() + " " + nearest.getY() + " "
                + nearest.getZ() + ") 距离 " + Math.round(Math.sqrt(bestDist)) + " 格";
    }

    /** 附近威胁清单：类型、距离、血量；苦力怕单独标引信。 */
    public static String danger(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return "还没进世界";
        }
        List<LivingEntity> threats = monsters(mc, 16.0);
        if (threats.isEmpty()) {
            return "16 格内没有敌对生物";
        }
        StringBuilder sb = new StringBuilder("威胁 ").append(threats.size()).append(" 只：");
        int shown = 0;
        for (LivingEntity mob : threats) {
            if (shown++ >= 5) {
                sb.append("…");
                break;
            }
            String type = Registries.ENTITY_TYPE.getId(mob.getType()).getPath();
            sb.append("\n- ").append(type)
                    .append(" 距离 ").append(Math.round(Math.sqrt(mob.squaredDistanceTo(p))))
                    .append(" 格 血 ").append(Math.round(mob.getHealth()));
            if (type.contains("creeper") && mob instanceof net.minecraft.entity.mob.CreeperEntity creeper
                    && creeper.getFuseSpeed() > 0) {
                sb.append(" §c引信已点燃");
            }
        }
        return sb.toString();
    }

    /** 附近可以交互的容器/站点（存东西、做饭、合成前先知道有什么）。 */
    public static String containers(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return "还没进世界";
        }
        BlockPos origin = p.getBlockPos();
        List<String> found = new ArrayList<>();
        int steps = 0;
        for (BlockPos pos : BlockPos.iterateOutwards(origin, 12, 8, 12)) {
            if (++steps > 12000 || found.size() >= 8) {
                break;
            }
            BlockState state = mc.world.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            String id = Registries.BLOCK.getId(state.getBlock()).getPath();
            if (!isContainer(id)) {
                continue;
            }
            found.add(id + "(" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " "
                    + Math.round(Math.sqrt(origin.getSquaredDistance(pos))) + "格)");
        }
        if (found.isEmpty()) {
            return "12 格内没有容器或站点";
        }
        return "附近：" + String.join("、", found);
    }

    /** 我现在在干嘛、是不是卡住了。 */
    public static String path(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) {
            return "还没进世界";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("任务 ").append(TaskTracker.isBusy() ? TaskTracker.type() : "无");
        sb.append(" | 计划 ").append(PlanRunner.isPlanning() ? PlanRunner.status() : "无");
        sb.append(" | 挖掘 ").append(MiningController.isBusy() ? "进行中" : "空闲");
        if (AcquireTask.isBusy()) {
            sb.append(" | ").append(AcquireTask.status());
        }
        sb.append(" | Baritone ").append(BaritoneBridge.available() ? "可用" : "未装");
        if (BlockInController.isBusy()) {
            sb.append(" | §e正在垒墙");
        }
        if (FleeController.isFleeing()) {
            sb.append(" | §e正在躲避");
        }
        sb.append(" | 坐标 ").append(p.getBlockPos().getX()).append(' ')
                .append(p.getBlockPos().getY()).append(' ').append(p.getBlockPos().getZ());
        return sb.toString();
    }

    /** 掉落物：刚挖的东西掉在脚边没捡，会直接影响"有没有材料"的判断。 */
    public static String drops(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return "还没进世界";
        }
        List<ItemEntity> items = mc.world.getEntitiesByClass(ItemEntity.class,
                p.getBoundingBox().expand(8.0), e -> !e.isRemoved() && !e.getStack().isEmpty());
        if (items.isEmpty()) {
            return "周围没有掉落物";
        }
        items.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
        StringBuilder sb = new StringBuilder("掉落物 ").append(items.size()).append(" 个：");
        int shown = 0;
        for (ItemEntity item : items) {
            if (shown++ >= 5) {
                sb.append("…");
                break;
            }
            ItemStack stack = item.getStack();
            sb.append("\n- ").append(stack.getName().getString()).append('x').append(stack.getCount())
                    .append(" ").append(Math.round(Math.sqrt(item.squaredDistanceTo(p)))).append("格");
        }
        return sb.toString();
    }

    /** 附近玩家（判断主人回来了没、有没有别人在看着）。 */
    public static String players(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return "还没进世界";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == p) {
                continue;
            }
            count++;
            if (count > 5) {
                continue;
            }
            sb.append("\n- ").append(player.getName().getString())
                    .append(' ').append(Math.round(Math.sqrt(player.squaredDistanceTo(p)))).append("格")
                    .append(player.getMainHandStack().isEmpty() ? " 空手"
                            : " 拿 " + player.getMainHandStack().getName().getString());
        }
        return count == 0 ? "附近没有其他玩家" : "附近玩家 " + count + " 位：" + sb;
    }

    // --------------------------------------------------------------- utils

    private static List<LivingEntity> monsters(MinecraftClient mc, double radius) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return List.of();
        }
        List<LivingEntity> list = mc.world.getEntitiesByClass(LivingEntity.class,
                p.getBoundingBox().expand(radius), e -> e instanceof Monster && e.isAlive());
        list.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
        return list;
    }

    private static boolean isContainer(String id) {
        return switch (id) {
            case "chest", "trapped_chest", "barrel", "shulker_box", "furnace", "blast_furnace", "smoker",
                    "crafting_table", "anvil", "smithing_table", "grindstone", "loom", "cartography_table",
                    "stonecutter", "brewing_stand", "enchanting_table", "campfire", "soul_campfire",
                    "ender_chest" -> true;
            default -> id.endsWith("_shulker_box") || id.endsWith("_chest");
        };
    }
}
