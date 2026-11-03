// Verbose logging for the crafting click path so a silent "nothing happened" becomes diagnosable.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/ContainerController.java';
let s = readFileSync(p, 'utf8');
const log = 'io.github.xiaod.deepseekcompanion.DeepseekCompanionClient.LOGGER';

// 1) log which handler we opened
if (!s.includes('opened handler')) {
  const from = `                if (mode == Mode.CRAFT || mode == Mode.CRAFT_INV) {
                    buildCraftClicks(mc, handler);`;
  const to = `                ${log}.info("[XiaoD] opened handler {} (mode={}, gridWidth={})",
                        handler.getClass().getSimpleName(), mode, gridWidth);
                if (mode == Mode.CRAFT || mode == Mode.CRAFT_INV) {
                    buildCraftClicks(mc, handler);`;
  if (s.includes(from)) { s = s.replace(from, to); console.log('ok   handler log'); }
  else console.log('MISS handler anchor');
}

// 2) log every queued/executed click
if (!s.includes('click slot=')) {
  const from = `                mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, click.slot(),
                        click.button(), click.type(), p);`;
  const to = `                ${log}.info("[XiaoD] click slot={} button={} type={} stack={} remaining={}",
                        click.slot(), click.button(), click.type(),
                        p.currentScreenHandler.slots.get(click.slot()).getStack().getItem(),
                        QUEUE.size());
                mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, click.slot(),
                        click.button(), click.type(), p);`;
  if (s.includes(from)) { s = s.replace(from, to); console.log('ok   click log'); }
  else console.log('MISS click anchor');
}

// 3) log the chosen recipe and the grid slots
if (!s.includes('craft recipe')) {
  const from = `            if (ok) {
                QUEUE.addAll(plan);`;
  const to = `            if (ok) {
                ${log}.info("[XiaoD] craft recipe {} grid={}x{} cells={} clicks={}",
                        recipe.result(), recipe.width(), recipe.height(),
                        java.util.Arrays.toString(recipe.cells()), plan.size());
                QUEUE.addAll(plan);`;
  if (s.includes(from)) { s = s.replace(from, to); console.log('ok   recipe log'); }
  else console.log('MISS recipe anchor');
}

writeFileSync(p, s, 'utf8');
