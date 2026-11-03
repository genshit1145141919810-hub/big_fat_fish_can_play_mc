// Throwaway probe: read a .litematic (gzip + NBT) and report the real structure.
// Used only to validate the Java reader's assumptions against a real file.
import { readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';

class Reader {
  constructor(buf) { this.b = buf; this.i = 0; }
  u8() { return this.b[this.i++]; }
  i8() { const v = this.b.readInt8(this.i); this.i += 1; return v; }
  i16() { const v = this.b.readInt16BE(this.i); this.i += 2; return v; }
  i32() { const v = this.b.readInt32BE(this.i); this.i += 4; return v; }
  i64() { const v = this.b.readBigInt64BE(this.i); this.i += 8; return v; }
  f32() { const v = this.b.readFloatBE(this.i); this.i += 4; return v; }
  f64() { const v = this.b.readDoubleBE(this.i); this.i += 8; return v; }
  str() { const n = this.b.readUInt16BE(this.i); this.i += 2; return this.b.toString('utf8', this.i, (this.i += n)); }
  bytes(n) { const s = this.b.subarray(this.i, this.i + n); this.i += n; return s; }
}

function readPayload(r, type) {
  switch (type) {
    case 1: return r.i8();
    case 2: return r.i16();
    case 3: return r.i32();
    case 4: return r.i64();
    case 5: return r.f32();
    case 6: return r.f64();
    case 7: { const n = r.i32(); return Int8Array.from(r.bytes(n)); }
    case 8: return r.str();
    case 9: {
      const elem = r.i8();
      const n = r.i32();
      const arr = [];
      for (let k = 0; k < n; k++) arr.push(readPayload(r, elem));
      return arr;
    }
    case 10: {
      const obj = {};
      for (;;) {
        const t = r.i8();
        if (t === 0) break;
        const name = r.str();
        obj[name] = readPayload(r, t);
      }
      return obj;
    }
    case 11: { const n = r.i32(); const a = []; for (let k = 0; k < n; k++) a.push(r.i32()); return a; }
    case 12: { const n = r.i32(); const a = []; for (let k = 0; k < n; k++) a.push(r.i64()); return a; }
    default: throw new Error('bad tag ' + type + ' at ' + r.i);
  }
}

export function readNbt(file) {
  const r = new Reader(gunzipSync(readFileSync(file)));
  const type = r.i8();
  const rootName = r.str();
  return { name: rootName, value: readPayload(r, type) };
}

const file = process.argv[2];
const { name, value } = readNbt(file);
console.log('root =', name, '| keys =', Object.keys(value).join(', '));
const meta = value.Metadata ?? {};
console.log('Version =', value.Version, '| DataVersion =', value.MinecraftDataVersion);
console.log('Metadata =', JSON.stringify(meta, (k, v) => (typeof v === 'bigint' ? Number(v) : v)));
for (const [regionName, region] of Object.entries(value.Regions ?? {})) {
  const size = region.Size ?? {};
  const vol = Math.abs(size.x * size.y * size.z);
  const palette = region.BlockStatePalette ?? [];
  const longs = region.BlockStates ?? [];
  // Solve bitsPerEntry from volume & long count. Litematica packs bits as a
  // continuous stream (values DO span long boundaries): longs = ceil(vol*bits/64).
  let bits = 0;
  for (let b = 1; b <= 32; b++) {
    if (Math.ceil((vol * b) / 64) === longs.length) { bits = b; break; }
  }
  console.log(`region ${regionName}: keys=${Object.keys(region).join(',')}`);
  console.log(`region ${regionName}: size=${size.x}x${size.y}x${size.z} vol=${vol} palette=${palette.length} longs=${longs.length} bits=${bits}`);
  if (!bits) { console.log('  !! could not solve bitsPerEntry'); continue; }
  const counts = new Map();
  const unnamed = new Map();
  let air = 0;
  let maxIdx = 0;
  let outOfRange = 0;
  const oorSample = [];
  const mask = (1n << BigInt(bits)) - 1n;
  for (let idx = 0; idx < vol; idx++) {
    const bitIndex = idx * bits;
    const longIdx = bitIndex >>> 6;
    const offset = bitIndex & 63;
    let raw = longs[longIdx] >> BigInt(offset);
    if (offset + bits > 64) raw |= longs[longIdx + 1] << BigInt(64 - offset);
    const v = Number(raw & mask);
    if (v >= palette.length) { outOfRange++; if (oorSample.length < 20) oorSample.push(v); }
    if (v > maxIdx) maxIdx = v;
    const entry = palette[v];
    const n = entry?.Name ?? '?';
    if (n === '?') unnamed.set(JSON.stringify(entry), (unnamed.get(JSON.stringify(entry)) ?? 0) + 1);
    if (n === 'minecraft:air' || n === 'minecraft:cave_air' || n === 'minecraft:void_air') { air++; continue; }
    counts.set(n, (counts.get(n) ?? 0) + 1);
  }
  if (unnamed.size) console.log('  unnamed palette entries:', [...unnamed.entries()].slice(0, 5));
  console.log('  palette=' + palette.length + ' maxIndex=' + maxIdx + ' outOfRange=' + outOfRange
    + ' sampleOOR=' + JSON.stringify(oorSample.slice(0, 10)));
  const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  console.log('  non-air =', [...counts.values()].reduce((a, b) => a + b, 0), '| air =', air);
  console.log('  top:', sorted.slice(0, 8).map(([n, c]) => `${n}=${c}`).join(', '));
  console.log('  sample palette[0..3] =', JSON.stringify(palette.slice(0, 4)));
}
