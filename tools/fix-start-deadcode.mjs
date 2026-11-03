// Remove the dead remainder of start() left behind after inserting the staged return,
// then build. Regex-based so the Chinese strings do not need to be quoted here.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/AcquireTask.java';
let s = readFileSync(p, 'utf8');
const before = s.length;

s = s.replace(
  /return beginStage\(mc\);\r?\n[\s\S]*?\n    \}\r?\n\r?\n    private static boolean beginStage/,
  'return beginStage(mc);\n    }\n\n    private static boolean beginStage'
);

writeFileSync(p, s, 'utf8');
console.log('bytes:', before, '->', s.length);
