import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { buildSite } from '../build.mjs';
import { verifySite } from '../verify.mjs';

const THIS_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(THIS_DIR, '../..');

function makePngBytes(width, height, totalBytes = 64) {
  const size = Math.max(totalBytes, 24);
  const buf = Buffer.alloc(size, 0);
  // PNG signature
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(buf, 0);
  buf.writeUInt32BE(13, 8);
  Buffer.from('IHDR', 'ascii').copy(buf, 12);
  buf.writeUInt32BE(width, 16);
  buf.writeUInt32BE(height, 20);
  return buf;
}

function createFixtureWorkspace() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'spatialfin-site-test-'));

  fs.mkdirSync(path.join(dir, 'buildSrc/src/main/kotlin'), { recursive: true });
  fs.writeFileSync(
    path.join(dir, 'buildSrc/src/main/kotlin/Versions.kt'),
    'object Versions {\n  const val APP_CODE = 172\n  const val APP_NAME = "2.7.71"\n  const val MIN_SDK = 31\n}\n',
  );

  fs.mkdirSync(path.join(dir, 'companion/wear'), { recursive: true });
  fs.writeFileSync(
    path.join(dir, 'companion/wear/build.gradle.kts'),
    'android {\n  defaultConfig {\n    minSdk = 33\n  }\n}\n',
  );

  fs.mkdirSync(path.join(dir, 'docs/web'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'docs/CNAME'), 'spatialfin.martinez.fyi\n');
  fs.writeFileSync(path.join(dir, 'docs/web/index.html'), '<!DOCTYPE html><title>WebXR</title>');
  fs.writeFileSync(path.join(dir, 'docs/web/manifest.webmanifest'), '{}');
  fs.mkdirSync(path.join(dir, 'docs/images'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'docs/images/SpatialFin-banner.png'), 'banner');

  fs.mkdirSync(path.join(dir, 'site/data'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'site/templates'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'site/assets'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'site/images'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'site/examples/peertube'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'site/pages/guides'), { recursive: true });

  fs.copyFileSync(path.join(REPO_ROOT, 'site/data/site.json'), path.join(dir, 'site/data/site.json'));
  fs.copyFileSync(path.join(REPO_ROOT, 'site/templates/shell.html'), path.join(dir, 'site/templates/shell.html'));
  fs.copyFileSync(path.join(REPO_ROOT, 'site/templates/redirect.html'), path.join(dir, 'site/templates/redirect.html'));
  fs.copyFileSync(path.join(REPO_ROOT, 'site/assets/style.css'), path.join(dir, 'site/assets/style.css'));
  fs.copyFileSync(path.join(REPO_ROOT, 'site/assets/site.js'), path.join(dir, 'site/assets/site.js'));
  fs.copyFileSync(
    path.join(REPO_ROOT, 'site/examples/peertube/manifest.json'),
    path.join(dir, 'site/examples/peertube/manifest.json'),
  );
  fs.copyFileSync(
    path.join(REPO_ROOT, 'site/examples/peertube/script.js'),
    path.join(dir, 'site/examples/peertube/script.js'),
  );

  fs.writeFileSync(path.join(dir, 'site/images/icon.png'), makePngBytes(128, 128));
  fs.writeFileSync(path.join(dir, 'site/images/og-card.png'), makePngBytes(1200, 630));
  fs.writeFileSync(path.join(dir, 'site/images/sample.webp'), Buffer.alloc(1024, 0));

  const pages = [
    {
      kind: 'page',
      slug: '/',
      outputPath: 'index.html',
      zone: 'discovery',
      layout: 'landing',
      title: 'SpatialFin Test Home Page With Enough Characters',
      description: 'SpatialFin test landing page description that satisfies the seventy to one hundred sixty character metadata constraint cleanly.',
      schemaType: 'SoftwareApplication',
      fragmentPath: 'site/pages/index.html',
      ogImage: '/images/og-card.png',
    },
    {
      kind: 'page',
      slug: '/guides/playback-offline/',
      outputPath: 'guides/playback-offline/index.html',
      zone: 'guides',
      layout: 'docs',
      title: 'SpatialFin Test Docs Guide With Enough Characters',
      description: 'SpatialFin test guide page description that satisfies the seventy to one hundred sixty character metadata constraint here.',
      schemaType: 'HowTo',
      sidebarGroup: 'Guides',
      navLabel: 'Playback & Offline',
      prev: null,
      next: null,
      fragmentPath: 'site/pages/guides/playback-offline.html',
      ogImage: '/images/og-card.png',
    },
    {
      kind: 'redirect',
      slug: '/smart.html',
      outputPath: 'smart.html',
      redirectTo: '/guides/playback-offline/',
      noindex: true,
    },
    {
      kind: 'notFound',
      slug: '/404.html',
      outputPath: '404.html',
      layout: 'landing',
      title: 'Page Not Found — SpatialFin',
      description: 'Requested page was not found on this site.',
      noindex: true,
      fragmentPath: 'site/pages/404.html',
    },
  ];
  fs.writeFileSync(path.join(dir, 'site/data/pages.json'), JSON.stringify(pages, null, 2));

  // Fix nav targets inside fixture so all nav links resolve locally inside fixture
  fs.mkdirSync(path.join(dir, 'site/pages/guides'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'site/pages/companion'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'site/pages/plugins'), { recursive: true });

  fs.writeFileSync(
    path.join(dir, 'site/pages/index.html'),
    [
      '<h1>SpatialFin {{version}} (code {{appCode}})</h1>',
      '<section id="platforms"><h2>Platforms (Android {{minSdk}}, Wear {{wearMinSdk}})</h2>',
      '<img src="/images/sample.webp" width="640" height="360" alt="SpatialFin XR screenshot"></section>',
      '<section id="download"><h2>Download</h2><a href="/guides/playback-offline/#setup">Guide</a></section>',
    ].join('\n'),
  );

  fs.writeFileSync(
    path.join(dir, 'site/pages/guides/playback-offline.html'),
    [
      '<h1>Playback Guide</h1>',
      '<h2>Overview</h2>',
      '<h2>Overview</h2>',
      '<h3 id="setup">Setup</h3>',
      '<p>Literal code mustache: <code>{{unchangedInCode}}</code></p>',
      '<pre><code>{{include:examples/peertube/manifest.json}}</code></pre>',
      '<a href="/guides/playback-offline/#overview-2">Duplicate anchor</a>',
      '<a href="/companion/">Companion</a> <a href="/plugins/">Plugins</a> <a href="/guides/">Guides</a>',
    ].join('\n'),
  );

  fs.writeFileSync(path.join(dir, 'site/pages/404.html'), '<h1>Not Found</h1><p><a href="/">Home</a></p>');

  // Also strip missing /guides/, /companion/, /plugins/ nav failures by updating header links OR adding fixture pages
  const extraPages = [
    {
      kind: 'page',
      slug: '/guides/',
      outputPath: 'guides/index.html',
      zone: 'guides',
      layout: 'docs',
      title: 'SpatialFin Fixture Guides Index Page Title Here',
      description: 'SpatialFin fixture guides index description covering onboarding, playback, casting, Wear OS, and plugin documentation.',
      schemaType: 'WebSite',
      sidebarGroup: 'Guides',
      navLabel: 'Guides Index',
      fragmentPath: 'site/pages/guides/index.html',
      ogImage: '/images/og-card.png',
    },
    {
      kind: 'page',
      slug: '/companion/',
      outputPath: 'companion/index.html',
      zone: 'companion',
      layout: 'docs',
      title: 'SpatialFin Fixture Companion Hub Page Title Here',
      description: 'SpatialFin fixture companion hub description detailing Docker compose setup, SearXNG search, and Quick Connect brokering.',
      schemaType: 'HowTo',
      sidebarGroup: 'Ecosystem',
      navLabel: 'Companion Hub',
      fragmentPath: 'site/pages/companion/index.html',
      ogImage: '/images/og-card.png',
    },
    {
      kind: 'page',
      slug: '/plugins/',
      outputPath: 'plugins/index.html',
      zone: 'plugins',
      layout: 'docs',
      title: 'SpatialFin Fixture Plugins Page Title Here Ok',
      description: 'SpatialFin fixture plugin guide description covering QuickJS sandboxing, Grayjay compatibility, and PeerTube code fixtures.',
      schemaType: 'TechArticle',
      sidebarGroup: 'Ecosystem',
      navLabel: 'Plugins',
      fragmentPath: 'site/pages/plugins/index.html',
      ogImage: '/images/og-card.png',
    },
  ];
  fs.writeFileSync(path.join(dir, 'site/data/pages.json'), JSON.stringify([...pages, ...extraPages], null, 2));
  fs.writeFileSync(path.join(dir, 'site/pages/guides/index.html'), '<h1>Guides</h1><h2>List</h2>');
  fs.writeFileSync(path.join(dir, 'site/pages/companion/index.html'), '<h1>Companion</h1><h2>Overview</h2>');
  fs.writeFileSync(path.join(dir, 'site/pages/plugins/index.html'), '<h1>Plugins</h1><h2>Example</h2><pre><code>{{include:examples/peertube/script.js}}</code></pre>');

  return dir;
}

test('happy path builds and verifies isolated fixture workspace', async (t) => {
  const dir = createFixtureWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  const built = buildSite({ rootDir: dir, outDir: '_site' });
  assert.equal(built.version, '2.7.71');

  const check = await verifySite({ rootDir: dir, outDir: '_site' });
  assert.deepEqual(check.failures, []);
  assert.equal(check.ok, true);

  const landingHtml = fs.readFileSync(path.join(dir, '_site/index.html'), 'utf8');
  assert.match(landingHtml, /SpatialFin 2\.7\.71 \(code 172\)/);
  assert.match(landingHtml, /Android 31, Wear 33/);

  const guideHtml = fs.readFileSync(path.join(dir, '_site/guides/playback-offline/index.html'), 'utf8');
  assert.match(guideHtml, /id="overview"/);
  assert.match(guideHtml, /id="overview-2"/);
  assert.match(guideHtml, /\{\{unchangedInCode\}\}/);
  assert.match(guideHtml, /&quot;id&quot;: &quot;peertube-example&quot;/);
});

test('buildSite aborts on unknown placeholder outside code blocks', (t) => {
  const dir = createFixtureWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  fs.writeFileSync(path.join(dir, 'site/pages/index.html'), '<h1>Bad {{unknownToken}}</h1>');
  assert.throws(() => buildSite({ rootDir: dir }), /Unknown template placeholder/);
});

test('buildSite blocks path traversal in include directives', (t) => {
  const dir = createFixtureWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  fs.writeFileSync(path.join(dir, 'site/pages/index.html'), '<h1>Home</h1><pre><code>{{include:examples/../../docs/CNAME}}</code></pre>');
  assert.throws(() => buildSite({ rootDir: dir }), /Unsafe include path/);
});

test('buildSite fails on malformed Versions.kt', (t) => {
  const dir = createFixtureWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  fs.writeFileSync(path.join(dir, 'buildSrc/src/main/kotlin/Versions.kt'), 'object Versions {}');
  assert.throws(() => buildSite({ rootDir: dir }), /Malformed Versions\.kt/);
});

test('buildSite refuses protected output paths (docs/web, docs/CNAME, docs, site, root)', (t) => {
  const dir = createFixtureWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  for (const target of ['docs/web', 'docs/CNAME', 'docs', 'site', '.']) {
    assert.throws(() => buildSite({ rootDir: dir, outDir: target }), /Refusing protected output path/);
  }
});

test('redirects and 404.html are excluded from sitemap.xml and redirects omit Umami', (t) => {
  const dir = createFixtureWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  buildSite({ rootDir: dir });
  const sitemap = fs.readFileSync(path.join(dir, '_site/sitemap.xml'), 'utf8');
  assert.doesNotMatch(sitemap, /smart\.html/);
  assert.doesNotMatch(sitemap, /404\.html/);

  const redirectHtml = fs.readFileSync(path.join(dir, '_site/smart.html'), 'utf8');
  assert.doesNotMatch(redirectHtml, /umami\.martinez\.fyi/);
});

test('verifySite detects undefined CSS var(--missing)', async (t) => {
  const dir = createFixtureWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  buildSite({ rootDir: dir });
  fs.appendFileSync(path.join(dir, '_site/assets/style.css'), '\n.bad { color: var(--missing); }\n');

  const res = await verifySite({ rootDir: dir });
  assert.equal(res.ok, false);
  assert.ok(res.failures.some((f) => f.includes('var(--missing)')));
});

test('verifySite detects heading skip while ignoring h4 inside code blocks', async (t) => {
  const dir = createFixtureWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  // Ignore <h4> inside <pre><code>
  fs.writeFileSync(
    path.join(dir, 'site/pages/guides/index.html'),
    '<h1>Guides</h1><h2>List</h2><pre><code>&lt;h4&gt;Ignored&lt;/h4&gt;<h4>Also ignored</h4></code></pre>',
  );
  buildSite({ rootDir: dir });
  const passRes = await verifySite({ rootDir: dir });
  assert.equal(passRes.ok, true);

  // Real heading skip outside code
  const idxPath = path.join(dir, '_site/guides/index.html');
  fs.writeFileSync(idxPath, fs.readFileSync(idxPath, 'utf8').replace(/<h2[^>]*>List<\/h2>/, '<h2>List</h2><h4>Skipped</h4>'));
  const failRes = await verifySite({ rootDir: dir });
  assert.equal(failRes.ok, false);
  assert.ok(failRes.failures.some((f) => f.includes('Heading level skip from <h2> to <h4>')));
});

test('verifySite detects broken internal link and broken #anchor', async (t) => {
  const dir = createFixtureWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  buildSite({ rootDir: dir });
  const guideOut = path.join(dir, '_site/guides/index.html');
  fs.writeFileSync(
    guideOut,
    fs.readFileSync(guideOut, 'utf8').replace(/<h2[^>]*>List<\/h2>/, '<h2>List</h2><a href="/missing-page/">Missing</a><a href="#no-such-id">Bad anchor</a>'),
  );

  const res = await verifySite({ rootDir: dir });
  assert.equal(res.ok, false);
  assert.ok(res.failures.some((f) => f.includes('Broken internal reference "/missing-page/"')));
  assert.ok(res.failures.some((f) => f.includes('Broken #anchor target "#no-such-id"')));
});

test('verifySite enforces WebP byte cap, OG PNG size cap, and PNG IHDR minimum dimensions', async (t) => {
  const dir = createFixtureWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  buildSite({ rootDir: dir });
  fs.writeFileSync(path.join(dir, '_site/images/sample.webp'), Buffer.alloc(360 * 1024, 0));
  fs.writeFileSync(path.join(dir, '_site/images/og-card.png'), makePngBytes(800, 400, 510 * 1024));

  const res = await verifySite({ rootDir: dir });
  assert.equal(res.ok, false);
  assert.ok(res.failures.some((f) => f.includes('sample.webp exceeds 350 KB budget')));
  assert.ok(res.failures.some((f) => f.includes('800x400')));
  assert.ok(res.failures.some((f) => f.includes('og-card.png exceeds 500 KB limit')));
});

test('verifySite flags orphan image under _site/images/', async (t) => {
  const dir = createFixtureWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  buildSite({ rootDir: dir });
  fs.writeFileSync(path.join(dir, '_site/images/orphan.webp'), Buffer.alloc(256, 0));

  const res = await verifySite({ rootDir: dir });
  assert.equal(res.ok, false);
  assert.ok(res.failures.some((f) => f.includes('Unreferenced image file') && f.includes('orphan.webp')));
});

test('verifySite detects invalid PeerTube manifest.json or script.js syntax error', async (t) => {
  const dir = createFixtureWorkspace();
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  buildSite({ rootDir: dir });
  fs.writeFileSync(path.join(dir, 'site/examples/peertube/manifest.json'), '{ "id": "only-id" }');
  fs.writeFileSync(path.join(dir, 'site/examples/peertube/script.js'), 'function broken( {');

  const res = await verifySite({ rootDir: dir });
  assert.equal(res.ok, false);
  assert.ok(res.failures.some((f) => f.includes('PeerTube manifest.json missing required fields')));
  assert.ok(res.failures.some((f) => f.includes('JavaScript syntax check failed')));
});

test('real repository Phase 1 schemas, CSS custom properties, JS, and PeerTube fixtures are valid', () => {
  const site = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'site/data/site.json'), 'utf8'));
  assert.equal(site.origin, 'https://spatialfin.martinez.fyi');

  const pages = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'site/data/pages.json'), 'utf8'));
  assert.equal(pages.length, 14);
  const canonical = pages.filter((p) => p.kind === 'page');
  assert.equal(canonical.length, 10);
  for (const p of canonical) {
    assert.ok(p.title.length >= 20 && p.title.length <= 65, `${p.slug} title len ${p.title.length}`);
    assert.ok(p.description.length >= 70 && p.description.length <= 160, `${p.slug} desc len ${p.description.length}`);
  }

  const cssText = fs.readFileSync(path.join(REPO_ROOT, 'site/assets/style.css'), 'utf8');
  const defined = new Set();
  for (const block of cssText.matchAll(/:root\s*\{([\s\S]*?)\}/g)) {
    for (const d of block[1].matchAll(/(--[A-Za-z0-9_-]+)\s*:/g)) defined.add(d[1]);
  }
  for (const u of cssText.matchAll(/var\(\s*(--[A-Za-z0-9_-]+)/g)) {
    assert.ok(defined.has(u[1]), `Undefined CSS variable in site/assets/style.css: ${u[1]}`);
  }

  execFileSync(process.execPath, ['--check', path.join(REPO_ROOT, 'site/assets/site.js')], { stdio: 'pipe' });
  execFileSync(process.execPath, ['--check', path.join(REPO_ROOT, 'site/examples/peertube/script.js')], { stdio: 'pipe' });

  const manifest = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'site/examples/peertube/manifest.json'), 'utf8'));
  assert.equal(manifest.id, 'peertube-example');
  assert.deepEqual(manifest.capabilities, ['home', 'search', 'resolve']);
});
