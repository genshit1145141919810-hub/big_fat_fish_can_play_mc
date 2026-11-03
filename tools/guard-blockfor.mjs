// Guard blockFor(): a bad identifier (tag string, empty, uppercase) must not throw and
// abort the whole planning pass.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/AcquireTask.java';
let s = readFileSync(p, 'utf8');

if (s.includes('isLikelyItemId')) {
  console.log('already guarded');
} else {
  s = s.replace(
    '    private static String blockFor(String item) {',
    `    /** 粗筛一下，避免把 tag 文本或空串丢进 Identifier.of 里抛异常。 */
    private static boolean isLikelyItemId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static String blockFor(String item) {`
  );
  s = s.replace(
    '        return Registries.BLOCK.containsId(Identifier.of("minecraft", item)) ? item : null;',
    `        if (!isLikelyItemId(item)) {
            return null;
        }
        try {
            return Registries.BLOCK.containsId(Identifier.of("minecraft", item)) ? item : null;
        } catch (Throwable ignored) {
            return null;
        }`
  );
  writeFileSync(p, s, 'utf8');
  console.log('blockFor guarded');
}
