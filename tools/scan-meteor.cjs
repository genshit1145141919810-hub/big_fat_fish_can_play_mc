// Parse Meteor module classes: category, module name, description (from constant pool).
const fs = require('fs');
const path = require('path');

const root = process.argv[2];
if (!root) {
  console.error('usage: node scan-meteor.mjs <extracted meteor dir>');
  process.exit(2);
}

const base = path.join(root, 'meteordevelopment', 'meteorclient', 'systems', 'modules');
const categories = fs.readdirSync(base).filter((d) => fs.statSync(path.join(base, d)).isDirectory());

function utf8Strings(buf) {
  // class file: u4 magic, u2 minor, u2 major, u2 cpCount, then entries
  const count = buf.readUInt16BE(8);
  let off = 10;
  const out = [];
  for (let i = 1; i < count; i++) {
    const tag = buf[off++];
    switch (tag) {
      case 1: {
        const len = buf.readUInt16BE(off);
        off += 2;
        out.push(buf.toString('utf8', off, off + len));
        off += len;
        break;
      }
      case 7: case 8: case 16: case 19: case 20:
        off += 2;
        break;
      case 15:
        off += 3;
        break;
      case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
        off += 4;
        break;
      case 5: case 6:
        off += 8;
        i++; // takes two slots
        break;
      default:
        throw new Error('unknown constant pool tag ' + tag + ' at ' + (off - 1));
    }
  }
  return out;
}

function looksLikeDescription(s) {
  if (!s || s.length < 12 || s.length > 200) return false;
  if (/[/;()$<>\\]/.test(s)) return false;
  if (!/^[A-Za-z]/.test(s)) return false;
  const words = s.split(/\s+/);
  return words.length >= 3;
}

const rows = [];
for (const category of categories) {
  for (const file of fs.readdirSync(path.join(base, category))) {
    if (!file.endsWith('.class') || file.includes('$')) continue;
    const buf = fs.readFileSync(path.join(base, category, file));
    let strings;
    try {
      strings = utf8Strings(buf);
    } catch (e) {
      continue;
    }
    const name = file.replace(/\.class$/, '');
    const candidates = strings.filter(looksLikeDescription);
    // 描述通常是带空格的句子；取最长的那条
    candidates.sort((a, b) => b.length - a.length);
    const description = candidates[0] || '';
    rows.push({ category, name, description });
  }
}

rows.sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name));
fs.writeFileSync('meteor-modules.json', JSON.stringify(rows, null, 1), 'utf8');
console.log('modules:', rows.length);
const byCat = {};
for (const r of rows) byCat[r.category] = (byCat[r.category] || 0) + 1;
console.log(JSON.stringify(byCat));
for (const r of rows.slice(0, 12)) console.log(`${r.category}/${r.name}: ${r.description}`);
