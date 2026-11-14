package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import io.github.xiaod.deepseekcompanion.integrations.ModLinks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.Comparator;
import java.util.List;

/** Turns the live client state into a compact line the model can reason about. */
public final class GameSnapshot {
    private GameSnapshot() {
    }

    public static String build(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld world = mc.world;
        if (p == null || world == null) {
            return "（还没进世界）";
        }
        CompanionConfig cfg = CompanionConfig.get();
        StringBuilder sb = new StringBuilder();
        sb.append("坐标 ").append(Math.round(p.getX())).append(' ').append(Math.round(p.getY())).append(' ').append(Math.round(p.getZ()));
        sb.append(" | 维度 ").append(world.getRegistryKey().getValue().getPath());
        sb.append(" | 血量 ").append(Math.round(p.getHealth())).append("/20");
        sb.append(" | 饱食度 ").append(p.getHungerManager().getFoodLevel()).append("/20");
        long dayTime = world.getTimeOfDay() % 24000L;
        sb.append(" | 时间 ").append(dayTime < 13000 ? "白天" : "夜晚");

        List<LivingEntity> monsters = world.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(16),
                e -> e instanceof Monster && e.isAlive());
        sb.append(" | 附近怪物 ").append(monsters.size()).append(" 只");
        if (!monsters.isEmpty()) {
            LivingEntity nearest = monsters.stream()
                    .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)))
                    .orElse(null);
            if (nearest != null) {
                sb.append("(最近距离 ").append(Math.round(Math.sqrt(nearest.squaredDistanceTo(p)))).append(" 格)");
            }
        }

        PlayerEntity owner = findOwner(mc, cfg);
        if (owner != null) {
            sb.append(" | 主人 ").append(owner.getName().getString())
                    .append(" 距离 ").append(Math.round(Math.sqrt(owner.squaredDistanceTo(p)))).append(" 格");
        } else {
            sb.append(" | 附近没有主人");
        }

        sb.append(" | 背包 ").append(inventorySummary(p));
        sb.append(" | 主手持物 ").append(p.getMainHandStack().isEmpty() ? "空手" : p.getMainHandStack().getName().getString());
        // 手上有没有活：模型得知道自己（或上一句话）正在做什么，别在计划跑到一半时又塞动作
        String plan = PlanRunner.status();
        String acquire = AcquireTask.status();
        if ((plan != null && !plan.isBlank()) || (acquire != null && !acquire.isBlank())) {
            sb.append(" | 正在做 ");
            if (plan != null && !plan.isBlank()) {
                sb.append(shorten(plan, 60));
            }
            if (acquire != null && !acquire.isBlank()) {
                if (plan != null && !plan.isBlank()) {
                    sb.append("；");
                }
                sb.append(shorten(acquire, 60));
            }
        }
        sb.append(" | ").append(HomeZone.describe());
        if (HomeZone.protectedHere()) {
            sb.append("(你现在就在保护区里)");
        }
        if (HomeZone.recentlyBlocked()) {
            sb.append(" | 提醒: 刚在家保护区想挖方块被拦下了，别在保护区内挖");
        }
        int protocol = ViaBridge.protocol(mc);
        if (protocol > 0) {
            sb.append(" | 服务器协议 ").append(ViaBridge.describe(protocol));
        }
        sb.append(" | 记忆 ").append(Memory.summary());
        String recentEvents = EventLog.summary();
        if (!recentEvents.isBlank()) {
            sb.append(" | 刚刚：").append(recentEvents);
        }
        sb.append(Perception.snapshotSuffix(mc));
        String env = ModLinks.summary();
        if (!env.isBlank()) {
            sb.append(" | 环境 ").append(env);
        }
        return sb.toString();
    }

    public static PlayerEntity findOwner(MinecraftClient mc, CompanionConfig cfg) {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        String name = cfg.ownerName == null ? "" : cfg.ownerName.strip();
        if (name.isEmpty()) {
            // Never guess: without an explicit owner the bot does not chase anyone.
            return null;
        }
        for (PlayerEntity other : mc.world.getPlayers()) {
            if (other != mc.player && other.getName().getString().equalsIgnoreCase(name)) {
                return other;
            }
        }
        return null;
    }

    private static String inventorySummary(ClientPlayerEntity p) {
        int stacks = 0;
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : p.getInventory().getMainStacks()) {
            if (!stack.isEmpty()) {
                stacks++;
                if (sb.length() < 90) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(stack.getName().getString()).append('x').append(stack.getCount());
                }
            }
        }
        if (!p.getOffHandStack().isEmpty()) {
            stacks++;
        }
        if (stacks == 0) {
            return "空";
        }
        return stacks + " 种(" + sb + ")";
    }

    /** Items the bot dropped nearby, useful for "go pick that up". */
    public static long nearbyItemCount(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            return 0;
        }
        return mc.world.getEntitiesByClass(ItemEntity.class, mc.player.getBoundingBox().expand(12), e -> e.isAlive()).size();
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}

