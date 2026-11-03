// Mirror of ChatCoordinator's parsing rules, used to sanity-check the strings a
// server plugin can produce WITHOUT booting Minecraft. Keep in sync with the Java.
const ANGLE = /^<([A-Za-z0-9_]{1,16})>\s+([\s\S]*)$/;
const COLON = /^([A-Za-z0-9_]{1,16})\s*[:：»>]\s*([\s\S]*)$/;
const DECORATION = /^(?:\[[^\]]{0,40}\]|\([^)]{0,40}\)|【[^】]{0,40}】|〔[^〕]{0,40}〕|<[^>]{0,40}>)\s*/;
const ZERO = /[\u200B-\u200D\uFEFF]/g;
const SEP = ':：,，、»>。！？!?';

function normalize(raw) {
  return raw.replace(ZERO, '').replace(/\uFF20/g, '@').replace(/\u00A0/g, ' ').trim();
}
function boundary(c) {
  return /\s/.test(c) || SEP.includes(c);
}
function stripDecorations(text) {
  let result = text;
  for (let i = 0; i < 6; i++) {
    const m = DECORATION.exec(result);
    if (!m || m[0].length === 0) break;
    const next = result.slice(m[0].length);
    if (!next.trim()) break;
    result = next;
  }
  return result.trim();
}
function parse(raw, cfg) {
  const clean = normalize(raw);
  let m = ANGLE.exec(clean);
  if (m) return { sender: m[1], body: m[2].trim() };
  m = COLON.exec(clean);
  if (m) return { sender: m[1], body: m[2].trim() };
  const undec = stripDecorations(clean);
  if (undec !== clean) {
    m = ANGLE.exec(undec) || COLON.exec(undec);
    if (m) return { sender: m[1], body: m[2].trim() };
  }
  const owner = (cfg.ownerName || '').trim();
  if (owner) {
    let idx = clean.toLowerCase().indexOf(owner.toLowerCase());
    while (idx >= 0) {
      const after = clean.slice(idx + owner.length).trim();
      if (after && ':：»>'.includes(after[0])) {
        const body = after.slice(1).trim();
        if (body) return { sender: owner, body };
      }
      idx = clean.toLowerCase().indexOf(owner.toLowerCase(), idx + owner.length);
    }
  }
  const bot = (cfg.botName || 'XiaoD').trim();
  if (containsMention(clean, bot)) return { sender: owner || '某人', body: undec };
  return null;
}
function containsMention(text, name) {
  const lower = text.toLowerCase();
  const target = name.toLowerCase();
  let idx = lower.indexOf('@' + target);
  while (idx >= 0) {
    const end = idx + 1 + target.length;
    const leftOk = idx === 0 || boundary(text[idx - 1]);
    const rightOk = end >= text.length || boundary(text[end]);
    if (leftOk && rightOk) return true;
    idx = lower.indexOf('@' + target, idx + 1);
  }
  return false;
}
function stripLeadingSeparators(text) {
  let rest = (text || '').trim();
  while (rest && SEP.includes(rest[0])) rest = rest.slice(1).trim();
  return rest;
}
function stripTrigger(raw, name) {
  if (!raw || !name) return null;
  const text = raw.trim();
  const lower = text.toLowerCase();
  const target = name.toLowerCase();
  const at = lower.indexOf('@' + target);
  if (at >= 0) {
    const end = at + 1 + target.length;
    const leftOk = at === 0 || boundary(text[at - 1]);
    const rightOk = end >= text.length || boundary(text[end]);
    if (leftOk && rightOk) {
      const before = text.slice(0, at).trim();
      const after = text.slice(end).trim();
      const payload = stripLeadingSeparators(after || before);
      return payload || null;
    }
  }
  if (lower.startsWith(target)) {
    const rest = text.slice(name.length);
    if (rest && !boundary(rest[0])) return null;
    const payload = stripLeadingSeparators(rest);
    return payload || null;
  }
  return null;
}
function stripLoose(raw, name) {
  const text = (raw || '').trim();
  const lower = text.toLowerCase();
  const target = name.toLowerCase();
  let idx = lower.indexOf(target);
  while (idx >= 0) {
    const end = idx + target.length;
    const leftOk = idx === 0 || boundary(text[idx - 1]);
    const rightOk = end >= text.length || boundary(text[end]);
    if (leftOk && rightOk) {
      const before = text.slice(0, idx).trim();
      const after = text.slice(end).trim();
      return (after || before) || null;
    }
    idx = lower.indexOf(target, idx + 1);
  }
  return null;
}

const cfg = { botName: 'Minecoinft', ownerName: 'Leeskykit', self: 'Minecoinft' };
const cases = [
  ['Leeskykit: @Minecoinft 帮我砍点树', true],
  ['[LVL 3] Leeskykit: @Minecoinft 帮我砍点树', true],
  ['【世界】Leeskykit » @Minecoinft 帮我砍点树', true],
  ['[世界] Leeskykit » @Minecoinft 帮我砍点树', true],
  ['Leeskykit: 帮我砍点树 @Minecoinft', true],
  ['<Leeskykit> @Minecoinft 砍点树', true],
  ['Leeskykit: Minecoinft 帮我砍点树', true],
  ['Leeskykit: @Minecoinft，帮我砍点树', true],
  ['Leeskykit: 有个 MinecoinftFan 好烦', false],
  ['[服务器] 欢迎来到本服', false],
  ['Leeskykit: 今天天气不错', false],
];

let ok = true;
for (const [line, expect] of cases) {
  const parsed = parse(line, cfg);
  let hit = false;
  let payload = null;
  if (parsed && parsed.sender.toLowerCase() !== cfg.self.toLowerCase()) {
    payload = stripTrigger(parsed.body, cfg.botName);
    if (!payload) payload = null;
    hit = payload !== null;
  }
  const loose = parsed ? stripLoose(parsed.body, cfg.botName) : null;
  const pass = hit === expect;
  if (!pass) ok = false;
  console.log(
    (pass ? 'PASS' : 'FAIL') + ' | ' + JSON.stringify(line) +
    ' -> sender=' + (parsed ? parsed.sender : '-') +
    ' body=' + (parsed ? JSON.stringify(parsed.body) : '-') +
    ' trigger=' + JSON.stringify(payload) +
    ' loose=' + JSON.stringify(loose) +
    ' expected=' + expect
  );
}
console.log(ok ? '\nALL PASS' : '\nSOME FAILED');
process.exit(ok ? 0 : 1);
