// 2x2 crafting: do NOT open a GUI. The player's own inventory handler is always active,
// and in creative the client refuses to keep an InventoryScreen open (that is why the
// "界面没打开" path kept firing). Click the handler directly.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/ContainerController.java';
let s = readFileSync(p, 'utf8');

const from = `                if (mode == Mode.CRAFT_INV) {
                    // 自己的背包界面一直和客户端同步，直接打开就行（相当于按 E）
                    mc.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(p));
                    phase = Phase.OPENING;
                    until = now + 3000L;
                    return;
                }`;

const to = `                if (mode == Mode.CRAFT_INV) {
                    // 不打开界面：玩家自己的 PlayerScreenHandler 始终是激活的（syncId 0），
                    // 直接往里点就行。创造模式下客户端不允许保留 InventoryScreen，
                    // 之前就是卡在"界面没打开"。
                    if (!(p.currentScreenHandler instanceof net.minecraft.screen.PlayerScreenHandler)) {
                        if (mc.currentScreen != null) {
                            p.closeHandledScreen();
                        }
                        return;
                    }
                    buildCraftClicks(mc, p.currentScreenHandler);
                    phase = Phase.CLICKS;
                    return;
                }`;

if (s.includes(from)) {
  writeFileSync(p, s.replace(from, to), 'utf8');
  console.log('CRAFT_INV no longer opens a screen');
} else {
  console.log('MISS FIND anchor');
}
