// Wire "block in" into combat: when surrounded / nearly dead, wall up instead of running.
// PlacementController gets placeAt(target) so the wall can be built at a specific cell.
import { readFileSync, writeFileSync } from 'node:fs';

const base = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/';
const out = [];

function patch(file, from, to, label) {
  const p = base + file;
  let s = readFileSync(p, 'utf8');
  if (!s.includes(from)) {
    out.push('MISS ' + label);
    return;
  }
  writeFileSync(p, s.replace(from, to), 'utf8');
  out.push('ok   ' + label);
}

// 1) PlacementController.placeAt: place a block into an explicit cell by clicking the
//    neighbouring solid block's face.
patch(
  'PlacementController.java',
  '    private static int hotbarSlot(ClientPlayerEntity p, String id) {',
  `    /** 在指定格子放一个（选相邻实心方块的朝向面）。给"堵墙"用。 */
    public static boolean placeAt(MinecraftClient mc, BlockPos target) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null || target == null) {
            return false;
        }
        if (!mc.world.getBlockState(target).isAir()) {
            return false;
        }
        BlockState targetState = mc.world.getBlockState(target);
        if (!targetState.isAir() && !targetState.isReplaceable()) {
            return false;
        }
        for (Direction side : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, Direction.UP}) {
            BlockPos base = target.offset(side);
            BlockState baseState = mc.world.getBlockState(base);
            if (baseState.isAir() || !baseState.isSolidBlock(mc.world, base)) {
                continue;
            }
            Direction face = side.getOpposite();
            Vec3d hit = Vec3d.ofCenter(base).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5,
                    face.getOffsetZ() * 0.5);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, face, base, false));
            p.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private static int hotbarSlot(ClientPlayerEntity p, String id) {`,
  'placeAt'
);

// 2) combat: surrounded / nearly dead -> block in, not flee
patch(
  'CombatController.java',
  `            if (now > retreatUntil) {
                retreatUntil = now + RETREAT_MS;
            }
            retreat(mc, p, hostile, now);`,
  `            if (now > retreatUntil) {
                retreatUntil = now + RETREAT_MS;
            }
            if (!BlockInController.isBusy() && BlockInController.start(mc, hostile)) {
                return; // 有方块就垒墙，比背身跑更有用
            }
            retreat(mc, p, hostile, now);`,
  'combat uses block-in'
);

// 3) tick the controller + treat it as busy
patch('BotBrain.java', 'TestDriver.tick(mc);', 'TestDriver.tick(mc); BlockInController.tick(mc, CompanionConfig.get());', 'tick block-in');

patch(
  'PlanRunner.java',
  `        return MiningController.isBusy()
                || ContainerController.isBusy()`,
  `        return BlockInController.isBusy()
                || MiningController.isBusy()
                || ContainerController.isBusy()`,
  'plan waits for block-in'
);

console.log(out.join('\n'));
