// Kiting by knockback: when the mob is close, ATTACK (the hit pushes it out of its own
// reach) instead of stepping back. Retreat stays reserved for "surrounded / nearly dead".
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/CombatController.java';
let s = readFileSync(p, 'utf8');

const from = `            if (gap < KEEP_MIN) {
                stepBack(mc, p, hostile, now);
                return;
            }
`;
if (s.includes(from)) {
  s = s.replace(from, '');
  console.log('backstep removed (hit knockback handles spacing)');
} else {
  console.log('MISS backstep call');
}

// drop the now-unused helper so the file stays honest
const helperStart = s.indexOf('    private static void stepBack(');
if (helperStart >= 0) {
  const helperEnd = s.indexOf('\n    }\n', s.indexOf('p.setVelocity(dx / len * speed', helperStart));
  if (helperEnd > 0) {
    s = s.slice(0, helperStart) + s.slice(helperEnd + 7);
    console.log('stepBack helper removed');
  } else {
    console.log('could not locate helper end');
  }
}

writeFileSync(p, s, 'utf8');
