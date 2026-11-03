// tiny v2, 3 namespaces (official / intermediary / named):
//   c                              <official/path> <intermediary/path> <named/path>
//   \tm <desc> <officialName>      <intermediaryName>  <namedName>
//   \tf <desc> <officialName>      <intermediaryName>  <namedName>
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
const classes = new Map();
const members = new Map();

for (const raw of lines) {
  if (!raw) continue;
  const parts = raw.replace(/^\t+/, '').split('\t');
  const kind = parts[0];
  if (kind === 'c' && parts.length >= 4) {
    classes.set(parts[2].split('/').pop(), parts[3].split('/').pop());
  } else if ((kind === 'm' || kind === 'f') && parts.length >= 5) {
    const inter = parts[3];
    const named = parts[4];
    if (inter && named) {
      if (!members.has(inter)) members.set(inter, named);
      else if (members.get(inter) !== named) members.set(inter, null);
    }
  }
}
console.log(`parsed: ${classes.size} classes, ${members.size} members`);

let src = readFileSync(target, 'utf8');
const before = src;
let classHits = 0;
let memberHits = 0;
const missed = new Set();

src = src.replace(/\bclass_(\d+)\b/g, (whole) => {
  const named = classes.get(whole);
  if (!named) {
    missed.add(whole);
    return whole;
  }
  classHits++;
  return named;
});

src = src.replace(/\b(method|field)_(\d+)\b/g, (whole) => {
  const named = members.get(whole);
  if (!named) {
    missed.add(whole);
    return whole;
  }
  memberHits++;
  return named;
});

writeFileSync(target, src, 'utf8');
console.log(`rewrote ${classHits} classes, ${memberHits} members; unmapped=${missed.size}`);
if (missed.size) console.log('unmapped:', [...missed].join(', '));
console.log('changed:', before !== src);
