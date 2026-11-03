// Placement in creative: the SWAP click updates the inventory one tick later, so the
// immediate re-check returned -1 and `place` bailed out. Do the swap, then retry on the
// next ticks through a tiny pending state.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/PlacementController.java';
let s = readFileSync(p, 'utf8');

// 1) fields + tick
if (!s.includes('pendingItem')) {
  s = s.replace(
    `    private PlacementController() {
    }`,
    `    /** 换手后等一 tick 再放：SWAP 的结果要等服务端同步回来。 */
    private static String pendingItem;
    private static long pendingUntil;

    private PlacementController() {
    }

    public static void tick(MinecraftClient mc) {
        if (pendingItem == null) {
            return;
        }
        if (mc.player == null || System.currentTimeMillis() > pendingUntil) {
            pendingItem = null;
            return;
        }
        if (hotbarSlot(mc.player, pendingItem) >= 0) {
            String item = pendingItem;
            pendingItem = null;
            place(mc, item);
        }
    }`
  );
  console.log('pending state added');
}

// 2) when the item had to be pulled from the main inventory, defer the actual placement
s = s.replace(
  `        int slot = hotbarSlot(p, id);
        if (slot < 0) {
            // 不在快捷栏就从背包里换过来（SlotActionType.SWAP 等于按数字键换位）
            slot = pullToHotbar(mc, p, id);
        }`,
  `        int slot = hotbarSlot(p, id);
        if (slot < 0) {
            // 不在快捷栏就从背包里换过来（SlotActionType.SWAP 等于按数字键换位）
            slot = pullToHotbar(mc, p, id);
            if (slot < 0) {
                DeepseekCompanionClient.LOGGER.info("[XiaoD] place {}: not in hotbar and not in inventory", id);
                BotBrain.say("身上没有 " + id + "（工作台/熔炉这些得先合出来）");
                return false;
            }
            pendingItem = id;
            pendingUntil = System.currentTimeMillis() + 5000L;
            DeepseekCompanionClient.LOGGER.info("[XiaoD] place {}: swapped into hotbar {}, retrying next tick", id, slot);
            return true;
        }`
);

// 3) swap is optimistic now (no immediate client-side stack check)
s = s.replace(
  `        mc.interactionManager.clickSlot(0, inventorySlot, hotbar, net.minecraft.screen.slot.SlotActionType.SWAP, p);
        return p.getInventory().getStack(hotbar).isEmpty() ? -1 : hotbar;`,
  `        mc.interactionManager.clickSlot(0, inventorySlot, hotbar, net.minecraft.screen.slot.SlotActionType.SWAP, p);
        p.getInventory().setSelectedSlot(hotbar);
        return hotbar; // 服务端同步要一 tick，别在这里立刻校验背包`
);

writeFileSync(p, s, 'utf8');
console.log('placement deferral wired');
