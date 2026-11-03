package io.github.xiaod.deepseekcompanion.bot;

import io.github.xiaod.deepseekcompanion.DeepseekCompanionClient;
import io.github.xiaod.deepseekcompanion.config.CompanionConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/**
 * Melee combat: pick the hardest-hitting hotbar item, jump for a real critical
 * hit, retreat and eat when hurt, and let Baritone do the walking.
 */
public final class CombatController {
    private static final Set<String> CHASEABLE = Set.of(
            "zombie", "husk", "drowned", "zombie_villager", "skeleton", "stray", "spider", "cave_spider", "slime");
    private static final double MELEE_REACH = 3.2;
    /** 比这更近就先退：怪物的近战够到约 2 格，站在 2.4~3.2 才能单方面输出。 */
    private static final double KEEP_MIN = 2.4;
    private static final double CHASE_RANGE = 12.0;
    private static final long CHASE_COOLDOWN_MS = 5000L;
    private static final long RETREAT_GOTO_MS = 2500L;
    private static final long JUMP_COOLDOWN_MS = 450L;
    private static final long WEAPON_SCAN_MS = 1000L;
    private static final long EAT_COOLDOWN_MS = 2500L;
    private static final long RETREAT_MS = 6000L;
    private static final long ENGAGED_HOLD_MS = 6000L;

    private static final Set<String> FOOD_ANIMALS = Set.of("cow", "pig", "sheep", "chicken", "rabbit", "mooshroom");
    private static final double THREAT_RANGE = 10.0;
    /**
     * Vanilla movement-speed attribute above which a sprinting player cannot get
     * away (endermen/spiders ~0.30, zombies 0.23). Those fights are to the death.
     */
    private static final double UNOUTRUNNABLE_SPEED = 0.29;

    private static long lastChase;
    private static long lastBaritoneStop;
    private static long lastRetreatGoto;
    private static long lastJump;
    private static long lastWeaponScan;
    private static long lastEat;
    private static long lastLegacySwing;
    private static long retreatUntil;
    private static long engagedUntil;
    private static long huntUntil;
    private static String huntType = "";
    private static LivingEntity huntTarget;

    /** 平滑瞄准用：上一个 tick 的朝向，以及"够不够正"（学 KillAura 的 OnHit 转向）。 */
    private static float aimYaw;
    private static float aimPitch;
    private static boolean aimInitialized;
    private static final float AIM_STEP_DEG = 22.0f;
    /** 换武器前手上是第几格：打完还回去（学 KillAura 的 swapBack）。 */
    private static int preCombatSlot = -1;
    /** 上次打日志时的武器格，避免每秒刷同一行。 */
    private static int lastWeaponLogSlot = -1;

    private CombatController() {
    }

    /** Starts a hunting trip for food. Ends on the first kill or after 90 seconds. */
    public static boolean startHunt(MinecraftClient mc, String type) {
        if (mc.player == null || mc.world == null) {
            return false;
        }
        huntType = type == null ? "" : type;
        huntTarget = null;
        huntUntil = System.currentTimeMillis() + 90_000L;
        return true;
    }

    public static boolean isHunting() {
        return System.currentTimeMillis() < huntUntil;
    }

    /** True for a few seconds after a hit or a chase, so auto-follow does not yank the bot. */
    public static boolean isEngaged(long now) {
        return now < engagedUntil;
    }

    public static boolean isRetreating() {        return System.currentTimeMillis() < retreatUntil;
    }

    public static void tick(MinecraftClient mc, CompanionConfig cfg, long now) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return;
        }
        LivingEntity hostile = pickHostile(mc, CHASE_RANGE);

        // mining priority: avoid monsters instead of fighting them - unless the mob
        // is faster than us, in which case running is pointless
        if (TaskTracker.isBusy() || MiningController.isBusy() || FleeController.isFleeing()) {
            LivingEntity threat = BotActions.nearestHostile(mc, THREAT_RANGE);
            if (threat == null) {
                return;
            }
            if (canOutrun(threat)) {
                FleeController.start(mc, threat, cfg);
                return;
            }
            TaskTracker.clear();
            MiningController.cancel(mc);
            FleeController.clear();
        }

        // hunting trip: only interrupted by a monster that is already on us
        if (isHunting()) {
            if (hostile != null && hostile.squaredDistanceTo(p) < 36.0) {
                endHunt(false);
            } else {
                huntStep(mc, cfg, p, now);
                return;
            }
        }

        // 1) hurt enough to disengage: run away, then feed ourselves - but only if the
        //    mob can actually be outrun; otherwise this is a fight to the death
        // 血量见底才算危险；被围不算（普通玩家打十个僵尸也不难）
        boolean desperate = p.getHealth() <= Math.min(2.0f, cfg.retreatHealth);
        // 苦力怕贴身：这时候躲开不是临阵脱逃，是常识
        boolean creeperClose = hostile != null
                && hostile.squaredDistanceTo(p) < 36.0
                && Registries.ENTITY_TYPE.getId(hostile.getType()).getPath().contains("creeper");
        if (cfg.retreatHealth > 0 && (desperate || creeperClose)
                && hostile != null && hostile.squaredDistanceTo(p) < 100.0
                && canOutrun(hostile)) {
            if (now > retreatUntil) {
                retreatUntil = now + RETREAT_MS;
            }
            if (BlockInController.isBusy()) {
                return; // 正在垒墙，别切到逃跑
            }
            if (BlockInController.recentlyWalled()) {
                return; // 刚垒好墙，先躲在后面回血，别再往外跑
            }
            if (!BlockInController.isBusy() && BlockInController.start(mc, hostile)) {
                return; // 有方块就垒墙，比背身跑更有用
            }
            retreat(mc, p, hostile, now);
            if (now - lastEat > EAT_COOLDOWN_MS) {
                if (BotActions.eat(mc, true)) {
                    lastEat = now;
                }
            }
            return;
        }

        // 2) 进入攻击距离：控距打法——贴脸先退、够得着才打，别站着挨揍
        if (hostile != null) {
            double gap = Math.sqrt(hostile.squaredDistanceTo(p));
            if (gap <= MELEE_REACH * MELEE_REACH) {
                if (gap < KEEP_MIN) {
                    driftBack(p, hostile, now); // 边退边打：只退一点点，攻击照常
                }
                equipBestWeapon(mc, cfg, now, hostile.isFireImmune());
                // fighting to the death still means eating to keep regenerating
                boolean wounded = cfg.retreatHealth > 0 && p.getHealth() <= cfg.retreatHealth;
                if ((wounded || p.getHungerManager().getFoodLevel() <= 10)
                        && now - lastEat > (wounded ? 4000L : 12000L)) {
                    if (BotActions.eat(mc, true)) {
                        lastEat = now;
                        return; // 吃优先：这一口吃完再打
                    }
                }
                attack(mc, p, hostile, cfg, now);
                return;
            }
        }

        // 3) out of reach: let Baritone walk us in, unless following wins right now
        if (PriorityMode.isFollow()) {
            return;
        }
        if (hostile == null) {
            restoreWeapon(mc); // 附近没怪了：把打架时换出来的武器收回去
        }
        if (hostile != null && BaritoneBridge.available() && !TaskTracker.isBusy()
                && hostile.squaredDistanceTo(p) > 25.0
                && now - lastChase > CHASE_COOLDOWN_MS && !MiningController.isBusy()) {
            String type = Registries.ENTITY_TYPE.getId(hostile.getType()).getPath();
            if (CHASEABLE.contains(type) && BaritoneBridge.followEntity(type)) {
                lastChase = now;
                engagedUntil = now + ENGAGED_HOLD_MS;
            }
        }
    }

    /**
     * 学 KillAura 的 SortPriority：不只看远近，还把威胁、血量、视线一起算进去。
     * 但只用来挑怪，不碰玩家。
     */
    private static LivingEntity pickHostile(MinecraftClient mc, double range) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return null;
        }
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (var entity : mc.world.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(range),
                e -> e != p && e.isAlive() && !e.isRemoved() && e instanceof net.minecraft.entity.mob.Monster)) {
            double score = Math.sqrt(entity.squaredDistanceTo(p));
            String type = Registries.ENTITY_TYPE.getId(entity.getType()).getPath();
            if (type.contains("creeper")) {
                score -= 6.0; // 爆炸源优先拆，别等它贴脸
            } else if (type.contains("skeleton") || type.contains("blaze") || type.contains("witch")) {
                score -= 3.0; // 远程先解决
            }
            if (entity.getHealth() <= 6.0f) {
                score -= 2.0; // 残血收掉，少一个敌人
            }
            if (!p.canSee(entity)) {
                score += 4.0; // 隔墙的先垫后，别对着空气挥
            }
            if (entity instanceof net.minecraft.entity.mob.ZombieEntity zombie && zombie.isBaby()) {
                score += 2.0; // 小僵尸跑得快，不是最优先
            }
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    /** 平滑转向：每 tick 最多转 AIM_STEP_DEG，角度还差得多就先转不挥（真人手也是这样）。 */
    private static boolean aimAt(ClientPlayerEntity p, LivingEntity target) {
        var eyes = target.getEyePos();
        double dx = eyes.x - p.getX();
        double dy = eyes.y - p.getEyeY();
        double dz = eyes.z - p.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        if (!aimInitialized) {
            aimYaw = p.getYaw();
            aimPitch = p.getPitch();
            aimInitialized = true;
        }
        float dYaw = wrapDegrees(wantYaw - aimYaw);
        float dPitch = wantPitch - aimPitch;
        float stepYaw = Math.max(-AIM_STEP_DEG, Math.min(AIM_STEP_DEG, dYaw));
        float stepPitch = Math.max(-AIM_STEP_DEG, Math.min(AIM_STEP_DEG, dPitch));
        aimYaw += stepYaw;
        aimPitch += stepPitch;
        p.setYaw(aimYaw);
        p.setPitch(aimPitch);
        return Math.abs(wrapDegrees(wantYaw - aimYaw)) < 12.0f && Math.abs(wantPitch - aimPitch) < 12.0f;
    }

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360.0f;
        if (d >= 180.0f) {
            d -= 360.0f;
        }
        if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }

    /** 打完了把手上的武器换回原来那格（只在真的为打架换过时才换回去）。 */
    private static void restoreWeapon(MinecraftClient mc) {
        if (preCombatSlot < 0 || mc.player == null) {
            return;
        }
        int slot = preCombatSlot;
        preCombatSlot = -1;
        if (slot != mc.player.getInventory().getSelectedSlot()) {
            mc.player.getInventory().setSelectedSlot(slot);
        }
    }

    private static void attack(MinecraftClient mc, ClientPlayerEntity p, LivingEntity target,
                               CompanionConfig cfg, long now) {
        // 吃优先，打靠后：嘴里有东西或者刚点开食物，这一 tick 就不挥了（挥了会打断进食）
        if (BotActions.isEating() || p.isUsingItem()) {
            return;
        }
        // 隔着墙不打：既浪费耐久，也像外挂
        if (!p.canSee(target)) {
            return;
        }
        boolean aimed = aimAt(p, target);
        boolean legacyServer = ViaBridge.isLegacyCombat(ViaBridge.protocol(mc));
        if (!legacyServer && p.getAttackCooldownProgress(0.5f) < 0.9f) {
            return;
        }
        if (!aimed) {
            return; // 先把手转到位，下一 tick 再打
        }
        if (legacyServer && now - lastLegacySwing < 100L) {
            return; // 1.8 servers have no cooldown; just do not spam every tick
        }
        if (legacyServer) {
            lastLegacySwing = now;
        }
        double distance = Math.sqrt(target.squaredDistanceTo(p));
        // point blank: a jump would only give the mob a free hit, so just swing
        boolean tooCloseToJump = distance < cfg.critMinDistance;
        if (!cfg.critEnabled || tooCloseToJump || p.isTouchingWater() || p.isClimbing()) {
            hit(mc, p, target);
            return;
        }
        if (p.isOnGround()) {
            // hop first; the hit lands on the way down, which is what vanilla
            // treats as a critical hit (fallDistance > 0, airborne, not sprinting)
            if (now - lastJump > JUMP_COOLDOWN_MS && !p.isSprinting()) {
                p.setVelocity(p.getVelocity().x, 0.42, p.getVelocity().z);
                lastJump = now;
            }
            return;
        }
        if (p.getVelocity().y < -0.05 && p.fallDistance > 0) {
            hit(mc, p, target);
        }
    }

    /**
     * 控距：贴太近就往后撤一小步，顺便停掉 Baritone 的"贴脸追人"。
     * 僵尸近战够到约 2 格，我们兵器够到 3 格——保持 2.4~3.0 就能单方面输出。
     */

    /** 8 格内还活着的怪物数量：被围才值得跑，单只用控距打。 */
    private static int countThreats(MinecraftClient mc, double radius) {
        if (mc.player == null || mc.world == null) {
            return 0;
        }
        return mc.world.getEntitiesByClass(LivingEntity.class, mc.player.getBoundingBox().expand(radius),
                e -> e instanceof net.minecraft.entity.mob.Monster && e.isAlive()).size();
    }

    /** 小幅后退，为了在攻击冷却期间脱离怪的近战距离；不影响本 tick 出手。 */
    private static void driftBack(ClientPlayerEntity p, LivingEntity target, long now) {
        if (now - lastBaritoneStop > 2500L && TaskTracker.isBusy()) {
            BaritoneBridge.stop();
            TaskTracker.clear();
            lastBaritoneStop = now;
        }
        double dx = p.getX() - target.getX();
        double dz = p.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) {
            return;
        }
        double speed = 0.16;
        p.setVelocity(dx / len * speed, p.getVelocity().y, dz / len * speed);
    }

    private static void hit(MinecraftClient mc, ClientPlayerEntity p, LivingEntity target) {        if (mc.interactionManager == null) {
            return;
        }
        mc.interactionManager.attackEntity(p, target);
        p.swingHand(Hand.MAIN_HAND);
        engagedUntil = System.currentTimeMillis() + ENGAGED_HOLD_MS;
        DeepseekCompanionClient.LOGGER.info("[XiaoD] hit {} crit={}", target.getName().getString(),
                p.getVelocity().y < 0);
    }

    private static void endHunt(boolean success) {
        huntUntil = 0L;
        huntTarget = null;
        huntType = "";
        if (success) {
            BotBrain.say("搞到肉了，饿了就吃");
        }
    }

    private static void huntStep(MinecraftClient mc, CompanionConfig cfg, ClientPlayerEntity p, long now) {
        if (huntTarget != null && (!huntTarget.isAlive() || huntTarget.isRemoved())) {
            endHunt(true);
            return;
        }
        if (huntTarget == null) {
            huntTarget = nearestFoodAnimal(mc, 24.0);
            if (huntTarget == null) {
                endHunt(false);
                return;
            }
        }
        double distance = Math.sqrt(huntTarget.squaredDistanceTo(p));
        if (BotActions.isEating() || p.isUsingItem()) {
            return; // 嘴里有东西：先吃完
        }
        if (distance <= MELEE_REACH) {
            equipBestWeapon(mc, cfg, now, huntTarget.isFireImmune());
            attack(mc, p, huntTarget, cfg, now);
            return;
        }
        if (now - lastChase > CHASE_COOLDOWN_MS && BaritoneBridge.available()) {
            String type = Registries.ENTITY_TYPE.getId(huntTarget.getType()).getPath();
            if (BaritoneBridge.followEntity(type)) {
                lastChase = now;
            }
        }
    }

    private static LivingEntity nearestFoodAnimal(MinecraftClient mc, double range) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return null;
        }
        return mc.world.getEntitiesByClass(net.minecraft.entity.passive.AnimalEntity.class,
                        p.getBoundingBox().expand(range), e -> {
                            if (!e.isAlive()) {
                                return false;
                            }
                            String id = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
                            return huntType.isEmpty() ? FOOD_ANIMALS.contains(id) : id.equals(huntType);
                        })
                .stream()
                .min(java.util.Comparator.comparingDouble(e -> e.squaredDistanceTo(p)))
                .orElse(null);
    }

    /** Swaps to whichever hotbar stack has the highest damage-per-second. */
    private static void equipBestWeapon(MinecraftClient mc, CompanionConfig cfg, long now, boolean fireImmuneTarget) {
        if (!cfg.switchWeapon || now - lastWeaponScan < WEAPON_SCAN_MS) {
            return;
        }
        lastWeaponScan = now;
        PlayerInventory inventory = mc.player.getInventory();
        int best = -1;
        double bestDps = 0.0;
        // 学 KillAura：整个背包都看，不只快捷栏
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            double dps = dps(stack, fireImmuneTarget);
            if (dps > bestDps) {
                bestDps = dps;
                best = slot;
            }
        }
        if (best < 0) {
            return;
        }
        if (best >= 9) {
            // 不在快捷栏：换到当前手持格（等于按数字键交换），并记下原来那格
            int hotbar = inventory.getSelectedSlot();
            int handlerSlot = -1;
            var handler = mc.player.currentScreenHandler;
            for (var slot : handler.slots) {
                if (slot.inventory == inventory && slot.getIndex() == best) {
                    handlerSlot = slot.id;
                    break;
                }
            }
            if (handlerSlot < 0 || mc.interactionManager == null) {
                return;
            }
            if (preCombatSlot < 0) {
                preCombatSlot = hotbar;
            }
            mc.interactionManager.clickSlot(handler.syncId, handlerSlot, hotbar,
                    net.minecraft.screen.slot.SlotActionType.SWAP, mc.player);
            DeepseekCompanionClient.LOGGER.info("[XiaoD] weapon: 从背包换入 slot {} (dps {})", best, String.format("%.2f", bestDps));
            return;
        }
        if (best != inventory.getSelectedSlot()) {
            if (preCombatSlot < 0) {
                preCombatSlot = inventory.getSelectedSlot();
            }
            inventory.setSelectedSlot(best);
            if (best != lastWeaponLogSlot) {
                lastWeaponLogSlot = best;
                DeepseekCompanionClient.LOGGER.info("[XiaoD] weapon -> slot {} (dps {})", best, String.format("%.2f", bestDps));
            }
        }
    }

    /**
     * Damage per second = (1 + item attack damage + sharpness) * attacks per second.
     * The player's attack speed attribute has a base of 4; swords/axes carry a
     * negative modifier that brings it down to their real swing rate.
     */
    private static double dps(ItemStack stack, boolean fireImmuneTarget) {
        double damage = 1.0;
        double speed = 4.0;
        try {
            var component = stack.get(net.minecraft.component.DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (component != null) {
                for (var entry : component.modifiers()) {
                    var attribute = entry.attribute().value();
                    if (attribute == EntityAttributes.ATTACK_DAMAGE.value()) {
                        damage += entry.modifier().value();
                    } else if (attribute == EntityAttributes.ATTACK_SPEED.value()) {
                        speed += entry.modifier().value();
                    }
                }
            }
        } catch (Throwable ignored) {
            // attribute API mismatch on another version: fall back to "no preference"
        }
        damage += sharpnessBonus(stack);
        if (speed <= 0.1) {
            speed = 0.1;
        }
        return damage * speed + (fireImmuneTarget ? 0.0 : fireAspectDps(stack));
    }

    /** Sharpness: +0.5 damage per level, plus 0.5 at level 1 (a flat +1 at I). */
    private static double sharpnessBonus(ItemStack stack) {
        int level = enchantLevel(stack, net.minecraft.enchantment.Enchantments.SHARPNESS);
        return level > 0 ? 0.5 * level + 0.5 : 0.0;
    }

    /**
     * Fire Aspect burns for 1 HP per second (half a heart per second) as long as
     * the target keeps burning, which continuous hits guarantee. This is a rate,
     * not a per-swing value, so it is added after the attack-speed multiplication.
     */
    private static double fireAspectDps(ItemStack stack) {
        return enchantLevel(stack, net.minecraft.enchantment.Enchantments.FIRE_ASPECT) > 0 ? 1.0 : 0.0;
    }

    private static int enchantLevel(ItemStack stack, net.minecraft.registry.RegistryKey<net.minecraft.enchantment.Enchantment> key) {
        try {
            var enchantments = stack.get(net.minecraft.component.DataComponentTypes.ENCHANTMENTS);
            if (enchantments == null) {
                return 0;
            }
            for (var entry : enchantments.getEnchantments()) {
                if (entry.matchesKey(key)) {
                    return enchantments.getLevel(entry);
                }
            }
        } catch (Throwable ignored) {
            // enchantment lookup unavailable: treat as unenchanted
        }
        return 0;
    }

    /** A sprinting player outruns everything slower than endermen/spiders. */
    private static boolean canOutrun(LivingEntity threat) {
        try {
            double speed = threat.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
            return speed < UNOUTRUNNABLE_SPEED;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void retreat(MinecraftClient mc, ClientPlayerEntity p, LivingEntity threat, long now) {
        if (!BaritoneBridge.available() || now - lastRetreatGoto < RETREAT_GOTO_MS) {
            return;
        }
        Vec3d away = p.getEntityPos().subtract(threat.getEntityPos());
        if (away.lengthSquared() < 0.01) {
            away = new Vec3d(1.0, 0.0, 0.0);
        }
        Vec3d destination = p.getEntityPos().add(away.normalize().multiply(6.0));
        int y = p.getBlockPos().getY();
        if (BaritoneBridge.gotoPos((int) Math.round(destination.x), y, (int) Math.round(destination.z))) {
            lastRetreatGoto = now;
            DeepseekCompanionClient.LOGGER.info("[XiaoD] retreating from {}", threat.getName().getString());
        }
    }
}

