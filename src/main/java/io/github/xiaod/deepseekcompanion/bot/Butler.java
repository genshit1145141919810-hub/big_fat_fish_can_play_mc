package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 常驻杂役：优先级固定的琐事循环，让上层任务可以假设"我活着、手上是趁手的东西、脚边没漏东西"。
 *
 * <p>排在它前面的永远是保命（逃跑/战斗），它自己只做三件事：</p>
 * <ol>
 *   <li><b>捡东西</b>：6 格内掉落物 → 走过去（不打断任何进行中的任务）</li>
 *   <li><b>卡住自救</b>：有任务但 4 秒没位移 → 跳一下 + 侧向挪一步</li>
 *   <li><b>清垃圾</b>：背包满且手上有明显没用的东西（腐肉/种子过量）→ 丢掉一部分</li>
 * </ol>
 *
 * <p>不做吃/穿甲/换武器：那三件已经由 BotActions / ArmorController / CombatController
 * 各自负责，重复做只会互相打断。</p>
 */
public final class Butler {
    private static final long PICKUP_COOLDOWN_MS = 3000L;
    private static final long STUCK_WINDOW_MS = 4000L;
    private static final Set<String> JUNK = Set.of(
            "rotten_flesh", "poisonous_potato", "spider_eye", "wheat_seeds", "beetroot_seeds",
            "pumpkin_seeds", "melon_seeds", "flint", "gravel");

    private static long lastPickup;
    private static long lastMoveCheck;
    private static double lastX;
    private static double lastZ;
    private static int stuckStrikes;

    private Butler() {
    }

    public static void tick(MinecraftClient mc, CompanionConfig cfg) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!cfg.enableButler) {
            return;
        }
        // 保命优先：逃跑/战斗/垒墙时杂役不插嘴
        if (FleeController.isFleeing() || BlockInController.isBusy() || CombatController.isRetreating()) {
            return;
        }
        pickup(mc, p, now);
        unstuck(mc, p, now);
        dropJunk(mc, p, now);
    }

    private static void pickup(MinecraftClient mc, ClientPlayerEntity p, long now) {
        if (PlanRunner.isPlanning() || MiningController.isBusy() || ContainerController.isBusy()) {
            return; // 正在干正事，别为了捡东西打断
        }
        if (now - lastPickup < PICKUP_COOLDOWN_MS) {
            return;
        }
        List<ItemEntity> drops = mc.world.getEntitiesByClass(ItemEntity.class,
                p.getBoundingBox().expand(6.0), e -> !e.isRemoved() && !e.getStack().isEmpty());
        if (drops.isEmpty()) {
            return;
        }
        drops.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)));
        ItemEntity nearest = drops.get(0);
        if (nearest.squaredDistanceTo(p) < 2.25) {
            return; // 走过去自然就捡到了
        }
        lastPickup = now;
        if (BaritoneBridge.available()) {
            BaritoneBridge.gotoPos(nearest.getBlockPos().getX(), nearest.getBlockPos().getY(),
                    nearest.getBlockPos().getZ());
            Perception.note("去捡 " + nearest.getStack().getName().getString());
        }
    }

    private static void unstuck(MinecraftClient mc, ClientPlayerEntity p, long now) {
        boolean busy = PlanRunner.isPlanning() || MiningController.isBusy() || TaskTracker.isBusy();
        if (!busy) {
            stuckStrikes = 0;
            lastX = p.getX();
            lastZ = p.getZ();
            return;
        }
        if (now - lastMoveCheck < STUCK_WINDOW_MS) {
            return;
        }
        double moved = Math.hypot(p.getX() - lastX, p.getZ() - lastZ);
        lastX = p.getX();
        lastZ = p.getZ();
        lastMoveCheck = now;
        if (moved > 0.5) {
            stuckStrikes = 0;
            return;
        }
        stuckStrikes++;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] butler: stuck? strikes={} moved={}",
                stuckStrikes, Math.round(moved * 100) / 100.0);
        if (stuckStrikes >= 2) {
            stuckStrikes = 0;
            Perception.note("卡住了，停一下重新找路");
            TaskTracker.clear();
            MiningController.cancel(mc);
            BaritoneBridge.stop();
            return;
        }
        // 先尝试跳一下，很多"卡住"只是被台阶挡住
        if (p.isOnGround()) {
            p.setVelocity(p.getVelocity().x, 0.42, p.getVelocity().z);
        } else {
            p.setVelocity(p.getVelocity().x + 0.12, p.getVelocity().y, p.getVelocity().z + 0.12);
        }
    }

    private static void dropJunk(MinecraftClient mc, ClientPlayerEntity p, long now) {
        int empty = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (p.getInventory().getStack(slot).isEmpty()) {
                empty++;
            }
        }
        if (empty > 4) {
            return; // 还有空位，不用清
        }
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = p.getInventory().getStack(slot);
            if (stack.isEmpty() || stack.getCount() < 8) {
                continue;
            }
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (!JUNK.contains(id)) {
                continue;
            }
            mc.interactionManager.clickSlot(0, slot, 1, net.minecraft.screen.slot.SlotActionType.THROW, p);
            Perception.note("扔掉了没用的 " + stack.getName().getString());
            return;
        }
    }
}
