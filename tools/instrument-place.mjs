// Instrument the placement scan: it reported "no spot" in the test world, need to know which
// check rejected every candidate. Also widen the search a little.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/PlacementController.java';
let s = readFileSync(p, 'utf8');
const log = 'io.github.xiaod.deepseekcompanion.DeepseekCompanionClient.LOGGER';

s = s.replace('REACH_HORIZONTAL = 3', 'REACH_HORIZONTAL = 4');
s = s.replace('REACH_UP = 1', 'REACH_UP = 2');
s = s.replace('REACH_DOWN = 2', 'REACH_DOWN = 3');

if (!s.includes('place scan')) {
  s = s.replace(
    `        BlockPos origin = p.getBlockPos();
        BlockPos.Mutable cursor = new BlockPos.Mutable();`,
    `        BlockPos origin = p.getBlockPos();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        int baseCount = 0;
        int airCount = 0;
        int overlapCount = 0;`
  );
  s = s.replace(
    `                    if (base.isAir() || !base.isSolidBlock(mc.world, cursor)) {
                        continue;
                    }
                    BlockPos above = cursor.up();
                    if (!mc.world.getBlockState(above).isAir()) {
                        continue;
                    }`,
    `                    if (base.isAir() || !base.isSolidBlock(mc.world, cursor)) {
                        continue;
                    }
                    baseCount++;
                    BlockPos above = cursor.up();
                    if (!mc.world.getBlockState(above).isAir()) {
                        airCount++;
                        continue;
                    }`
  );
  s = s.replace(
    `                    if (p.getBoundingBox().intersects(new Box(above))) {
                        continue;
                    }`,
    `                    if (p.getBoundingBox().intersects(new Box(above))) {
                        overlapCount++;
                        continue;
                    }`
  );
  s = s.replace(
    `        BotBrain.say("脚边没有能放 " + id + " 的位置");`,
    `${log}.info("[XiaoD] place scan {}: bases={} notAir={} overlap={} origin={}",
                id, baseCount, airCount, overlapCount, origin);
        BotBrain.say("脚边没有能放 " + id + " 的位置");`
  );
  console.log('scan instrumented');
}

writeFileSync(p, s, 'utf8');
console.log('done');
