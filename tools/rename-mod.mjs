// Rename the project to "big-fat-fish_can_playMC".
//   display name  : big-fat-fish_can_playMC
//   mod id        : big_fat_fish_can_play_mc   (Fabric ids are [a-z0-9_] only)
//   jar base name : big-fat-fish-can-play-mc
//   config file   : bigfatfish.json
//   asset ns      : assets/big_fat_fish_can_play_mc/
// Java package stays io.github.xiaod.deepseekcompanion (invisible to users, avoids a 100-file churn).
import { readFileSync, writeFileSync, renameSync, existsSync, mkdirSync, copyFileSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';

const root = 'D:/Chat/deepseek-companion';
const src = join(root, 'src/main');
const ID = 'big_fat_fish_can_play_mc';
const NAME = 'big-fat-fish_can_playMC';
const JAR = 'big-fat-fish-can-play-mc';
const MIXIN = 'bigfatfish';
const CONFIG = 'bigfatfish.json';
const done = [];

function read(p) { return readFileSync(p, 'utf8'); }
function write(p, s) { writeFileSync(p, s, 'utf8'); }

// ---- 1. resources: rename mixin configs, assets dir, fabric.mod.json
const resDir = join(src, 'resources');
const mixinFile = join(resDir, 'deepseekcompanion.mixins.json');
const mixinBar = join(resDir, 'deepseekcompanion.baritone.mixins.json');
if (existsSync(mixinFile)) {
  let s = read(mixinFile);
  write(mixinFile, s);
  renameSync(mixinFile, join(resDir, `${MIXIN}.mixins.json`));
  done.push('renamed mixin config');
}
if (existsSync(mixinBar)) {
  renameSync(mixinBar, join(resDir, `${MIXIN}.baritone.mixins.json`));
  done.push('renamed baritone mixin config');
}

const assetsOld = join(resDir, 'assets', 'deepseekcompanion');
const assetsNew = join(resDir, 'assets', ID);
if (existsSync(assetsOld)) {
  mkdirSync(dirname(assetsNew), { recursive: true });
  for (const file of readdirSafe(assetsOld)) {
    copyFileSync(join(assetsOld, file), join(assetsNew, file));
  }
  rmSync(assetsOld, { recursive: true, force: true });
  done.push('moved assets -> ' + ID);
}

function readdirSafe(dir) {
  try { return require('node:fs').readdirSync(dir); } catch { return []; }
}

// ---- 2. fabric.mod.json
const fmj = join(resDir, 'fabric.mod.json');
let j = JSON.parse(read(fmj));
j.id = ID;
j.name = NAME;
j.description = 'A real second-account AI teammate: chats, follows, mines, crafts, fights and plans. Works with any OpenAI-compatible endpoint (DeepSeek by default).';
j.mixins = [`${MIXIN}.mixins.json`, `${MIXIN}.baritone.mixins.json`];
j.icon = `assets/${ID}/icon.png`;
write(fmj, JSON.stringify(j, null, 2) + '\n');
done.push('fabric.mod.json rewritten');

// ---- 3. java references: asset path + config file name
const javaFiles = walk(join(src, 'java'));
let javaTouched = 0;
for (const file of javaFiles) {
  let s = read(file);
  const before = s;
  s = s.replaceAll('/assets/deepseekcompanion/', `/assets/${ID}/`);
  s = s.replaceAll('"deepseek-companion.json"', `"${CONFIG}"`);
  s = s.replaceAll('config/deepseek-companion.json', `config/${CONFIG}`);
  if (s !== before) {
    write(file, s);
    javaTouched++;
  }
}
done.push(`java files updated: ${javaTouched}`);

function walk(dir) {
  const out = [];
  for (const entry of require('node:fs').readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(p));
    else out.push(p);
  }
  return out;
}

// ---- 4. gradle.properties
const gp = join(root, 'gradle.properties');
let props = read(gp);
props = props.replace(/archives_base_name=.*/m, `archives_base_name=${JAR}`);
write(gp, props);
done.push('jar base name -> ' + JAR);

// ---- 5. addon entrypoint id stays the same (it is an interface id, not the mod id) - keep as is
console.log(done.join('\n'));
