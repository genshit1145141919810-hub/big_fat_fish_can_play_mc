// Find downloadable .litematic files on GitHub (no auth, no deps).
// Usage: node gh-find-schematic.mjs "query" [maxRepos]
const PROXY = process.env.HTTP_PROXY || 'http://127.0.0.1:7890';

async function api(path) {
  const res = await fetch('https://api.github.com' + path, {
    headers: { 'User-Agent': 'dsh-agent', Accept: 'application/vnd.github+json' },
    // Node's fetch ignores HTTP_PROXY, so route through a tiny tunnel by hand.
  });
  if (!res.ok) throw new Error(path + ' -> ' + res.status);
  return res.json();
}

const query = process.argv[2] || 'litematica schematics';
const maxRepos = Number(process.argv[3] || 10);

const search = await api(`/search/repositories?q=${encodeURIComponent(query)}&sort=stars&per_page=${maxRepos}`);
const found = [];
for (const repo of search.items) {
  try {
    const tree = await api(`/repos/${repo.full_name}/git/trees/HEAD?recursive=1`);
    const hits = (tree.tree || []).filter((entry) => entry.path.toLowerCase().endsWith('.litematic'));
    for (const hit of hits.slice(0, 6)) {
      found.push({
        repo: repo.full_name,
        path: hit.path,
        size: hit.size,
        raw: `https://raw.githubusercontent.com/${repo.full_name}/${tree.sha}/${hit.path}`,
      });
    }
  } catch (e) {
    // skip repos whose tree is huge or unavailable
  }
}
found.sort((a, b) => a.size - b.size);
for (const item of found) {
  console.log(`${item.size}\t${item.repo}/${item.path}`);
}
console.log(`--- ${found.length} files ---`);
if (process.argv[4] === '--urls') {
  for (const item of found) console.log(item.raw);
}
