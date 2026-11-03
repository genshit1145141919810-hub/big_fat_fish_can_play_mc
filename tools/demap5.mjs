// After mapping class_XXX -> simple names, the package part of MC imports is still the
// intermediary one (net.minecraft.<Name>). Rebuild every MC import from the mappings'
// full named path instead.
import { readFileSync, writeFileSync, existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const CACHE = 'C:/Users/guane/.gradle/caches/fabric-loom';
const target = process.argv[2];

function findMappings(version) {
  const dir = join(CACHE, version);
  for (const sub of readdirSync(dir)) {
    const file = join(dir, sub, 'mappings.tiny');
    if (existsSync(file)) return file;
  }
  throw new Error('mappings.tiny not found');
}

const lines = readFileSync(findMappings('1.21.11'), 'utf8').split('\n');
const bySimple = new Map(); // simple name -> full named path (with dots)

for (const raw of lines) {
  if (!raw) continue;
  const parts = raw.replace(/^\t+/, '').split('\t');
  if (parts[0] !== 'c' || parts.length < 4) continue;
  const full = parts[3].replace(/\//g, '.');
  const simple = full.split('.').pop();
  if (!bySimple.has(simple)) bySimple.set(simple, full);
}

let src = readFileSync(target, 'utf8');
let fixed = 0;
let dropped = 0;

src = src.split('\n').map((line) => {
  const m = /^import (net\.minecraft[\w.]*);$/.exec(line.trim());
  if (!m) return line;
  const simple = m[1].split('.').pop();
  const full = bySimple.get(simple);
  if (!full) {
    dropped++;
    return line;
  }
  const replacement = 'import ' + full + ';';
  if (replacement !== line.trim()) fixed++;
  return replacement;
}).join('\n');

writeFileSync(target, src, 'utf8');
console.log(`imports fixed: ${fixed}, unknown: ${dropped}`);
