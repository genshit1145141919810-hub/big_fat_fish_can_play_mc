// Add the missing `.xd craft` command (the model had a craft action, the chat command did not).
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/command/LocalCommandHandler.java';
let s = readFileSync(p, 'utf8');
const before = s;

if (!s.includes('case "craft"')) {
  s = s.replace('case "cook" -> cook(arg);', 'case "craft" -> craft(arg);\n            case "cook" -> cook(arg);');
  s = s.replace(
    '    private static void place(String arg) {',
    `    /** 合成（会自动展开原料树：缺木板/木棍会先合出来）。 */
    private static void craft(String arg) {
        try {
            String item = ActionValidator.validateItem(arg);
            boolean ok = io.github.xiaod.deepseekcompanion.bot.ContainerController
                    .craft(MinecraftClient.getInstance(), item);
            say(ok ? "§7开始合成 " + item : "§c合成不了 " + item);
        } catch (ActionValidator.FormatException e) {
            say("§c" + e.getMessage());
        }
    }

    private static void place(String arg) {`
  );
  s = s.replace('say("§7.xd place', 'say("§7.xd craft <物品> §f- 合成（缺原料会自动展开树）");\n        say("§7.xd place');
  console.log('craft command added');
} else {
  console.log('already present');
}

writeFileSync(p, s, 'utf8');
console.log('changed:', before !== s);
