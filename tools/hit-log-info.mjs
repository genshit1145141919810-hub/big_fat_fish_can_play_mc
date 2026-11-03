// Make the per-hit log visible at INFO (it was DEBUG, so combat tests were blind).
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/CombatController.java';
let s = readFileSync(p, 'utf8');
const from = 'DeepseekCompanionClient.LOGGER.debug("[XiaoD] hit';
const to = 'DeepseekCompanionClient.LOGGER.info("[XiaoD] hit';
if (s.includes(from)) {
  writeFileSync(p, s.replace(from, to), 'utf8');
  console.log('hit log -> info');
} else {
  console.log('anchor missing');
}
