// Create a brand-new Minecraft world folder from a template level.dat.
// Usage: node tools/new-world.mjs <savesDir> <worldName> [templateName] [seed]
//
// The folder only gets level.dat (+icon), so the game generates terrain from
// scratch on first load. A fresh RandomSeed is written so it is a genuinely
// different world rather than a replay of the template.
import { readFileSync, writeFileSync, mkdirSync, rmSync, copyFileSync, existsSync } from 'node:fs';
import { gunzipSync, gzipSync } from 'node:zlib';
import { randomBytes } from 'node:crypto';
import { join } from 'node:path';

const [savesDir, worldName, templateName = '新的世界', seedArg] = process.argv.slice(2);
if (!savesDir || !worldName) {
  console.error('usage: node tools/new-world.mjs <savesDir> <worldName> [templateName] [seed]');
  process.exit(2);
}

const template = join(savesDir, templateName, 'level.dat');
if (!existsSync(template)) {
  console.error(`template level.dat not found: ${template}`);
  process.exit(1);
}

const target = join(savesDir, worldName);
rmSync(target, { recursive: true, force: true });
mkdirSync(target, { recursive: true });
copyFileSync(template, join(target, 'level.dat'));
const icon = join(savesDir, templateName, 'icon.png');
if (existsSync(icon)) copyFileSync(icon, join(target, 'icon.png'));

// ---- patch RandomSeed (TAG_Long) and allowCommands (TAG_Byte) ----
const raw = readFileSync(join(target, 'level.dat'));
const nbt = gunzipSync(raw);

function patchLong(buf, name, value) {
  const needle = Buffer.from(name, 'ascii');
  const idx = buf.indexOf(needle);
  if (idx < 0) return false;
  const len = buf.readUInt16BE(idx - 2);
  const type = buf[idx - 3];
  if (len !== name.length || type !== 4) return false;
  buf.writeBigInt64BE(BigInt(value), idx + name.length);
  return true;
}

function patchByte(buf, name, value) {
  const needle = Buffer.from(name, 'ascii');
  const idx = buf.indexOf(needle);
  if (idx < 0) return false;
  const len = buf.readUInt16BE(idx - 2);
  const type = buf[idx - 3];
  if (len !== name.length || type !== 1) return false;
  buf[idx + name.length] = value;
  return true;
}

const seed = seedArg ? Number(seedArg) : Number(randomBytes(6).readUIntBE(0, 6)) - 0x800000000000;
const seeded = patchLong(nbt, 'seed', seed);
patchByte(nbt, 'allowCommands', 1);
patchLong(nbt, 'LastPlayed', Date.now());

writeFileSync(join(target, 'level.dat'), gzipSync(nbt));
console.log(JSON.stringify({ world: target, seed, seeded }, null, 2));
