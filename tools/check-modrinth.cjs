const fs = require('fs');
const path = require('path');

const pageFile = path.join(process.env.TEMP, 'mr-page.html');
const apiFile = path.join(process.env.TEMP, 'mr-project.json');

if (fs.existsSync(pageFile)) {
  const html = fs.readFileSync(pageFile, 'utf8');
  const title = html.match(/<title>([^<]*)<\/title>/);
  console.log('page bytes:', html.length);
  console.log('title:', title ? title[1].trim() : '(none)');
  const desc = html.match(/<meta name="description" content="([^"]*)"/);
  console.log('meta desc:', desc ? desc[1].slice(0, 160) : '(none)');
  const og = html.match(/<meta property="og:title" content="([^"]*)"/);
  console.log('og title:', og ? og[1] : '(none)');
  const notFound = html.includes('not found') || html.includes('404');
  console.log('mentions 404:', notFound);
}

if (fs.existsSync(apiFile)) {
  const raw = fs.readFileSync(apiFile, 'utf8').trim();
  console.log('api bytes:', raw.length);
  if (raw.length > 0) {
    try {
      const p = JSON.parse(raw);
      console.log(JSON.stringify({
        title: p.title,
        slug: p.slug,
        summary: p.summary,
        type: p.project_type,
        client_side: p.client_side,
        server_side: p.server_side,
        loaders: p.loaders,
        game_versions: p.game_versions,
        categories: p.categories,
        license: p.license && p.license.id,
        status: p.status,
        published: p.published,
      }, null, 1));
    } catch (e) {
      console.log('api raw:', raw.slice(0, 300));
    }
  } else {
    console.log('api returned empty body');
  }
}
