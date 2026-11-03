// Enable cheats (Data.allowCommands) in a Minecraft level.dat by patching the NBT byte
// in place. Only the one payload byte changes, so the rest of the file is untouched.
// Usage: node level-cheats.mjs <path to level.dat>
import { readFileSync, writeFileSync, copyFileSync } from 'node:fs';
import { gunzipSync, gzipSync } from 'node:zlib';

const file = process.argv[2];
if (!file) {
  console.error('usage: node level-cheats.mjs <level.dat>');
  process.exit(2);
}

const raw = readFileSync(file);
const nbt = gunzipSync(raw);

const needle = Buffer.from('allowCommands', 'ascii');
const idx = nbt.indexOf(needle);
if (idx < 0) {
  console.error('allowCommands not found');
  process.exit(1);
}

// layout: [tagType 1B][nameLen 2B][name][payload 1B]
const nameLen = nbt.readUInt16BE(idx - 2);
const type = nbt[idx - 3];
const payloadAt = idx + needle.length;
const before = nbt[payloadAt];
console.log(`found allowCommands at ${idx} (type=${type}, nameLen=${nameLen}, value=${before})`);
if (type !== 1) {
  console.error('unexpected tag type for allowCommands');
  process.exit(1);
}
if (before === 1) {
  console.log('cheats already enabled, nothing to do');
  process.exit(0);
}

copyFileSync(file, file + '.bak');
nbt[payloadAt] = 1;
writeFileSync(file, gzipSync(nbt));
const check = gunzipSync(readFileSync(file));
const at = check.indexOf(needle) + needle.length;
console.log('wrote level.dat, allowCommands now =', check[at]);
