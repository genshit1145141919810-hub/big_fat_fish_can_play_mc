const fs = require('fs');
const rows = JSON.parse(fs.readFileSync('chinese-literals.json', 'utf8'));
const complete = rows.filter((r) => {
  const t = r.text;
  if (t.length < 5) return false;
  if (t.trim() !== t) return false;               // fragments
  if (/[：:（(]$/.test(t)) return false;            // prefixes
  if (/^[）)，,、]/.test(t)) return false;
  if (t.replace(/§./g, '').trim().length < 4) return false;
  return true;
});
complete.sort((a, b) => a.text.length - b.text.length);
console.log('complete candidates:', complete.length);
fs.writeFileSync('chinese-complete.json', JSON.stringify(complete, null, 1), 'utf8');
for (const r of complete.slice(0, 60)) {
  console.log(r.text.replace(/\n/g, '\\n').slice(0, 80));
}
