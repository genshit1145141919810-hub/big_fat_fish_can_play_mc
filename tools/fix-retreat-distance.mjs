// 1) retreat distance 18 -> 6 blocks (was fleeing too far)
// 2) build the wall at 2-3 blocks towards the mob (the adjacent cell is usually occupied)
// 3) after the wall is up, suppress retreat for a while so it actually stays behind it
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

// 1) shorter retreat
patch('CombatController.java', 'away.normalize().multiply(18.0)', 'away.normalize().multiply(6.0)', 'retreat 6 blocks');

// 3) do not run off right after walling up
patch(
  'CombatController.java',
  `            if (!BlockInController.isBusy() && BlockInController.start(mc, hostile)) {
                return; // 有方块就垒墙，比背身跑更有用
            }`,
  `            if (BlockInController.recentlyWalled()) {
                return; // 刚垒好墙，先躲在后面回血，别再往外跑
            }
            if (!BlockInController.isBusy() && BlockInController.start(mc, hostile)) {
                return; // 有方块就垒墙，比背身跑更有用
            }`,
  'suppress retreat after wall'
);

// 2) wall cell at 2 or 3 blocks towards the mob + remember when it is up
patch(
  'BlockInController.java',
  `        Direction facing = horizontalTowards(p, threat);
        BlockPos feet = p.getBlockPos().offset(facing);
        if (PlacementController.placeAt(mc, feet)) {
            placed++;
            lastPlaceAt = now;
            return;
        }
        BlockPos head = feet.up();
        if (PlacementController.placeAt(mc, head)) {
            placed++;
            lastPlaceAt = now;
            return;
        }`,
  `        Direction facing = horizontalTowards(p, threat);
        BlockPos origin = p.getBlockPos();
        for (int distance = 2; distance <= 3; distance++) {
            BlockPos feet = origin.offset(facing, distance);
            if (PlacementController.placeAt(mc, feet)) {
                placed++;
                lastPlaceAt = now;
                return;
            }
            if (PlacementController.placeAt(mc, feet.up())) {
                placed++;
                lastPlaceAt = now;
                return;
            }
        }`,
  'wall at 2-3 blocks'
);

patch(
  'BlockInController.java',
  '    private static int placed;',
  `    private static int placed;
    /** 墙垒好后的一段时间里，别再往外跑。 */
    private static long walledUntil;`,
  'walledUntil field'
);

patch(
  'BlockInController.java',
  `        if (now - startedAt > GIVE_UP_MS || placed >= WALL_HEIGHT * 2) {`,
  `        if (placed >= WALL_HEIGHT && walledUntil < now) {
            walledUntil = now + 12_000L;
            DeepseekCompanionClient.LOGGER.info("[XiaoD] wall is up ({} blocks), holding position", placed);
        }
        if (now - startedAt > GIVE_UP_MS || placed >= WALL_HEIGHT * 2) {`,
  'record wall up'
);

patch(
  'BlockInController.java',
  `    public static boolean isBusy() {
        return threat != null;
    }`,
  `    public static boolean isBusy() {
        return threat != null;
    }

    /** 刚垒好墙：这期间应该守着墙回血，而不是继续跑。 */
    public static boolean recentlyWalled() {
        return System.currentTimeMillis() < walledUntil;
    }`,
  'recentlyWalled'
);

console.log(out.join('\n'));
