// sendChatMessage does not interpret a leading '/', so chat commands have to go through
// sendChatCommand (without the slash). Otherwise the whole line lands in public chat.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/TestDriver.java';
let s = readFileSync(p, 'utf8');

const from = `            } else if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendChatMessage(line);
            }`;

const to = `            } else if (mc.getNetworkHandler() != null) {
                if (line.startsWith("/")) {
                    // 命令必须走 sendChatCommand（不带前导 /），sendChatMessage 会原样发到公屏
                    mc.getNetworkHandler().sendChatCommand(line.substring(1));
                } else {
                    mc.getNetworkHandler().sendChatMessage(line);
                }
            }`;

if (!s.includes('sendChatCommand')) {
  if (s.includes(from)) {
    writeFileSync(p, s.replace(from, to), 'utf8');
    console.log('TestDriver command routing fixed');
  } else {
    console.log('MISS routing anchor');
  }
} else {
  console.log('already fixed');
}
