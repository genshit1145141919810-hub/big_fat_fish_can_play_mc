// Retreat should be a fighting withdrawal: keep swinging at anything already in reach
// (its knockback buys the space), instead of turning our back and dying to a horde.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/CombatController.java';
let s = readFileSync(p, 'utf8');

const from = `    private static void retreat(MinecraftClient mc, ClientPlayerEntity p, LivingEntity threat, long now) {
        if (!BaritoneBridge.available() || now - lastRetreatGoto < RETREAT_GOTO_MS) {
            return;
        }`;

const to = `    private static void retreat(MinecraftClient mc, ClientPlayerEntity p, LivingEntity threat, long now) {
        // 边退边打：已经贴身的怪先吃一记击退，退路才打得开
        if (threat.squaredDistanceTo(p) <= MELEE_REACH * MELEE_REACH) {
            attack(mc, p, threat, CompanionConfig.get(), now);
        }
        if (!BaritoneBridge.available() || now - lastRetreatGoto < RETREAT_GOTO_MS) {
            return;
        }`;

if (s.includes(from)) {
  writeFileSync(p, s.replace(from, to), 'utf8');
  console.log('fighting withdrawal enabled');
} else {
  console.log('MISS retreat anchor');
}
