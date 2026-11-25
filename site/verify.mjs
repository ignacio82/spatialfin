import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import { createRequire } from 'node:module';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const MAX_WEBP_BYTES = 350 * 1024;
const MAX_TRAILER_POSTER_BYTES = 250 * 1024;
const MAX_OG_PNG_BYTES = 500 * 1024;
const MAX_TOTAL_IMAGES_BYTES = 15 * 1024 * 1024;

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.webmanifest': 'application/manifest+json',
  '.wasm': 'application/wasm',
};

function stripNonContentPreserveLines(html) {
  return html.replace(
    /<pre\b[\s\S]*?<\/pre>|<code\b[\s\S]*?<\/code>|<script\b[\s\S]*?<\/script>|<style\b[\s\S]*?<\/style>|<!--[\s\S]*?-->/gi,
    (m) => m.replace(/[^\r\n]/g, ' '),
  );
}

function stripPreCodeCommentsPreserveLines(html) {
  return html.replace(
    /<pre\b[\s\S]*?<\/pre>|<code\b[\s\S]*?<\/code>|<!--[\s\S]*?-->/gi,
    (m) => m.replace(/[^\r\n]/g, ' '),
  );
}

function lineOfOffset(text, offset) {
  let line = 1;
  for (let i = 0; i < offset && i < text.length; i += 1) {
    if (text[i] === '\n') line += 1;
  }
  return line;
}

function formatFailure(relFile, line, issue, fix) {
  const loc = line && line > 0 ? `${relFile}:${line}` : relFile;
  return `[FAIL] ${loc} — ${issue} — Fix: ${fix}`;
}

function parseAppName(absRoot) {
  const versionsPath = path.resolve(absRoot, 'buildSrc/src/main/kotlin/Versions.kt');
  const txt = fs.readFileSync(versionsPath, 'utf8');
  const match = txt.match(/const\s+val\s+APP_NAME\s*=\s*"([^"]+)"/);
  if (!match) {
    throw new Error(`Could not parse APP_NAME from ${versionsPath}`);
  }
  return match[1].trim();
}

function collectFilesRecursive(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...collectFilesRecursive(full));
    } else if (entry.isFile()) {
      out.push(full);
    }
  }
  return out;
}

function parseRootCssVars(cssText) {
  const stripped = cssText.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\r\n]/g, ' '));
  const defined = new Set();
  for (const blockMatch of stripped.matchAll(/:root\s*\{([\s\S]*?)\}/g)) {
    for (const declMatch of blockMatch[1].matchAll(/(--[A-Za-z0-9_-]+)\s*:/g)) {
      defined.add(declMatch[1]);
    }
  }
  const usages = [];
  for (const useMatch of stripped.matchAll(/var\(\s*(--[A-Za-z0-9_-]+)/g)) {
    usages.push({
      name: useMatch[1],
      line: lineOfOffset(cssText, useMatch.index ?? 0),
    });
  }
  return { defined, usages };
}

function decodeBasicEntities(s) {
  return s
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'");
}

function extractTagAttr(tag, attrName) {
  const re = new RegExp(`\\b${attrName}\\s*=\\s*"([^"]*)"`, 'i');
  const m = tag.match(re);
  return m ? decodeBasicEntities(m[1]) : null;
}

function resolveOnDiskTarget(absOut, currentFileAbs, rawUrl, siteOrigin, failures, relCurrent, lineNum) {
  let clean = rawUrl.trim();
  if (!clean) return null;

  if (
    clean.startsWith('mailto:') ||
    clean.startsWith('tel:') ||
    clean.startsWith('data:') ||
    clean.startsWith('javascript:')
  ) {
    return null;
  }

  if (clean.startsWith('http://') || clean.startsWith('https://')) {
    if (clean === siteOrigin || clean.startsWith(`${siteOrigin}/`)) {
      clean = clean.slice(siteOrigin.length) || '/';
    } else {
      return null;
    }
  }

  const hashIdx = clean.indexOf('#');
  const fragment = hashIdx >= 0 ? clean.slice(hashIdx + 1) : '';
  const pathPart = hashIdx >= 0 ? clean.slice(0, hashIdx) : clean;
  const queryStripped = pathPart.split('?')[0];

  let targetFile;
  if (!queryStripped) {
    targetFile = currentFileAbs;
  } else if (queryStripped.startsWith('/')) {
    const sub = queryStripped.slice(1);
    const candidate = path.resolve(absOut, sub);
    if (!candidate.startsWith(absOut)) {
      failures.push(formatFailure(relCurrent, lineNum, `Link escapes output directory: ${rawUrl}`, 'Use a valid site-internal path.'));
      return null;
    }
    if (queryStripped.endsWith('/') || (fs.existsSync(candidate) && fs.statSync(candidate).isDirectory())) {
      targetFile = path.join(candidate, 'index.html');
    } else {
      targetFile = candidate;
    }
  } else {
    const candidate = path.resolve(path.dirname(currentFileAbs), queryStripped);
    if (!candidate.startsWith(absOut)) {
      failures.push(formatFailure(relCurrent, lineNum, `Relative link escapes output directory: ${rawUrl}`, 'Use a valid relative or root-relative path.'));
      return null;
    }
    if (queryStripped.endsWith('/') || (fs.existsSync(candidate) && fs.statSync(candidate).isDirectory())) {
      targetFile = path.join(candidate, 'index.html');
    } else {
      targetFile = candidate;
    }
  }

  if (!fs.existsSync(targetFile) || !fs.statSync(targetFile).isFile()) {
    failures.push(
      formatFailure(
        relCurrent,
        lineNum,
        `Broken internal reference "${rawUrl}"`,
        `Ensure ${path.relative(absOut, targetFile)} exists in built output.`,
      ),
    );
    return null;
  }

  return { targetFile, fragment };
}

function readPngIhdr(filePath) {
  const buf = fs.readFileSync(filePath);
  if (buf.length < 24) return { valid: false, reason: 'File smaller than 24 bytes', size: buf.length };
  const sig = buf.subarray(0, 8);
  const expectedSig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (!sig.equals(expectedSig) || buf.subarray(12, 16).toString('ascii') !== 'IHDR') {
    return { valid: false, reason: 'Invalid PNG signature or IHDR chunk', size: buf.length };
  }
  return {
    valid: true,
    width: buf.readUInt32BE(16),
    height: buf.readUInt32BE(20),
    size: buf.length,
  };
}

async function runBrowserAudit(absRoot, absOut, site, pages, failures) {
  let puppeteer;
  try {
    const webxrPkg = path.resolve(absRoot, 'spatialfin-webxr/package.json');
    if (!fs.existsSync(webxrPkg)) {
      throw new Error(`Missing ${webxrPkg}`);
    }
    const req = createRequire(webxrPkg);
    puppeteer = req('puppeteer');
  } catch (err) {
    failures.push(
      formatFailure(
        'spatialfin-webxr/package.json',
        0,
        `Puppeteer unavailable for --browser audit (${err.message})`,
        'Run "cd spatialfin-webxr && npm ci" before running "node site/verify.mjs --browser".',
      ),
    );
    return;
  }

  const server = http.createServer((req, res) => {
    const reqUrl = (req.url || '/').split('?')[0];
    let rel = decodeURIComponent(reqUrl);
    if (rel.startsWith('/')) rel = rel.slice(1);
    let candidate = path.resolve(absOut, rel);
    if (!candidate.startsWith(absOut)) {
      res.writeHead(403);
      res.end('Forbidden');
      return;
    }
    if (fs.existsSync(candidate) && fs.statSync(candidate).isDirectory()) {
      candidate = path.join(candidate, 'index.html');
    }
    if (!fs.existsSync(candidate) || !fs.statSync(candidate).isFile()) {
      const notFoundPath = path.join(absOut, '404.html');
      if (fs.existsSync(notFoundPath)) {
        res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(fs.readFileSync(notFoundPath));
      } else {
        res.writeHead(404);
        res.end('Not found');
      }
      return;
    }
    const ext = path.extname(candidate).toLowerCase();
    res.writeHead(200, { 'Content-Type': MIME_TYPES[ext] || 'application/octet-stream' });
    fs.createReadStream(candidate).pipe(res);
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  const baseUrl = `http://127.0.0.1:${address.port}`;

  let browser;
  try {
    browser = await puppeteer.launch({
      headless: true,
      args: ['--no-sandbox', '--disable-setuid-sandbox'],
    });
    const auditPages = pages.filter((p) => p.kind === 'page' || p.kind === 'notFound');
    const viewports = [
      { width: 1440, height: 900 },
      { width: 768, height: 1024 },
      { width: 390, height: 844 },
    ];

    for (const vp of viewports) {
      for (const pageMeta of auditPages) {
        const page = await browser.newPage();
        await page.setViewport(vp);
        const consoleErrors = [];
        const pageErrors = [];
        page.on('console', (msg) => {
          if (msg.type() === 'error') consoleErrors.push(msg.text());
        });
        page.on('pageerror', (err) => pageErrors.push(err.message));

        await page.setRequestInterception(true);
        page.on('request', (req) => {
          const u = req.url();
          if (u.startsWith(baseUrl) || u.startsWith('data:') || u.startsWith('blob:')) {
            req.continue();
          } else if (u === site.umamiSrc) {
            req.respond({
              status: 200,
              contentType: 'application/javascript; charset=utf-8',
              body: '',
            });
          } else {
            req.abort();
          }
        });

        await page.goto(`${baseUrl}${pageMeta.slug}`, { waitUntil: 'load' });
        const result = await page.evaluate((kind) => {
          const overflow = document.documentElement.scrollWidth > window.innerWidth;
          const brokenImgs = Array.from(document.querySelectorAll('img'))
            .filter((img) => !img.complete || img.naturalWidth === 0)
            .map((img) => img.getAttribute('src'));
          const hasAriaCurrent = kind !== 'page' || Boolean(document.querySelector('[aria-current="page"]'));
          return { overflow, brokenImgs, hasAriaCurrent };
        }, pageMeta.kind);

        if (consoleErrors.length > 0 || pageErrors.length > 0) {
          failures.push(
            formatFailure(
              pageMeta.outputPath,
              0,
              `Browser runtime errors at ${vp.width}x${vp.height}: ${[...consoleErrors, ...pageErrors].join('; ')}`,
              'Fix JavaScript errors on page load.',
            ),
          );
        }
        if (result.overflow) {
          failures.push(
            formatFailure(
              pageMeta.outputPath,
              0,
              `Horizontal overflow detected at ${vp.width}x${vp.height}`,
              'Fix responsive CSS so scrollWidth <= innerWidth.',
            ),
          );
        }
        if (result.brokenImgs.length > 0) {
          failures.push(
            formatFailure(
              pageMeta.outputPath,
              0,
              `Images failed to load in browser: ${result.brokenImgs.join(', ')}`,
              'Ensure all referenced images exist and load cleanly.',
            ),
          );
        }
        if (!result.hasAriaCurrent) {
          failures.push(
            formatFailure(
              pageMeta.outputPath,
              0,
              'Missing aria-current="page" on canonical page navigation',
              'Mark the active route with aria-current="page".',
            ),
          );
        }

        if (vp.width === 390) {
          await page.click('[data-nav-toggle]');
          const opened = await page.evaluate(() => {
            const btn = document.querySelector('[data-nav-toggle]');
            const drawer = document.getElementById('mobile-nav');
            return btn?.getAttribute('aria-expanded') === 'true' && drawer && !drawer.hidden;
          });
          if (!opened) {
            failures.push(
              formatFailure(
                pageMeta.outputPath,
                0,
                'Mobile navigation drawer did not open at 390px',
                'Check [data-nav-toggle] and #mobile-nav wiring in site/assets/site.js.',
              ),
            );
          }
          await page.keyboard.press('Escape');
          const closed = await page.evaluate(() => {
            const btn = document.querySelector('[data-nav-toggle]');
            const drawer = document.getElementById('mobile-nav');
            return btn?.getAttribute('aria-expanded') === 'false' && drawer && drawer.hidden;
          });
          if (!closed) {
            failures.push(
              formatFailure(
                pageMeta.outputPath,
                0,
                'Mobile navigation drawer did not close on Escape at 390px',
                'Ensure Escape key resets aria-expanded="false" and hides #mobile-nav.',
              ),
            );
          }
        }
        await page.close();
      }
    }

    // No-JS readability check
    for (const pageMeta of auditPages) {
      const page = await browser.newPage();
      await page.setJavaScriptEnabled(false);
      await page.setViewport({ width: 1280, height: 800 });
      await page.setRequestInterception(true);
      page.on('request', (req) => {
        if (req.url().startsWith(baseUrl)) req.continue();
        else req.abort();
      });
      await page.goto(`${baseUrl}${pageMeta.slug}`, { waitUntil: 'domcontentloaded' });
      const visible = await page.evaluate(() => {
        const main = document.querySelector('main');
        const cs = main ? getComputedStyle(main) : null;
        return Boolean(main && main.innerText.trim().length > 0 && cs && parseFloat(cs.opacity) > 0 && cs.display !== 'none');
      });
      if (!visible) {
        failures.push(
          formatFailure(
            pageMeta.outputPath,
            0,
            'Primary content is hidden or empty with JavaScript disabled',
            'Ensure main content never depends on JS to become visible.',
          ),
        );
      }
      await page.close();
    }
  } finally {
    if (browser) await browser.close();
    server.close();
  }
}

export async function verifySite({ rootDir = process.cwd(), outDir = '_site', browser = false } = {}) {
  const absRoot = path.resolve(rootDir);
  const absOut = path.resolve(absRoot, outDir);
  const failures = [];

  if (!fs.existsSync(absOut)) {
    failures.push(formatFailure(outDir, 0, `Built site directory does not exist: ${absOut}`, 'Run "node site/build.mjs" first.'));
    return { ok: false, failures, stats: {} };
  }

  const site = JSON.parse(fs.readFileSync(path.resolve(absRoot, 'site/data/site.json'), 'utf8'));
  const pages = JSON.parse(fs.readFileSync(path.resolve(absRoot, 'site/data/pages.json'), 'utf8'));
  const appName = parseAppName(absRoot);

  // 1. Protected copies & core metadata files
  const cnamePath = path.join(absOut, 'CNAME');
  if (!fs.existsSync(cnamePath) || fs.readFileSync(cnamePath, 'utf8').trim() !== 'spatialfin.martinez.fyi') {
    failures.push(formatFailure(`${outDir}/CNAME`, 0, 'Missing CNAME or host != spatialfin.martinez.fyi', 'Copy docs/CNAME into _site/CNAME.'));
  }
  for (const reqFile of ['.nojekyll', 'robots.txt', 'sitemap.xml', 'web/index.html', 'web/manifest.webmanifest', 'assets/style.css']) {
    if (!fs.existsSync(path.join(absOut, reqFile))) {
      failures.push(formatFailure(`${outDir}/${reqFile}`, 0, `Missing required output file ${reqFile}`, 'Run "node site/build.mjs".'));
    }
  }

  const robotsPath = path.join(absOut, 'robots.txt');
  if (fs.existsSync(robotsPath)) {
    const robotsTxt = fs.readFileSync(robotsPath, 'utf8');
    if (robotsTxt.includes('Disallow: /web/')) {
      failures.push(formatFailure(`${outDir}/robots.txt`, 0, 'robots.txt must not disallow /web/', 'Remove Disallow: /web/ and keep /web/ omitted from sitemap.xml.'));
    }
    if (!robotsTxt.includes(`Sitemap: ${site.origin}/sitemap.xml`)) {
      failures.push(formatFailure(`${outDir}/robots.txt`, 0, 'robots.txt missing Sitemap directive', `Include "Sitemap: ${site.origin}/sitemap.xml".`));
    }
  }

  // 2. CSS :root variables check
  const cssPath = path.join(absOut, 'assets/style.css');
  let definedCssVars = new Set();
  if (fs.existsSync(cssPath)) {
    const cssText = fs.readFileSync(cssPath, 'utf8');
    const parsedCss = parseRootCssVars(cssText);
    definedCssVars = parsedCss.defined;
    for (const usage of parsedCss.usages) {
      if (!definedCssVars.has(usage.name)) {
        failures.push(
          formatFailure(
            `${outDir}/assets/style.css`,
            usage.line,
            `Undefined CSS custom property var(${usage.name})`,
            `Define ${usage.name} inside :root in site/assets/style.css.`,
          ),
        );
      }
    }
  }

  // 3. Parse sitemap.xml
  const sitemapPath = path.join(absOut, 'sitemap.xml');
  const sitemapUrls = [];
  if (fs.existsSync(sitemapPath)) {
    const sitemapTxt = fs.readFileSync(sitemapPath, 'utf8');
    for (const m of sitemapTxt.matchAll(/<loc>([^<]+)<\/loc>/g)) {
      sitemapUrls.push(m[1].trim());
    }
  }
  const expectedCanonicalUrls = pages.filter((p) => p.kind === 'page').map((p) => `${site.origin}${p.slug}`);
  if (
    sitemapUrls.length !== expectedCanonicalUrls.length ||
    !expectedCanonicalUrls.every((u) => sitemapUrls.includes(u))
  ) {
    failures.push(
      formatFailure(
        `${outDir}/sitemap.xml`,
        0,
        `sitemap.xml URLs do not match the ${expectedCanonicalUrls.length} canonical pages 1:1`,
        'Ensure sitemap.xml includes every kind:"page" slug and excludes redirects and 404.html.',
      ),
    );
  }

  // 4. Preload ID maps & HTML files
  const htmlFilesMeta = [];
  const idsByFile = new Map();
  for (const pageMeta of pages) {
    const absHtml = path.join(absOut, pageMeta.outputPath);
    const relHtml = `${outDir}/${pageMeta.outputPath}`;
    if (!fs.existsSync(absHtml)) {
      failures.push(formatFailure(relHtml, 0, `Missing built HTML output ${pageMeta.outputPath}`, 'Run "node site/build.mjs".'));
      continue;
    }
    const rawHtml = fs.readFileSync(absHtml, 'utf8');
    const stripped = stripNonContentPreserveLines(rawHtml);
    const idSet = new Set();
    for (const idMatch of stripped.matchAll(/\bid\s*=\s*"([^"]+)"/gi)) {
      idSet.add(idMatch[1]);
    }
    idsByFile.set(absHtml, idSet);
    htmlFilesMeta.push({ pageMeta, absHtml, relHtml, rawHtml, stripped });
  }

  const seenTitles = new Set();
  const seenDescs = new Set();
  const referencedImagePaths = new Set();

  for (const { pageMeta, absHtml, relHtml, rawHtml, stripped } of htmlFilesMeta) {
    // Check inline style="...var(--foo)..." attributes
    for (const m of stripped.matchAll(/var\(\s*(--[A-Za-z0-9_-]+)/g)) {
      if (!definedCssVars.has(m[1])) {
        failures.push(
          formatFailure(
            relHtml,
            lineOfOffset(rawHtml, m.index ?? 0),
            `Undefined CSS custom property var(${m[1]}) in HTML`,
            `Define ${m[1]} inside :root in site/assets/style.css.`,
          ),
        );
      }
    }

    if (pageMeta.kind === 'redirect') {
      if (!rawHtml.includes('<meta http-equiv="refresh"')) {
        failures.push(formatFailure(relHtml, 0, 'Redirect stub missing meta http-equiv="refresh"', 'Use site/templates/redirect.html.'));
      }
      if (!/<link\s+rel="canonical"/i.test(rawHtml)) {
        failures.push(formatFailure(relHtml, 0, 'Redirect stub missing canonical link', 'Emit <link rel="canonical"> in redirect.html.'));
      }
      if (!rawHtml.includes('noindex, follow')) {
        failures.push(formatFailure(relHtml, 0, 'Redirect stub missing noindex, follow directive', 'Add <meta name="robots" content="noindex, follow">.'));
      }
      if (rawHtml.includes(site.umamiSrc)) {
        failures.push(formatFailure(relHtml, 0, 'Redirect stub must not include Umami script', 'Omit Umami from site/templates/redirect.html.'));
      }
      if (sitemapUrls.includes(`${site.origin}${pageMeta.slug}`)) {
        failures.push(formatFailure(`${outDir}/sitemap.xml`, 0, `Redirect stub ${pageMeta.slug} leaked into sitemap.xml`, 'Exclude redirect entries from sitemap.xml.'));
      }
    } else {
      // Both canonical pages and 404.html require Umami
      if (!rawHtml.includes(site.umamiSrc) || !rawHtml.includes(site.umamiWebsiteId)) {
        failures.push(formatFailure(relHtml, 0, 'Missing expected Umami analytics script tag', 'Include umamiSrc and umamiWebsiteId in shell.html.'));
      }

      // Check every <img> has width, height, alt
      for (const imgMatch of stripped.matchAll(/<img\b[^>]*>/gi)) {
        const tag = imgMatch[0];
        const lineNum = lineOfOffset(rawHtml, imgMatch.index ?? 0);
        const w = extractTagAttr(tag, 'width');
        const h = extractTagAttr(tag, 'height');
        const alt = extractTagAttr(tag, 'alt');
        const src = extractTagAttr(tag, 'src');
        if (!w || !/^\d+$/.test(w) || !h || !/^\d+$/.test(h)) {
          failures.push(formatFailure(relHtml, lineNum, `<img> (${src || 'unknown'}) missing numeric width/height`, 'Add explicit integer width and height attributes.'));
        }
        if (!alt || !alt.trim()) {
          failures.push(formatFailure(relHtml, lineNum, `<img> (${src || 'unknown'}) missing non-empty alt`, 'Add descriptive alt text.'));
        }
      }

      if (pageMeta.kind === 'notFound') {
        if (!rawHtml.includes('/assets/style.css') || !rawHtml.includes('/images/icon.png')) {
          failures.push(formatFailure(relHtml, 0, '404.html must use root-relative /assets/ and /images/ URLs', 'Fix asset paths in shell.html.'));
        }
        if (sitemapUrls.includes(`${site.origin}${pageMeta.slug}`)) {
          failures.push(formatFailure(`${outDir}/sitemap.xml`, 0, '404.html must not appear in sitemap.xml', 'Exclude notFound entries from sitemap.xml.'));
        }
      }

      if (pageMeta.kind === 'page') {
        const titleMatch = rawHtml.match(/<title>([\s\S]*?)<\/title>/i);
        const titleText = titleMatch ? decodeBasicEntities(titleMatch[1]).trim() : '';
        if (!titleText || titleText.length < 20 || titleText.length > 65 || seenTitles.has(titleText)) {
          failures.push(
            formatFailure(
              relHtml,
              0,
              `Invalid or duplicate <title> (len=${titleText.length})`,
              'Set a unique title between 20 and 65 characters in pages.json.',
            ),
          );
        }
        seenTitles.add(titleText);

        const descMatch = rawHtml.match(/<meta\s+name="description"\s+content="([^"]*)"/i);
        const descText = descMatch ? decodeBasicEntities(descMatch[1]).trim() : '';
        if (!descText || descText.length < 70 || descText.length > 160 || seenDescs.has(descText)) {
          failures.push(
            formatFailure(
              relHtml,
              0,
              `Invalid or duplicate meta description (len=${descText.length})`,
              'Set a unique description between 70 and 160 characters in pages.json.',
            ),
          );
        }
        seenDescs.add(descText);

        const canonMatch = rawHtml.match(/<link\s+rel="canonical"\s+href="([^"]*)"/i);
        const canonHref = canonMatch ? decodeBasicEntities(canonMatch[1]) : '';
        const expectedCanon = `${site.origin}${pageMeta.slug}`;
        if (canonHref !== expectedCanon) {
          failures.push(
            formatFailure(relHtml, 0, `Canonical "${canonHref}" != "${expectedCanon}"`, 'Fix canonical link generation in site/build.mjs.'),
          );
        }

        for (const metaKey of [
          'property="og:title"',
          'property="og:description"',
          'property="og:image"',
          'property="og:url"',
          'name="twitter:card"',
          'name="twitter:image"',
        ]) {
          if (!rawHtml.includes(metaKey)) {
            failures.push(formatFailure(relHtml, 0, `Missing social meta tag ${metaKey}`, 'Emit complete OG/Twitter metadata in site/build.mjs.'));
          }
        }

        // JSON-LD check
        const ldMatch = rawHtml.match(/<script\s+type="application\/ld\+json">([\s\S]*?)<\/script>/i);
        if (!ldMatch) {
          failures.push(formatFailure(relHtml, 0, 'Missing JSON-LD structured data block', 'Emit JSON-LD in site/build.mjs.'));
        } else {
          try {
            const parsedLd = JSON.parse(ldMatch[1]);
            if (pageMeta.slug === '/') {
              if (parsedLd['@type'] !== 'SoftwareApplication' || parsedLd.softwareVersion !== appName) {
                failures.push(
                  formatFailure(
                    relHtml,
                    0,
                    `JSON-LD softwareVersion (${parsedLd.softwareVersion}) !== Versions.APP_NAME (${appName})`,
                    'Rebuild with site/build.mjs from Versions.kt.',
                  ),
                );
              }
            }
          } catch (err) {
            failures.push(formatFailure(relHtml, 0, `Invalid JSON-LD JSON: ${err.message}`, 'Fix JSON-LD escaping in site/build.mjs.'));
          }
        }

        // Heading hierarchy & single <h1>
        const headings = [];
        for (const hm of stripped.matchAll(/<h([1-6])\b/gi)) {
          headings.push({
            level: Number(hm[1]),
            line: lineOfOffset(rawHtml, hm.index ?? 0),
          });
        }
        const h1Count = headings.filter((h) => h.level === 1).length;
        if (h1Count !== 1) {
          failures.push(formatFailure(relHtml, 0, `Expected exactly 1 <h1>, found ${h1Count}`, 'Keep one <h1> heading per page.'));
        }
        let prevLevel = 0;
        for (const h of headings) {
          if (prevLevel === 0 && h.level !== 1) {
            failures.push(formatFailure(relHtml, h.line, `First heading is <h${h.level}> instead of <h1>`, 'Start page heading hierarchy at <h1>.'));
          } else if (prevLevel > 0 && h.level > prevLevel + 1) {
            failures.push(
              formatFailure(
                relHtml,
                h.line,
                `Heading level skip from <h${prevLevel}> to <h${h.level}>`,
                `Use <h${prevLevel + 1}> or add intermediate headings.`,
              ),
            );
          }
          prevLevel = h.level;
        }
      }
    }

    // Check internal links, assets, #anchors, and collect referenced images (stripping <pre>/<code>/<!--...-->)
    const linkScanSurface = stripPreCodeCommentsPreserveLines(rawHtml);
    for (const tagMatch of linkScanSurface.matchAll(/<(a|img|link|script|meta)\b[^>]*>/gi)) {
      const tag = tagMatch[0];
      const tagName = tagMatch[1].toLowerCase();
      const lineNum = lineOfOffset(rawHtml, tagMatch.index ?? 0);

      let urlAttr = null;
      if (tagName === 'a' || tagName === 'link') {
        urlAttr = extractTagAttr(tag, 'href');
      } else if (tagName === 'img' || tagName === 'script') {
        urlAttr = extractTagAttr(tag, 'src');
      } else if (tagName === 'meta') {
        const prop = extractTagAttr(tag, 'property') || extractTagAttr(tag, 'name');
        if (prop === 'og:image' || prop === 'twitter:image') {
          urlAttr = extractTagAttr(tag, 'content');
        }
      }

      if (!urlAttr) continue;

      const resolved = resolveOnDiskTarget(absOut, absHtml, urlAttr, site.origin, failures, relHtml, lineNum);
      if (resolved) {
        if (resolved.fragment) {
          const targetIds = idsByFile.get(resolved.targetFile);
          if (targetIds && !targetIds.has(resolved.fragment)) {
            failures.push(
              formatFailure(
                relHtml,
                lineNum,
                `Broken #anchor target "#${resolved.fragment}" in ${urlAttr}`,
                `Add id="${resolved.fragment}" on target element or fix the href.`,
              ),
            );
          }
        }

        const imagesDirAbs = path.join(absOut, 'images');
        if (
          resolved.targetFile.startsWith(imagesDirAbs + path.sep) &&
          (tagName === 'img' || tagName === 'link' || tagName === 'meta')
        ) {
          referencedImagePaths.add(resolved.targetFile);
        }
      }
    }
  }

  // 5. Image dimensions, byte caps, and orphan image check
  const imagesDirAbs = path.join(absOut, 'images');
  let totalImageBytes = 0;
  if (!fs.existsSync(imagesDirAbs)) {
    failures.push(formatFailure(`${outDir}/images`, 0, 'Missing built images directory', 'Commit required assets under site/images/ and rebuild.'));
  } else {
    const ogCardAbs = path.join(imagesDirAbs, 'og-card.png');
    if (!fs.existsSync(ogCardAbs)) {
      failures.push(formatFailure(`${outDir}/images/og-card.png`, 0, 'Missing required og-card.png', 'Add 1200x630 PNG at site/images/og-card.png.'));
    } else {
      const pngInfo = readPngIhdr(ogCardAbs);
      if (!pngInfo.valid || pngInfo.width < 1200 || pngInfo.height < 630) {
        failures.push(
          formatFailure(
            `${outDir}/images/og-card.png`,
            0,
            `og-card.png must be a valid PNG >= 1200x630 (${pngInfo.valid ? `${pngInfo.width}x${pngInfo.height}` : pngInfo.reason})`,
            'Generate a 1200x630 PNG at site/images/og-card.png.',
          ),
        );
      }
      if (pngInfo.size > MAX_OG_PNG_BYTES) {
        failures.push(
          formatFailure(
            `${outDir}/images/og-card.png`,
            0,
            `og-card.png exceeds 500 KB limit (${pngInfo.size} bytes)`,
            'Compress og-card.png below 500 KB.',
          ),
        );
      }
    }

    const imageFiles = collectFilesRecursive(imagesDirAbs);
    for (const imgAbs of imageFiles) {
      const stat = fs.statSync(imgAbs);
      totalImageBytes += stat.size;
      const relImg = `${outDir}/${path.relative(absOut, imgAbs)}`;
      const base = path.basename(imgAbs);

      if (base.endsWith('.webp')) {
        const cap = base === 'trailer-poster.webp' ? MAX_TRAILER_POSTER_BYTES : MAX_WEBP_BYTES;
        if (stat.size > cap) {
          failures.push(
            formatFailure(
              relImg,
              0,
              `${base} exceeds ${Math.round(cap / 1024)} KB budget (${stat.size} bytes)`,
              'Re-encode WebP at lower quality or smaller width.',
            ),
          );
        }
      }

      if (!referencedImagePaths.has(imgAbs)) {
        failures.push(
          formatFailure(
            relImg,
            0,
            `Unreferenced image file under ${outDir}/images/`,
            'Reference it via <img>, <link rel="icon">, or OG/Twitter meta tags, or remove it from site/images/.',
          ),
        );
      }
    }

    if (totalImageBytes > MAX_TOTAL_IMAGES_BYTES) {
      failures.push(
        formatFailure(
          `${outDir}/images`,
          0,
          `Total image bytes exceed 15 MB (${totalImageBytes} bytes)`,
          'Trim or compress images under site/images/.',
        ),
      );
    }
  }

  // 6. PeerTube example fixture checks
  const ptManifestPath = path.resolve(absRoot, 'site/examples/peertube/manifest.json');
  const ptScriptPath = path.resolve(absRoot, 'site/examples/peertube/script.js');
  if (!fs.existsSync(ptManifestPath)) {
    failures.push(formatFailure('site/examples/peertube/manifest.json', 0, 'Missing PeerTube manifest.json', 'Create site/examples/peertube/manifest.json.'));
  } else {
    try {
      const manifest = JSON.parse(fs.readFileSync(ptManifestPath, 'utf8'));
      if (
        typeof manifest.id !== 'string' ||
        !manifest.id ||
        typeof manifest.name !== 'string' ||
        !manifest.name ||
        typeof manifest.scriptUrl !== 'string' ||
        !manifest.scriptUrl ||
        !Array.isArray(manifest.capabilities) ||
        manifest.capabilities.length === 0
      ) {
        failures.push(
          formatFailure(
            'site/examples/peertube/manifest.json',
            0,
            'PeerTube manifest.json missing required fields (id, name, scriptUrl, capabilities)',
            'Populate required PluginConfig fields.',
          ),
        );
      }
    } catch (err) {
      failures.push(formatFailure('site/examples/peertube/manifest.json', 0, `Invalid JSON: ${err.message}`, 'Fix JSON syntax.'));
    }
  }

  if (!fs.existsSync(ptScriptPath)) {
    failures.push(formatFailure('site/examples/peertube/script.js', 0, 'Missing PeerTube script.js', 'Create site/examples/peertube/script.js.'));
  } else {
    try {
      execFileSync(process.execPath, ['--check', ptScriptPath], { stdio: 'pipe' });
    } catch (err) {
      failures.push(
        formatFailure(
          'site/examples/peertube/script.js',
          0,
          `JavaScript syntax check failed: ${err.stderr?.toString() || err.message}`,
          'Fix syntax errors in site/examples/peertube/script.js.',
        ),
      );
    }
  }

  if (browser) {
    await runBrowserAudit(absRoot, absOut, site, pages, failures);
  }

  return {
    ok: failures.length === 0,
    failures,
    stats: {
      pagesChecked: htmlFilesMeta.length,
      totalImageBytes,
    },
  };
}

function parseArgs(argv) {
  const opts = { browser: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--root' && argv[i + 1]) {
      opts.rootDir = argv[++i];
    } else if (arg === '--out' && argv[i + 1]) {
      opts.outDir = argv[++i];
    } else if (arg === '--browser') {
      opts.browser = true;
    } else {
      throw new Error(`Unknown or incomplete CLI argument: ${arg}`);
    }
  }
  return opts;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    const opts = parseArgs(process.argv.slice(2));
    const res = await verifySite(opts);
    if (!res.ok) {
      for (const msg of res.failures) {
        console.error(msg);
      }
      process.exit(1);
    }
    console.log(`Verified ${res.stats.pagesChecked} pages (${res.stats.totalImageBytes} image bytes) with 0 failures.`);
  } catch (err) {
    console.error(`[FAIL] site/verify.mjs — ${err.message}`);
    process.exit(1);
  }
}
