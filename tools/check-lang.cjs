// Validate the English translation table:
//  - JSON parses
//  - every key actually exists as a source literal in the Java code (catches typos)
//  - how many Chinese literals are still untranslated (rough progress number)
//
//   node tools/check-lang.cjs
const fs = require('fs');
const path = require('path');

const langFile = 'src/main/resources/assets/big_fat_fish_can_play_mc/lang/en_us.json';
const json = JSON.parse(fs.readFileSync(langFile, 'utf8'));
const keys = Object.keys(json.strings || {});

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (entry.name.endsWith('.java')) out.push(full);
  }
  return out;
}

const sources = walk('src/main/java').map((f) => fs.readFileSync(f, 'utf8')).join('\n');
const missingInCode = keys.filter((k) => !sources.includes(k));

const literal = /"((?:[^"\\]|\\.)*)"/g;
const zh = new Set();
for (const file of walk('src/main/java')) {
  const text = fs.readFileSync(file, 'utf8');
  let m;
  while ((m = literal.exec(text)) !== null) {
    const value = m[1];
    if (/[\u4e00-\u9fff]/.test(value)) zh.add(value);
  }
}

const translated = new Set(keys);
const untranslated = [...zh].filter((t) => !translated.has(t));

console.log('strings in en_us.json :', keys.length);
console.log('rules in en_us.json   :', (json.rules || []).length);
console.log('chinese literals       :', zh.size);
console.log('untranslated           :', untranslated.length);
if (missingInCode.length) {
  console.log('\nkeys that do not exist in the code (typos / stale):');
  for (const k of missingInCode) console.log('  - ' + k);
  process.exitCode = 1;
} else {
  console.log('\nall keys match a source literal');
}
const sample = untranslated.filter((t) => t.length >= 6 && t.trim() === t).slice(0, 15);
if (sample.length) {
  console.log('\nsample still-Chinese strings:');
  for (const s of sample) console.log('  · ' + s);
}
