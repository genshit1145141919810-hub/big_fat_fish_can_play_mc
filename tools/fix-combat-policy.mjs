// Combat policy: losing health is not by itself a reason to run.
//   * health <= min(4, retreatHealth)  -> really dangerous, disengage
//   * two or more monsters within 8     -> surrounded, disengage
//   * otherwise (single mob, health ok) -> hold the 2.4~3.2 band and fight
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/CombatController.java';
let s = readFileSync(p, 'utf8');

const from = `        if (cfg.retreatHealth > 0 && p.getHealth() <= cfg.retreatHealth
                && hostile != null && hostile.squaredDistanceTo(p) < 100.0
                && canOutrun(hostile)) {`;

const to = `        int nearbyThreats = countThreats(mc, 8.0);
        boolean desperate = p.getHealth() <= Math.min(4.0f, cfg.retreatHealth);
        boolean surrounded = nearbyThreats >= 2;
        if (cfg.retreatHealth > 0 && (desperate || surrounded)
                && hostile != null && hostile.squaredDistanceTo(p) < 100.0
                && canOutrun(hostile)) {`;

if (s.includes(from)) {
  s = s.replace(from, to);
  console.log('retreat policy replaced');
} else {
  console.log('MISS retreat anchor');
}

if (!s.includes('countThreats(')) {
  s = s.replace(
    '    private static void hit(MinecraftClient mc, ClientPlayerEntity p, LivingEntity target) {',
    `    /** 8 格内还活着的怪物数量：被围才值得跑，单只用控距打。 */
    private static int countThreats(MinecraftClient mc, double radius) {
        if (mc.player == null || mc.world == null) {
            return 0;
        }
        return mc.world.getEntitiesByClass(LivingEntity.class, mc.player.getBoundingBox().expand(radius),
                e -> e instanceof net.minecraft.entity.mob.Monster && e.isAlive()).size();
    }

    private static void hit(MinecraftClient mc, ClientPlayerEntity p, LivingEntity target) {`
  );
  console.log('countThreats added');
}

writeFileSync(p, s, 'utf8');
