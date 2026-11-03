const fs = require('fs');
const path = require('path');

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, out);
    } else if (entry.name.endsWith('.java')) {
      out.push(full);
    }
  }
  return out;
}

const files = walk('src/main/java');
const literal = /"((?:[^"\\]|\\.)*)"/g;
const counts = new Map();
for (const file of files) {
  const text = fs.readFileSync(file, 'utf8');
  let m;
  while ((m = literal.exec(text)) !== null) {
    const value = m[1];
    if (/[\u4e00-\u9fff]/.test(value)) {
      counts.set(value, (counts.get(value) || 0) + 1);
    }
  }
}

const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1]);
console.log('distinct Chinese literals:', sorted.length);
const out = sorted.map(([text, count]) => ({ count, text }));
fs.writeFileSync('chinese-literals.json', JSON.stringify(out, null, 1), 'utf8');
for (const row of out.slice(0, 20)) {
  console.log(String(row.count).padStart(3) + 'x  ' + row.text.slice(0, 70));
}
