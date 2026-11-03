// Test driver directives:
//   #step=<ms>   change the delay between commands (default 3000)
//   #sleep=<ms>  wait before the next command
// Lets a test fire ten summons within ~1s and then sit still for a minute.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/TestDriver.java';
let s = readFileSync(p, 'utf8');

if (s.includes('#step=')) {
  console.log('directives already present');
} else {
  s = s.replace(
    '    private static long nextAt;',
    `    private static long nextAt;
    private static long stepMs = 3000L;`
  );
  s = s.replace(
    `        String line = lines.get(index++).strip();
        nextAt = now + STEP_MS;`,
    `        String line = lines.get(index++).strip();
        if (line.startsWith("#step=")) {
            try {
                stepMs = Math.max(50L, Long.parseLong(line.substring(6).trim()));
            } catch (NumberFormatException ignored) {
                // keep the previous step delay
            }
            nextAt = now + 50L;
            return;
        }
        if (line.startsWith("#sleep=")) {
            try {
                nextAt = now + Math.max(0L, Long.parseLong(line.substring(7).trim()));
            } catch (NumberFormatException ignored) {
                nextAt = now + stepMs;
            }
            return;
        }
        nextAt = now + stepMs;`
  );
  s = s.replace('nextAt = now + STEP_MS;\n        }', 'nextAt = now + stepMs;\n        }');
  writeFileSync(p, s, 'utf8');
  console.log('directives added');
}
