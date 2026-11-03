// Move remaining status chatter off public chat: owner-missing line and flee line.
import { readFileSync, writeFileSync } from 'node:fs';

const B = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/';

function patch(file, from, to) {
  const path = B + file;
  const s = readFileSync(path, 'utf8');
  if (!s.includes(from)) {
    console.log('MISS ' + file + ' :: ' + from.slice(0, 40));
    return false;
  }
  writeFileSync(path, s.replace(from, to), 'utf8');
  console.log('ok   ' + file);
  return true;
}

patch('BotBrain.java', 'say(pick(', 'tell(pick(');
patch('FleeController.java', 'BotBrain.say(', 'BotBrain.tell(');

// combat: only chase with Baritone when the target is far; stop the chase when too close
const combat = B + 'CombatController.java';
let c = readFileSync(combat, 'utf8');
const chaseFrom = 'if (hostile != null && BaritoneBridge.available() && !TaskTracker.isBusy()\n                && now - lastChase > CHASE_COOLDOWN_MS && !MiningController.isBusy()) {';
const chaseTo = 'if (hostile != null && BaritoneBridge.available() && !TaskTracker.isBusy()\n                && hostile.squaredDistanceTo(p) > 25.0\n                && now - lastChase > CHASE_COOLDOWN_MS && !MiningController.isBusy()) {';
if (c.includes(chaseFrom)) {
  c = c.replace(chaseFrom, chaseTo);
  console.log('ok   CombatController chase distance gate');
} else {
  console.log('MISS CombatController chase gate');
}

const backFrom = 'p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());\n        if (now - lastBaritoneStop > 2500L) {\n            BaritoneBridge.stop();\n            lastBaritoneStop = now;\n        }';
const backTo = 'p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());\n        if (TaskTracker.isBusy()) {\n            BaritoneBridge.stop();\n            TaskTracker.clear();\n            lastBaritoneStop = now;\n        } else if (now - lastBaritoneStop > 2500L) {\n            BaritoneBridge.stop();\n            lastBaritoneStop = now;\n        }';
if (c.includes(backFrom)) {
  c = c.replace(backFrom, backTo);
  console.log('ok   CombatController backstep stops chase');
} else {
  console.log('MISS CombatController backstep');
}

writeFileSync(combat, c, 'utf8');
