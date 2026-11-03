// Add `.xd see` — prints exactly what the perception layer feeds the model.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/command/LocalCommandHandler.java';
let s = readFileSync(p, 'utf8');

if (s.includes('case "see"')) {
  console.log('already present');
} else {
  s = s.replace('case "chat" ->', 'case "see" -> see();\n            case "chat" ->');
  s = s.replace(
    '    private static void debug(String arg) {',
    `    /** 感知层自检：一次打印模型会看到的那些感知信息。 */
    private static void see() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String[] lines = {
                io.github.xiaod.deepseekcompanion.bot.Perception.look(mc),
                io.github.xiaod.deepseekcompanion.bot.Perception.danger(mc),
                io.github.xiaod.deepseekcompanion.bot.Perception.path(mc),
                io.github.xiaod.deepseekcompanion.bot.Perception.containers(mc),
                io.github.xiaod.deepseekcompanion.bot.Perception.drops(mc),
                io.github.xiaod.deepseekcompanion.bot.Perception.players(mc)
        };
        for (String line : lines) {
            for (String part : line.split("\\n")) {
                say("§7" + part);
            }
        }
    }

    private static void debug(String arg) {`
  );
  writeFileSync(p, s, 'utf8');
  console.log('see command added');
}
