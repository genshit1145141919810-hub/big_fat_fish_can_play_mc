// Fix fully-qualified references like `net.minecraft.Text` / `net.minecraft.BlockPos`
// that came out of the intermediary sources (`net.minecraft.class_2561`).
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

const bySimple = new Map();
for (const raw of readFileSync(findMappings('1.21.11'), 'utf8').split('\n')) {
  if (!raw) continue;
  const parts = raw.replace(/^\t+/, '').split('\t');
  if (parts[0] !== 'c' || parts.length < 4) continue;
  const full = parts[3].replace(/\//g, '.');
  const simple = full.split('.').pop();
  if (!bySimple.has(simple)) bySimple.set(simple, full);
}

let src = readFileSync(target, 'utf8');
let fixes = 0;
const unknown = new Set();

src = src.replace(/\bnet\.minecraft\.([A-Z][A-Za-z0-9_]*)\b/g, (whole, simple) => {
  const full = bySimple.get(simple);
  if (!full) {
    unknown.add(whole);
    return whole;
  }
  if (full !== whole) fixes++;
  return full;
});

writeFileSync(target, src, 'utf8');
console.log(`qualified refs fixed: ${fixes}`);
if (unknown.size) console.log('unknown:', [...unknown].slice(0, 10).join(', '));
