// Horde size is NOT a reason to run: a normal player clears ten zombies.
// New rule: fight everything; only disengage when nearly dead, or when a creeper is about
// to blow up next to us (walking away from that is not cowardice).
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/CombatController.java';
let s = readFileSync(p, 'utf8');

const from = `        int nearbyThreats = countThreats(mc, 8.0);
        boolean desperate = p.getHealth() <= Math.min(4.0f, cfg.retreatHealth);
        boolean surrounded = nearbyThreats >= 2;
        if (cfg.retreatHealth > 0 && (desperate || surrounded)`;

const to = `        // 血量见底才算危险；被围不算（普通玩家打十个僵尸也不难）
        boolean desperate = p.getHealth() <= Math.min(2.0f, cfg.retreatHealth);
        // 苦力怕贴身：这时候躲开不是临阵脱逃，是常识
        boolean creeperClose = hostile != null
                && hostile.squaredDistanceTo(p) < 36.0
                && Registries.ENTITY_TYPE.getId(hostile.getType()).getPath().contains("creeper");
        if (cfg.retreatHealth > 0 && (desperate || creeperClose)`;

if (s.includes(from)) {
  writeFileSync(p, s.replace(from, to), 'utf8');
  console.log('retreat rule replaced (fight by default)');
} else {
  console.log('MISS rule anchor');
}
