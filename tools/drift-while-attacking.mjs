// Kiting = drift backwards WHILE swinging. The attack still fires every cooldown tick;
// the small backward nudge just keeps us at the edge of the mob's reach.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/CombatController.java';
let s = readFileSync(p, 'utf8');

const from = `            if (gap <= MELEE_REACH * MELEE_REACH) {
                equipBestWeapon(mc, cfg, now, hostile.isFireImmune());`;

const to = `            if (gap <= MELEE_REACH * MELEE_REACH) {
                if (gap < KEEP_MIN) {
                    driftBack(p, hostile, now); // 边退边打：只退一点点，攻击照常
                }
                equipBestWeapon(mc, cfg, now, hostile.isFireImmune());`;

if (s.includes(from)) {
  s = s.replace(from, to);
  console.log('drift-while-attacking added');
} else {
  console.log('MISS attack anchor');
}

if (!s.includes('private static void driftBack')) {
  s = s.replace(
    '    private static void hit(MinecraftClient mc, ClientPlayerEntity p, LivingEntity target) {',
    `    /** 小幅后退，为了在攻击冷却期间脱离怪的近战距离；不影响本 tick 出手。 */
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

    private static void hit(MinecraftClient mc, ClientPlayerEntity p, LivingEntity target) {`
  );
  console.log('driftBack helper added');
}

writeFileSync(p, s, 'utf8');
