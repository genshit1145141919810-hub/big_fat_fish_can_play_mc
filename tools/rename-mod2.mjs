// Finish the rename (the previous run died before the java edits) and set the Chinese name.
import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';

const root = 'D:/Chat/deepseek-companion';
const src = join(root, 'src/main');
const ID = 'big_fat_fish_can_play_mc';
const NAME = '大肥鱼能玩MC';
const SUB = 'Big Fat Fish Can Play MC';
const JAR = 'big-fat-fish-can-play-mc';
const CONFIG = 'bigfatfish.json';
const done = [];

function walk(dir) {
  const out = [];
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(p));
    else out.push(p);
  }
  return out;
}

// 1) java: asset namespace + config file name
let touched = 0;
for (const file of walk(join(src, 'java'))) {
  let s = readFileSync(file, 'utf8');
  const before = s;
  s = s.replaceAll('/assets/deepseekcompanion/', `/assets/${ID}/`);
  s = s.replaceAll('"deepseek-companion.json"', `"${CONFIG}"`);
  s = s.replaceAll('config/deepseek-companion.json', `config/${CONFIG}`);
  if (s !== before) {
    writeFileSync(file, s, 'utf8');
    touched++;
  }
}
done.push(`java files updated: ${touched}`);

// 2) fabric.mod.json: Chinese display name + english subtitle in description
const fmj = join(src, 'resources/fabric.mod.json');
const mod = JSON.parse(readFileSync(fmj, 'utf8'));
mod.id = ID;
mod.name = NAME;
mod.description = `${SUB} — 一个真实第二个账号的 AI 搭子：聊天、跟随、挖矿、合成、战斗、多步规划。默认接 DeepSeek，任何 OpenAI 兼容端点都能用。`;
mod.icon = `assets/${ID}/icon.png`;
mod.mixins = ['bigfatfish.mixins.json', 'bigfatfish.baritone.mixins.json'];
writeFileSync(fmj, JSON.stringify(mod, null, 2) + '\n', 'utf8');
done.push('fabric.mod.json: name/description/id');

// 3) gradle.properties jar base name
const gp = join(root, 'gradle.properties');
let props = readFileSync(gp, 'utf8');
props = props.replace(/archives_base_name=.*/m, `archives_base_name=${JAR}`);
writeFileSync(gp, props, 'utf8');
done.push('jar base -> ' + JAR);

// 4) release-check.ps1: new asset paths + default jar
const rc = join(root, 'tools/release-check.ps1');
let check = readFileSync(rc, 'utf8');
check = check.replaceAll('assets/deepseekcompanion/', `assets/${ID}/`);
check = check.replace(/deepseek-companion-0\.3\.0-alpha\.1\.jar/, `${JAR}-0.3.0-alpha.1.jar`);
check = check.replaceAll('deepseek-companion-<ver>', `${JAR}-<ver>`);
writeFileSync(rc, check, 'utf8');
done.push('release-check paths');

// 5) docs: titles
const readme = join(root, 'README.md');
let r = readFileSync(readme, 'utf8');
r = r.replace(/^# .*$/m, `# 大肥鱼能玩MC（${SUB}）`);
writeFileSync(readme, r, 'utf8');

const store = join(root, 'docs/store-description.md');
let st = readFileSync(store, 'utf8');
st = st.replace(/^# .*$/m, `# ${SUB}（大肥鱼能玩MC）— Modrinth / CurseForge description`);
st = st.replace(/^## Summary.*$/m, `## Summary (short, 200 chars)

大肥鱼能玩MC (Big Fat Fish Can Play MC) — a real second-account AI teammate: chat, follow,
mine, craft, fight and plan. Works with any OpenAI-compatible endpoint.`);
writeFileSync(store, st, 'utf8');
done.push('docs titles');

console.log(done.join('\n'));
