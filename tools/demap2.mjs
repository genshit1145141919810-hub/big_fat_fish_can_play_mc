// Reverse-map intermediary names back to yarn names using Loom's mappings.tiny (tiny v2):
//   c <official> <intermediary> <named>
//   m <descriptor> <intermediary> <named>
//   f <descriptor> <intermediary> <named>
import { readFileSync, writeFileSync, existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const CACHE = 'C:/Users/guane/.gradle/caches/fabric-loom';
const target = process.argv[2];
const outPath = process.argv[3] || target;

function findMappings(version) {
  const dir = join(CACHE, version);
  if (!existsSync(dir)) throw new Error('no loom cache for ' + version);
  for (const sub of readdirSync(dir)) {
    const file = join(dir, sub, 'mappings.tiny');
    if (existsSync(file)) return file;
  }
  throw new Error('mappings.tiny not found under ' + dir);
}

const tinyPath = findMappings('1.21.11');
const lines = readFileSync(tinyPath, 'utf8').split('\n');
console.log('mappings:', tinyPath);

const classes = new Map();
const methods = new Map();
const fields = new Map();

for (let i = 1; i < lines.length; i++) {
  const line = lines[i];
  if (!line) continue;
  const parts = line.split('\t');
  const kind = parts[0];
  if (kind === 'c' && parts.length >= 4) {
    classes.set(parts[2], parts[3].split('/').pop());
  } else if ((kind === 'm' || kind === 'f') && parts.length >= 4) {
    const map = kind === 'm' ? methods : fields;
    const inter = parts[2];
    const named = parts[3];
    if (!map.has(inter)) map.set(inter, named);
    else if (map.get(inter) !== named) map.set(inter, null);
  }
}

console.log(`parsed: ${classes.size} classes, ${methods.size} methods, ${fields.size} fields`);

let src = readFileSync(target, 'utf8');
const before = src;
let classHits = 0;
let memberHits = 0;
const missed = new Set();

src = src.replace(/\bclass_(\d+)\b/g, (whole, digits) => {
  const named = classes.get('class_' + digits);
  if (!named) {
    missed.add(whole);
    return whole;
  }
  classHits++;
  return named;
});

src = src.replace(/\b(method|field)_(\d+)\b/g, (whole) => {
  const map = whole.startsWith('method_') ? methods : fields;
  const named = map.get(whole);
  if (!named) {
    missed.add(whole);
    return whole;
  }
  memberHits++;
  return named;
});

writeFileSync(outPath, src, 'utf8');
console.log(`rewrote ${classHits} classes, ${memberHits} members; unmapped=${missed.size}`);
if (missed.size) {
  console.log('unmapped sample:', [...missed].slice(0, 12).join(', '));
}
console.log('changed:', before !== src);
