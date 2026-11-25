import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const ALLOWED_KINDS = new Set(['page', 'redirect', 'notFound']);
const ALLOWED_ZONES = new Set(['discovery', 'guides', 'companion', 'plugins']);
const ALLOWED_LAYOUTS = new Set(['landing', 'docs']);
const ALLOWED_SCHEMAS = new Set(['SoftwareApplication', 'WebSite', 'HowTo', 'TechArticle']);
const PROTECTED_CODE_RE = /(<pre\b[\s\S]*?<\/pre>|<code\b[\s\S]*?<\/code>)/gi;

function isSameOrInside(candidate, parent) {
  const rel = path.relative(parent, candidate);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

function escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function escapeAttr(str) {
  return escapeHtml(str);
}

function stripTagsAndDecode(html) {
  return html
    .replace(/<[^>]+>/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function slugifyHeading(text) {
  const base = text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return base || 'section';
}

function assertProtectedOutput(absRoot, absOut) {
  const siteDir = path.resolve(absRoot, 'site');
  const docsDir = path.resolve(absRoot, 'docs');
  const docsWebDir = path.resolve(absRoot, 'docs/web');
  const docsCname = path.resolve(absRoot, 'docs/CNAME');
  const bannerPath = path.resolve(absRoot, 'docs/images/SpatialFin-banner.png');

  const blocked = [absRoot, siteDir, docsDir, docsWebDir, docsCname, bannerPath];
  if (
    blocked.includes(absOut) ||
    isSameOrInside(absOut, siteDir) ||
    isSameOrInside(absOut, docsDir) ||
    isSameOrInside(docsDir, absOut) ||
    isSameOrInside(siteDir, absOut)
  ) {
    throw new Error(`Refusing protected output path: ${absOut}`);
  }
}

function parseVersions(absRoot) {
  const versionsPath = path.resolve(absRoot, 'buildSrc/src/main/kotlin/Versions.kt');
  if (!fs.existsSync(versionsPath)) {
    throw new Error(`Missing Versions.kt at ${versionsPath}`);
  }
  const versionsText = fs.readFileSync(versionsPath, 'utf8');
  const appNameMatch = versionsText.match(/const\s+val\s+APP_NAME\s*=\s*"([^"]+)"/);
  const appCodeMatch = versionsText.match(/const\s+val\s+APP_CODE\s*=\s*([0-9_]+)/);
  const minSdkMatch = versionsText.match(/const\s+val\s+MIN_SDK\s*=\s*([0-9_]+)/);
  if (!appNameMatch || !appCodeMatch || !minSdkMatch) {
    throw new Error(`Malformed Versions.kt (missing APP_NAME, APP_CODE, or MIN_SDK) at ${versionsPath}`);
  }

  const wearGradlePath = path.resolve(absRoot, 'companion/wear/build.gradle.kts');
  if (!fs.existsSync(wearGradlePath)) {
    throw new Error(`Missing wear build.gradle.kts at ${wearGradlePath}`);
  }
  const wearText = fs.readFileSync(wearGradlePath, 'utf8');
  const wearMinSdkMatch = wearText.match(/minSdk\s*=\s*([0-9_]+)/);
  if (!wearMinSdkMatch) {
    throw new Error(`Malformed companion/wear/build.gradle.kts (missing minSdk) at ${wearGradlePath}`);
  }

  return {
    version: appNameMatch[1].trim(),
    appCode: appCodeMatch[1].replace(/_/g, ''),
    minSdk: minSdkMatch[1].replace(/_/g, ''),
    wearMinSdk: wearMinSdkMatch[1].replace(/_/g, ''),
  };
}

function validateSiteConfig(site, filePath) {
  const required = [
    'siteName',
    'origin',
    'repoUrl',
    'playStoreUrl',
    'releasesUrl',
    'discordUrl',
    'privacyUrl',
    'companionRepoUrl',
    'grayjayHubUrl',
    'grayjayDocsUrl',
    'fcastUrl',
    'youtubeTrailerId',
    'umamiSrc',
    'umamiWebsiteId',
    'defaultOgImage',
  ];
  for (const key of required) {
    if (typeof site[key] !== 'string' || !site[key].trim()) {
      throw new Error(`Invalid site.json (${filePath}): missing or empty "${key}"`);
    }
  }
  if (site.origin.endsWith('/')) {
    throw new Error(`Invalid site.json (${filePath}): "origin" must not end with a trailing slash`);
  }
}

function validatePagesConfig(pages, filePath) {
  if (!Array.isArray(pages) || pages.length === 0) {
    throw new Error(`Invalid pages.json (${filePath}): expected non-empty array`);
  }
  const seenSlugs = new Set();
  const seenOutputs = new Set();
  const seenTitles = new Set();
  const seenDescriptions = new Set();

  for (const entry of pages) {
    if (!entry || typeof entry !== 'object') {
      throw new Error(`Invalid pages.json (${filePath}): entry must be an object`);
    }
    if (!ALLOWED_KINDS.has(entry.kind)) {
      throw new Error(`Invalid pages.json (${filePath}): unsupported kind "${entry.kind}"`);
    }
    if (typeof entry.slug !== 'string' || !entry.slug.startsWith('/') || /[A-Z]/.test(entry.slug)) {
      throw new Error(`Invalid pages.json (${filePath}): slug must be lowercase and start with "/": "${entry.slug}"`);
    }
    if (seenSlugs.has(entry.slug)) {
      throw new Error(`Invalid pages.json (${filePath}): duplicate slug "${entry.slug}"`);
    }
    seenSlugs.add(entry.slug);

    if (typeof entry.outputPath !== 'string' || !entry.outputPath || entry.outputPath.includes('..') || path.isAbsolute(entry.outputPath)) {
      throw new Error(`Invalid pages.json (${filePath}): invalid outputPath "${entry.outputPath}"`);
    }
    if (seenOutputs.has(entry.outputPath)) {
      throw new Error(`Invalid pages.json (${filePath}): duplicate outputPath "${entry.outputPath}"`);
    }
    seenOutputs.add(entry.outputPath);

    if (entry.kind === 'page') {
      if (!ALLOWED_ZONES.has(entry.zone)) {
        throw new Error(`Invalid pages.json (${filePath}): invalid zone "${entry.zone}" on ${entry.slug}`);
      }
      if (!ALLOWED_LAYOUTS.has(entry.layout)) {
        throw new Error(`Invalid pages.json (${filePath}): invalid layout "${entry.layout}" on ${entry.slug}`);
      }
      if (!ALLOWED_SCHEMAS.has(entry.schemaType)) {
        throw new Error(`Invalid pages.json (${filePath}): invalid schemaType "${entry.schemaType}" on ${entry.slug}`);
      }
      if (typeof entry.title !== 'string' || entry.title.length < 20 || entry.title.length > 65) {
        throw new Error(`Invalid pages.json (${filePath}): title for ${entry.slug} must be 20–65 chars (got ${entry.title?.length ?? 0})`);
      }
      if (seenTitles.has(entry.title)) {
        throw new Error(`Invalid pages.json (${filePath}): duplicate title "${entry.title}"`);
      }
      seenTitles.add(entry.title);

      if (typeof entry.description !== 'string' || entry.description.length < 70 || entry.description.length > 160) {
        throw new Error(`Invalid pages.json (${filePath}): description for ${entry.slug} must be 70–160 chars (got ${entry.description?.length ?? 0})`);
      }
      if (seenDescriptions.has(entry.description)) {
        throw new Error(`Invalid pages.json (${filePath}): duplicate description for ${entry.slug}`);
      }
      seenDescriptions.add(entry.description);

      if (typeof entry.fragmentPath !== 'string' || !entry.fragmentPath) {
        throw new Error(`Invalid pages.json (${filePath}): missing fragmentPath on ${entry.slug}`);
      }
    } else if (entry.kind === 'redirect') {
      if (typeof entry.redirectTo !== 'string' || !/^\/[a-z0-9/_-]*$/.test(entry.redirectTo)) {
        throw new Error(`Invalid pages.json (${filePath}): invalid redirectTo "${entry.redirectTo}" on ${entry.slug}`);
      }
      if (entry.noindex !== true) {
        throw new Error(`Invalid pages.json (${filePath}): redirect ${entry.slug} must set noindex: true`);
      }
    } else if (entry.kind === 'notFound') {
      if (!ALLOWED_LAYOUTS.has(entry.layout)) {
        throw new Error(`Invalid pages.json (${filePath}): invalid layout "${entry.layout}" on ${entry.slug}`);
      }
      if (typeof entry.title !== 'string' || !entry.title || typeof entry.description !== 'string' || !entry.description) {
        throw new Error(`Invalid pages.json (${filePath}): notFound page missing title or description`);
      }
      if (entry.noindex !== true) {
        throw new Error(`Invalid pages.json (${filePath}): notFound page must set noindex: true`);
      }
      if (typeof entry.fragmentPath !== 'string' || !entry.fragmentPath) {
        throw new Error(`Invalid pages.json (${filePath}): notFound page missing fragmentPath`);
      }
    }
  }
}

function expandFragment(rawHtml, absRoot, scalarTokens) {
  const examplesDir = path.resolve(absRoot, 'site/examples');

  const resolveToken = (tokenName, allowUnknown) => {
    if (Object.hasOwn(scalarTokens, tokenName)) {
      return scalarTokens[tokenName];
    }
    if (tokenName.startsWith('include:')) {
      const targetRel = tokenName.slice('include:'.length);
      if (!targetRel.startsWith('examples/')) {
        throw new Error(`Include must stay under examples/: {{${tokenName}}}`);
      }
      const subRel = targetRel.slice('examples/'.length);
      if (!subRel || subRel.includes('..') || path.isAbsolute(subRel)) {
        throw new Error(`Unsafe include path: {{${tokenName}}}`);
      }
      const resolved = path.resolve(examplesDir, subRel);
      if (!isSameOrInside(resolved, examplesDir) || resolved === examplesDir) {
        throw new Error(`Include path escapes site/examples/: {{${tokenName}}}`);
      }
      if (!fs.existsSync(resolved)) {
        throw new Error(`Missing include file: ${resolved}`);
      }
      return escapeHtml(fs.readFileSync(resolved, 'utf8').replace(/\n+$/, ''));
    }
    if (allowUnknown) {
      return `{{${tokenName}}}`;
    }
    throw new Error(`Unknown template placeholder outside code block: {{${tokenName}}}`);
  };

  // Split around <pre>/<code> blocks so unknown {{...}} inside code examples stay literal.
  const parts = rawHtml.split(PROTECTED_CODE_RE);
  return parts
    .map((part, idx) => {
      const inCode = idx % 2 === 1;
      return part.replace(/\{\{([^}]+)\}\}/g, (_, key) => resolveToken(key.trim(), inCode));
    })
    .join('');
}

function processDocsHeadings(html) {
  const usedIds = new Set();

  // First pass outside code/comments: collect any pre-existing id attributes on headings or elements
  const strippedForPreScan = html.replace(
    /<pre\b[\s\S]*?<\/pre>|<code\b[\s\S]*?<\/code>|<script\b[\s\S]*?<\/script>|<style\b[\s\S]*?<\/style>|<!--[\s\S]*?-->/gi,
    '',
  );
  for (const m of strippedForPreScan.matchAll(/\bid\s*=\s*"([^"]+)"/gi)) {
    usedIds.add(m[1]);
  }

  const tocItems = [];
  const parts = html.split(/(<pre\b[\s\S]*?<\/pre>|<code\b[\s\S]*?<\/code>|<script\b[\s\S]*?<\/script>|<style\b[\s\S]*?<\/style>|<!--[\s\S]*?-->)/gi);
  const transformed = parts
    .map((part, idx) => {
      if (idx % 2 === 1) return part;
      return part.replace(/<(h[23])\b([^>]*)>([\s\S]*?)<\/\1>/gi, (full, tag, attrs, inner) => {
        const level = Number(tag.slice(1));
        const text = stripTagsAndDecode(inner);
        const existingIdMatch = attrs.match(/\bid\s*=\s*"([^"]+)"/i);
        let id;
        let updatedTag;
        if (existingIdMatch) {
          id = existingIdMatch[1];
          updatedTag = full;
        } else {
          const base = slugifyHeading(text);
          id = base;
          let suffix = 2;
          while (usedIds.has(id)) {
            id = `${base}-${suffix}`;
            suffix += 1;
          }
          usedIds.add(id);
          updatedTag = `<${tag}${attrs} id="${escapeAttr(id)}">${inner}</${tag}>`;
        }
        tocItems.push({ level, id, text });
        return updatedTag;
      });
    })
    .join('');

  return { html: transformed, tocItems };
}

function buildNavLinks(page) {
  const items = [
    { href: '/#platforms', label: 'Platforms', event: 'nav-platforms', match: (p) => false },
    { href: '/guides/', label: 'Guides', event: 'nav-guides', match: (p) => p.zone === 'guides' },
    { href: '/companion/', label: 'Companion', event: 'nav-companion', match: (p) => p.zone === 'companion' },
    { href: '/plugins/', label: 'Plugins', event: 'nav-plugins', match: (p) => p.zone === 'plugins' },
    { href: '/web/', label: 'Web App', event: 'nav-web', match: () => false },
  ];

  // For '/', ensure an aria-current="page" indicator exists in primary navigation on Platforms/Home
  return items
    .map((item) => {
      const isActive = item.href === '/#platforms' ? page.slug === '/' : item.match(page);
      const currentAttr = isActive ? ' aria-current="page"' : '';
      return `<a href="${item.href}" data-umami-event="${item.event}"${currentAttr}>${escapeHtml(item.label)}</a>`;
    })
    .join('\n        ');
}

function buildSidebar(pages, currentPage) {
  if (currentPage.layout !== 'docs') return '';
  const docsPages = pages.filter((p) => p.kind === 'page' && p.layout === 'docs');
  const groups = new Map();
  for (const p of docsPages) {
    const groupName = p.sidebarGroup || 'Documentation';
    if (!groups.has(groupName)) groups.set(groupName, []);
    groups.get(groupName).push(p);
  }

  const groupHtml = [];
  for (const [groupName, items] of groups.entries()) {
    const links = items
      .map((item) => {
        const label = item.navLabel || item.title;
        const current = item.slug === currentPage.slug ? ' aria-current="page"' : '';
        return `<li><a href="${item.slug}"${current}>${escapeHtml(label)}</a></li>`;
      })
      .join('\n          ');
    groupHtml.push(
      `<div class="sidebar-group">\n        <p class="sidebar-group-title">${escapeHtml(groupName)}</p>\n        <ul class="sidebar-list">\n          ${links}\n        </ul>\n      </div>`,
    );
  }

  return `<aside class="sidebar-col" aria-label="Documentation navigation">\n      ${groupHtml.join('\n      ')}\n    </aside>`;
}

function buildBreadcrumbs(pagesBySlug, page) {
  if (page.layout !== 'docs') return '';
  const crumbs = [`<a href="/">Home</a>`];
  if (page.zone === 'guides' && page.slug !== '/guides/') {
    const guidesHome = pagesBySlug.get('/guides/');
    crumbs.push(`<a href="/guides/">${escapeHtml(guidesHome?.navLabel || 'Guides')}</a>`);
  }
  crumbs.push(`<span aria-current="page">${escapeHtml(page.navLabel || page.title)}</span>`);
  return `<nav class="breadcrumbs" aria-label="Breadcrumb">\n      ${crumbs.join('\n      <span aria-hidden="true">/</span>\n      ')}\n    </nav>`;
}

function buildPrevNext(pagesBySlug, page) {
  if (page.layout !== 'docs') return '';
  const prevPage = page.prev ? pagesBySlug.get(page.prev) : null;
  const nextPage = page.next ? pagesBySlug.get(page.next) : null;
  if (!prevPage && !nextPage) return '';

  const prevLink = prevPage
    ? `<a class="prev-link" href="${prevPage.slug}">← ${escapeHtml(prevPage.navLabel || prevPage.title)}</a>`
    : '<span></span>';
  const nextLink = nextPage
    ? `<a class="next-link" href="${nextPage.slug}">${escapeHtml(nextPage.navLabel || nextPage.title)} →</a>`
    : '<span></span>';

  return `<nav class="prev-next" aria-label="Pagination">\n      ${prevLink}\n      ${nextLink}\n    </nav>`;
}

function buildToc(tocItems, layout) {
  if (layout !== 'docs' || tocItems.length === 0) return '';
  const listItems = tocItems
    .map((item) => {
      const cls = item.level === 3 ? ' class="toc-sub"' : '';
      return `<li${cls}><a href="#${escapeAttr(item.id)}">${escapeHtml(item.text)}</a></li>`;
    })
    .join('\n        ');
  return `<aside class="toc-col" aria-label="On this page">\n      <p class="toc-title">On this page</p>\n      <ul class="toc-list">\n        ${listItems}\n      </ul>\n    </aside>`;
}

function buildJsonLd(site, page, version, canonicalUrl) {
  if (page.kind !== 'page') return '';
  let schema;
  if (page.schemaType === 'SoftwareApplication') {
    schema = {
      '@context': 'https://schema.org',
      '@type': 'SoftwareApplication',
      name: site.siteName,
      operatingSystem: 'Android XR, Android, Android TV, Wear OS, WebXR',
      applicationCategory: 'MultimediaApplication',
      softwareVersion: version,
      url: canonicalUrl,
      downloadUrl: site.playStoreUrl,
      offers: {
        '@type': 'Offer',
        price: '0',
        priceCurrency: 'USD',
      },
    };
  } else {
    const breadcrumbItems = [
      { '@type': 'ListItem', position: 1, name: 'Home', item: `${site.origin}/` },
    ];
    if (page.zone === 'guides' && page.slug !== '/guides/') {
      breadcrumbItems.push({
        '@type': 'ListItem',
        position: 2,
        name: 'Guides',
        item: `${site.origin}/guides/`,
      });
    }
    breadcrumbItems.push({
      '@type': 'ListItem',
      position: breadcrumbItems.length + 1,
      name: page.navLabel || page.title,
      item: canonicalUrl,
    });

    schema = {
      '@context': 'https://schema.org',
      '@graph': [
        {
          '@type': page.schemaType,
          name: page.title,
          headline: page.title,
          description: page.description,
          url: canonicalUrl,
        },
        {
          '@type': 'BreadcrumbList',
          itemListElement: breadcrumbItems,
        },
      ],
    };
  }

  const serialized = JSON.stringify(schema).replace(/</g, '\\u003c');
  return `<script type="application/ld+json">${serialized}</script>`;
}

function buildOgMeta(site, page, canonicalUrl) {
  if (page.kind !== 'page') return '';
  const ogImg = `${site.origin}${page.ogImage || site.defaultOgImage}`;
  return [
    `<meta property="og:type" content="website">`,
    `<meta property="og:site_name" content="${escapeAttr(site.siteName)}">`,
    `<meta property="og:title" content="${escapeAttr(page.title)}">`,
    `<meta property="og:description" content="${escapeAttr(page.description)}">`,
    `<meta property="og:url" content="${escapeAttr(canonicalUrl)}">`,
    `<meta property="og:image" content="${escapeAttr(ogImg)}">`,
    `<meta name="twitter:card" content="summary_large_image">`,
    `<meta name="twitter:title" content="${escapeAttr(page.title)}">`,
    `<meta name="twitter:description" content="${escapeAttr(page.description)}">`,
    `<meta name="twitter:image" content="${escapeAttr(ogImg)}">`,
  ].join('\n  ');
}

function copyDirRecursive(src, dest) {
  fs.mkdirSync(dest, { recursive: true });
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const srcPath = path.join(src, entry.name);
    const destPath = path.join(dest, entry.name);
    if (entry.isDirectory()) {
      copyDirRecursive(srcPath, destPath);
    } else if (entry.isFile()) {
      fs.copyFileSync(srcPath, destPath);
    }
  }
}

export function buildSite({ rootDir = process.cwd(), outDir = '_site' } = {}) {
  const absRoot = path.resolve(rootDir);
  const absOut = path.resolve(absRoot, outDir);
  assertProtectedOutput(absRoot, absOut);

  const siteJsonPath = path.resolve(absRoot, 'site/data/site.json');
  const pagesJsonPath = path.resolve(absRoot, 'site/data/pages.json');
  const shellPath = path.resolve(absRoot, 'site/templates/shell.html');
  const redirectPath = path.resolve(absRoot, 'site/templates/redirect.html');

  const site = JSON.parse(fs.readFileSync(siteJsonPath, 'utf8'));
  validateSiteConfig(site, siteJsonPath);

  const pages = JSON.parse(fs.readFileSync(pagesJsonPath, 'utf8'));
  validatePagesConfig(pages, pagesJsonPath);

  const versions = parseVersions(absRoot);
  const shellTemplate = fs.readFileSync(shellPath, 'utf8');
  const redirectTemplate = fs.readFileSync(redirectPath, 'utf8');

  const docsWebSrc = path.resolve(absRoot, 'docs/web');
  const docsCnameSrc = path.resolve(absRoot, 'docs/CNAME');
  if (!fs.existsSync(docsWebSrc) || !fs.existsSync(docsCnameSrc)) {
    throw new Error(`Missing required protected inputs (docs/web or docs/CNAME) under ${absRoot}`);
  }

  const pagesBySlug = new Map(pages.map((p) => [p.slug, p]));
  const renderedFiles = [];

  for (const page of pages) {
    if (page.kind === 'redirect') {
      const targetPage = pagesBySlug.get(page.redirectTo);
      const canonicalUrl = targetPage ? `${site.origin}${targetPage.slug}` : `${site.origin}${page.redirectTo}`;
      const tokens = {
        redirectTo: escapeAttr(page.redirectTo),
        canonicalUrl: escapeAttr(canonicalUrl),
      };
      const html = redirectTemplate.replace(/\{\{([a-zA-Z0-9_]+)\}\}/g, (_, key) => tokens[key] ?? '');
      renderedFiles.push({ outputPath: page.outputPath, html });
      continue;
    }

    const fragmentAbs = path.resolve(absRoot, page.fragmentPath);
    if (!isSameOrInside(fragmentAbs, path.resolve(absRoot, 'site/pages'))) {
      throw new Error(`Fragment path must reside under site/pages/: ${page.fragmentPath}`);
    }
    if (!fs.existsSync(fragmentAbs)) {
      throw new Error(`Missing page fragment: ${fragmentAbs}`);
    }

    const rawFragment = fs.readFileSync(fragmentAbs, 'utf8');
    let contentHtml = expandFragment(rawFragment, absRoot, versions);
    let tocItems = [];
    if (page.layout === 'docs') {
      const processed = processDocsHeadings(contentHtml);
      contentHtml = processed.html;
      tocItems = processed.tocItems;
    }

    const canonicalUrl = `${site.origin}${page.slug}`;
    const tokens = {
      title: escapeHtml(page.title),
      description: escapeAttr(page.description),
      canonicalTag: page.kind === 'page' ? `<link rel="canonical" href="${escapeAttr(canonicalUrl)}">` : '',
      robotsMeta: page.noindex ? `<meta name="robots" content="noindex">` : '',
      ogMeta: buildOgMeta(site, page, canonicalUrl),
      jsonLd: buildJsonLd(site, page, versions.version, canonicalUrl),
      umamiTag: `<script defer src="${escapeAttr(site.umamiSrc)}" data-website-id="${escapeAttr(site.umamiWebsiteId)}"></script>`,
      navLinks: buildNavLinks(page),
      layoutClass: page.layout === 'docs' ? 'layout-docs' : 'layout-landing',
      sidebarNav: buildSidebar(pages, page),
      breadcrumbs: buildBreadcrumbs(pagesBySlug, page),
      content: contentHtml.trim(),
      prevNext: buildPrevNext(pagesBySlug, page),
      toc: buildToc(tocItems, page.layout),
      version: escapeHtml(versions.version),
    };

    const html = shellTemplate.replace(/\{\{([a-zA-Z0-9_]+)\}\}/g, (_, key) => {
      if (!Object.hasOwn(tokens, key)) {
        throw new Error(`Unknown shell.html template slot: {{${key}}}`);
      }
      return tokens[key];
    });
    renderedFiles.push({ outputPath: page.outputPath, html });
  }

  // Clean and emit output directory once all validation succeeds
  fs.rmSync(absOut, { recursive: true, force: true });
  fs.mkdirSync(absOut, { recursive: true });

  for (const item of renderedFiles) {
    const outFile = path.resolve(absOut, item.outputPath);
    fs.mkdirSync(path.dirname(outFile), { recursive: true });
    fs.writeFileSync(outFile, item.html, 'utf8');
  }

  fs.writeFileSync(path.resolve(absOut, '.nojekyll'), '', 'utf8');
  fs.writeFileSync(
    path.resolve(absOut, 'robots.txt'),
    `User-agent: *\nAllow: /\n\nSitemap: ${site.origin}/sitemap.xml\n`,
    'utf8',
  );

  const canonicalUrls = pages
    .filter((p) => p.kind === 'page')
    .map((p) => `  <url><loc>${escapeHtml(`${site.origin}${p.slug}`)}</loc></url>`)
    .join('\n');
  fs.writeFileSync(
    path.resolve(absOut, 'sitemap.xml'),
    `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${canonicalUrls}\n</urlset>\n`,
    'utf8',
  );

  const assetsSrc = path.resolve(absRoot, 'site/assets');
  if (fs.existsSync(assetsSrc)) {
    copyDirRecursive(assetsSrc, path.resolve(absOut, 'assets'));
  }

  const imagesSrc = path.resolve(absRoot, 'site/images');
  if (fs.existsSync(imagesSrc)) {
    copyDirRecursive(imagesSrc, path.resolve(absOut, 'images'));
  }

  copyDirRecursive(docsWebSrc, path.resolve(absOut, 'web'));
  fs.copyFileSync(docsCnameSrc, path.resolve(absOut, 'CNAME'));

  return {
    outDir: absOut,
    pageCount: renderedFiles.length,
    version: versions.version,
  };
}

function parseArgs(argv) {
  const opts = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--root' && argv[i + 1]) {
      opts.rootDir = argv[++i];
    } else if (arg === '--out' && argv[i + 1]) {
      opts.outDir = argv[++i];
    } else {
      throw new Error(`Unknown or incomplete CLI argument: ${arg}`);
    }
  }
  return opts;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    const res = buildSite(parseArgs(process.argv.slice(2)));
    console.log(`Built ${res.pageCount} pages (v${res.version}) -> ${res.outDir}`);
  } catch (err) {
    console.error(`[FAIL] site/build.mjs — ${err.message}`);
    process.exit(1);
  }
}
