# SpatialFin Website & Release-Synced Docs Upgrade

*   **Author:** Ignacio Martinez (ignacio@martinez.fyi)
*   **Status:** Implemented
*   **Date:** 2026-11-25

--------------------------------------------------------------------------------

## Overview

SpatialFin is an open-source media client built for Android XR headsets that also ships natively on Android phones (including XREAL Beam Pro and external-display phones), Android TV, and Wear OS smartwatches, alongside a browser-based WebXR Progressive Web App (`/web/`). Its public website at [spatialfin.martinez.fyi](https://spatialfin.martinez.fyi/) (hosted via GitHub Pages on [ignacio82/spatialfin](https://github.com/ignacio82/spatialfin)) serves new users discovering the app and existing users configuring specialized workflows.

This design replaces the four hand-edited HTML pages under [`docs/`](../) with a zero-dependency Node.js 22 site compiler under `site/` deployed via GitHub Actions Pages, paired with deterministic release-bundle tooling so the site stays synchronized with Play Store releases without disturbing [`docs/web/`](../web/), [`docs/CNAME`](../CNAME), or [`docs/images/SpatialFin-banner.png`](../images/SpatialFin-banner.png):

1.  **Search and link discovery:** Publish complete search-engine metadata, extensionless directory URLs, and functional mobile navigation across all five supported surfaces.
2.  **Capability-led differentiation:** Document the playback, casting, offline, and security mechanisms SpatialFin implements — along with explicit platform boundaries — without naming competing Jellyfin clients.
3.  **Code-accurate user and developer guides:** Consolidate user documentation into focused guides covering specialized features (including the optional self-hosted **SpatialFin Companion Hub** and **SpatialFin for Wear OS**) and provide a QuickJS plugin guide detailing Grayjay compatibility separately from FCast casting.
4.  **Deterministic release-bundle integration:** Share serialized Play Store bundle flags (`libre` for Android XR and phones, `tv` for Android TV, `wear` for Wear OS) between CI and local releases, enforce fail-closed release signing in Gradle, and verify website integrity automatically on every push and release.

## The Problem

Auditing [`docs/`](../) against the current repository revealed six defects:

1.  **Weak search visibility and hidden documentation:** The site lacks `.nojekyll`, `robots.txt`, `sitemap.xml`, `404.html`, canonical URLs, Open Graph/Twitter previews, and JSON-LD structured data. Nine of ten capability sections on [`docs/index.html`](../index.html) sit behind `display: none` JavaScript tabs with no ARIA roles, URL hashes, or `Ctrl+F` reachability; `.reveal` sections remain at `opacity: 0` when JavaScript is disabled.
2.  **Broken mobile experience and cross-page drift across four hand-edited HTML files:** [`docs/style.css`](../style.css) hides `<nav>` (`display: none`) below `760px` with no mobile menu toggle. Across the four pages ([`docs/index.html`](../index.html), [`docs/smart.html`](../smart.html), [`docs/companion.html`](../companion.html), [`docs/plugins.html`](../plugins.html)), shared layout and styles have diverged: `docs/plugins.html` omits the footer and analytics script and references undefined CSS variables (`--surface`, `--border`, `--text-secondary`); `docs/companion.html` references undefined `--bg-card`, uses invalid `font-family: 'Space+Grotesk'`, and renders a CSS checkerboard placeholder; navigation links differ per page; and three of the four pages (`index.html`, `smart.html`, `companion.html`) skip from `<h2>` to `<h4>`.
3.  **Unclear positioning and naming collisions:** The landing page displays 12 unlinked hero badges and ~65 technical bullets referencing internal identifiers (`qc_broker_v1`, `ActivityManager.clearApplicationUserData()`), frames the app as headset-only despite five supported surfaces, omits Wear OS from downloads, and mixes "Companion App", "Companion Hub", and "Wear OS Companion" so visitors can confuse the optional self-hosted Docker server with a required phone app or smartwatch app.
4.  **Missing or inaccurate feature and developer guides:**
    *   Specialized workflows lack step-by-step guides: 3-choice onboarding, Android LAN `/setup` (port `41230`), Split-A/V calibration, on-device Gemma voice setup, App Lock and encrypted downloads (`Download/SpatialFin`), SMB/NFS + TMDB, Wear OS, WebXR CORS/Tailscale setup, ARCore wall anchoring, and Music Assistant / SendSpin.
    *   AirPlay is described as supporting AirPlay speakers even though [`FCastSessionPickerSheet.kt`](../../fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/FCastSessionPickerSheet.kt) supports unencrypted AirPlay v1 video only.
    *   Free-floating browsing and player 3D room pose persistence is claimed even though the active Galaxy XR browsing panel persists only scale (`xrAppPanelScale` in [`UnifiedMainActivity.kt`](../../app/unified/src/main/java/dev/spatialfin/unified/UnifiedMainActivity.kt), superseding legacy [`PanelPoseController.kt`](../../app/unified/src/main/java/dev/spatialfin/unified/PanelPoseController.kt)), the free-floating XR player always spawns centered while preserving zoom scale and distance depth ([`PlayerPoseStorage.kt`](../../player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerPoseStorage.kt)), and cross-session 3D room placement is provided exclusively by optional ARCore wall anchoring on the flat cinema screen ([`WallAnchorController.kt`](../../player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/WallAnchorController.kt)).
    *   The voice subtitle buffer is listed as 60 seconds instead of 20 minutes with disk fallback.
    *   [`docs/plugins.html`](../plugins.html) gives an outdated menu path ("Settings > Plugins" instead of **Sources → Plugins → Install Plugin**), uses `async` functions against synchronous `http.GET`, and omits Grayjay sandbox limits (`UrlTargetPolicy.PUBLIC_INTERNET_ONLY`, disabled `usePlatformAuth`, `GET`/`POST`-only HTTP, `20.1 s` timeout, stubbed `bridge.setSettings`, unenforced `scriptSignature`).
    *   [`docs/companion.html`](../companion.html) uses `docker compose up -d` instead of `docker compose up -d --build` (the upstream [`SpatialFin-Companion`](https://github.com/ignacio82/SpatialFin-Companion) compose file is `docker-compose.yaml` on `master`) and omits its bundled SearXNG web-search sidecar and first-time admin auth (`bootstrapPin` / `COMPANION_ADMIN_PASSWORD`).
5.  **No release-coupled verification or signing enforcement:** Point releases ship frequently across three Play tracks (`libre`, `tv`, `wear`), yet nothing checks website builds in CI, syncs [`Versions.kt`](../../buildSrc/src/main/kotlin/Versions.kt) metadata, or prevents silent fallback to Android debug signing (`app/unified/build.gradle.kts` and `companion/wear/build.gradle.kts` fall back to debug signing when `SPATIALFIN_KEYSTORE` is unset so clean CI runners succeed, and both resolve relative keystore paths against each subproject directory rather than the repo root). Serving generated site files from committed `main:/docs` also collides with [`docs/web/`](../web/), [`docs/images/SpatialFin-banner.png`](../images/SpatialFin-banner.png), and internal specs under `docs/designs/`.
6.  **Privacy disclosure mismatch:** [`docs/index.html`](../index.html) links to [`PRIVACY`](../../PRIVACY) ("no analytics") while loading external Google Fonts, an eager YouTube `<iframe>`, and Umami without clarifying that neither the native apps nor `/web/` collect analytics while static documentation pages use cookieless self-hosted Umami.

## Goals

*   **Search and social discoverability:** Every canonical page on [spatialfin.martinez.fyi](https://spatialfin.martinez.fyi/) emits a unique `<title>`, `<meta name="description">`, `<link rel="canonical">`, Open Graph/Twitter tags pointing to a `1200×630` preview image, valid JSON-LD, `.nojekyll`, `robots.txt`, `sitemap.xml`, and `404.html`.
*   **Multi-platform positioning without naming competitors:** The landing page hero and first two sections show all five supported surfaces (Android XR, Android Phone, Android TV, Wear OS, WebXR), state that a Jellyfin server is optional, and explain SpatialFin's playback mechanisms without naming competing Jellyfin clients (enforced as an editorial policy in [`GEMINI.md`](../../GEMINI.md)).
*   **Single user-facing guide surface:** Organize the site into four zones (10 canonical pages: `/`, `/guides/` + 6 cohesive workflow guides, `/companion/`, `/plugins/`) plus 3 legacy redirect stubs (`/smart.html`, `/companion.html`, `/plugins.html`) excluded from `sitemap.xml`, and slim [`README.md`](../../README.md) into a project overview linking directly to the website guides so prose is not duplicated across two 400-line surfaces.
*   **Consistent product naming and verified Companion integration:** Name the optional self-hosted server **SpatialFin Companion Hub** and the smartwatch app **SpatialFin for Wear OS**, align `/companion/` to verified [`SpatialFin-Companion`](https://github.com/ignacio82/SpatialFin-Companion) behavior (`master`, `docker-compose.yaml`, `docker compose up -d --build`, SearXNG, admin auth), and link out to that repo for server administration.
*   **Accurate plugin and casting ecosystem docs:** Provide a QuickJS plugin guide on `/plugins/` documenting the `:plugins` compatibility matrix, security boundaries, manifest extensions, and a PeerTube example verified by a real `:plugins` QuickJS JVM unit test, linking Grayjay resources ([Grayjay Plugin Hub](https://plugins.grayjay.app/), [Grayjay Plugin Development Guide](https://gitlab.futo.org/videostreaming/grayjay/-/blob/master/plugin-development.md)) separately from [FCast](https://fcast.org/).
*   **Visual alignment, mobile usability, and zero third-party embeds:** Adapt [`DESIGN.md`](../../DESIGN.md)'s dark cool-blue palette and TV accent highlights with a zero-download system-UI + monospace font stack, ship a keyboard-accessible `< 760px` mobile navigation drawer, commit optimized WebP screenshots under `site/images/`, replace eager YouTube `<iframe>` embeds with a click-to-load poster facade, and disclose cookieless self-hosted Umami scope on static docs pages in the footer and [`PRIVACY`](../../PRIVACY).
*   **Isolated GitHub Actions Pages deployment and deterministic release plumbing:** Compile `site/` into gitignored `_site/` and publish via GitHub Actions Pages (copying [`docs/web/`](../web/) and [`docs/CNAME`](../CNAME) read-only so `docs/web/`, `docs/CNAME`, `docs/images/SpatialFin-banner.png`, and `docs/designs/` remain isolated); share CI's exact serialized 3-AAB Gradle command (preserving `-Dcom.android.tools.r8.enableKeepAnnotations=true`); enforce fail-closed non-debug signing in Gradle; and run offline site verification plus CI browser smoke tests.
*   **Repository metadata consistency:** Reconcile stale claims across [`README.md`](../../README.md), [`CLAUDE.md`](../../CLAUDE.md), [`GEMINI.md`](../../GEMINI.md), [`PRIVACY`](../../PRIVACY), and [`fastlane/metadata/android/en-US/full_description.txt`](../../fastlane/metadata/android/en-US/full_description.txt).

## Non-Goals

*   Modifying, rebuilding, or changing the `/web/` base path or Workbox scope of the WebXR PWA inside [`docs/web/`](../web/) ([`spatialfin-webxr/vite.config.ts`](../../spatialfin-webxr/vite.config.ts)).
*   Naming competing Jellyfin clients in comparison tables (upstream Findroid fork attribution remains restricted to credits/license notices).
*   Adopting a third-party static site generator (Astro, Hugo, Eleventy, VitePress, Docusaurus) or adding an npm dependency tree/lockfile for the docs site.
*   Building client-side search indexes, light/dark theme switchers, dynamic OG-image generators, or multi-language localization scaffolding.
*   Mirroring the full server-administration manual of [`SpatialFin-Companion`](https://github.com/ignacio82/SpatialFin-Companion) or scraping its server source code during Android releases.
*   Mutating `gradle/libs.versions.toml` during automated release-bundle builds.

## Open Questions

None.

## Critical User Journeys (CUJs)

### CUJ 1: Prospective User Discovering and Evaluating SpatialFin

*   **Given:** A visitor searches for a Jellyfin client for Android XR, Android TV, Wear OS, ASS/SSA subtitles, or local/SMB playback and lands on [spatialfin.martinez.fyi](https://spatialfin.martinez.fyi/) (or a guide URL) from a desktop or mobile browser.
*   **When:** The visitor reads the hero and capability matrix, inspects device screenshots, and opens a linked guide (such as Casting & Music or Playback & Offline).
*   **Then:** The page renders readable text and navigation on desktop and `< 760px` mobile viewports with zero JS-hidden content; the visitor identifies all five supported surfaces and SpatialFin's engineering mechanisms without naming competing clients; and direct install links on `/` and the persistent header CTA reach the single Google Play Store listing (which delivers `libre`, `tv`, and `wear` tracks automatically), GitHub Releases, or `/web/`.

### CUJ 2: Existing User Setting Up a Specialized Feature or Companion Hub From a Phone

*   **Given:** An existing SpatialFin user opens [spatialfin.martinez.fyi](https://spatialfin.martinez.fyi/) on a phone while configuring a Galaxy XR headset, Android TV, Wear OS watch, or optional **SpatialFin Companion Hub**.
*   **When:** The user opens the mobile navigation drawer, navigates to `/guides/getting-started/`, `/guides/casting-music/`, `/guides/wear-os/`, or `/companion/`, and follows the prerequisites, numbered steps, and verification checks.
*   **Then:** The mobile drawer opens and closes via touch, keyboard, and Escape; menu paths, ports (`1982`, `41230`), pairing code formats (`123 456`, `K7P-9M2`), and prerequisites match the shipped app; and each page uses consistent product naming and scope labels distinguishing **SpatialFin Companion Hub** from **SpatialFin for Wear OS**.

### CUJ 3: Developer Porting a Grayjay Plugin or Authoring a SpatialFin Plugin

*   **Given:** A developer wants to port a Grayjay JavaScript plugin or build a new source plugin and visits `/plugins/` (or follows a legacy `/plugins.html` bookmark).
*   **When:** The developer reads the Grayjay compatibility matrix, copies the PeerTube `manifest.json` and `script.js`, and installs locally via **Sources → Plugins → Install Plugin** (or QR scan).
*   **Then:** `/plugins.html` redirects to `/plugins/` preserving `#hash` fragments; the guide states which Grayjay APIs work versus which are blocked or stubbed (`UrlTargetPolicy.PUBLIC_INTERNET_ONLY`, disabled `usePlatformAuth`, `GET`/`POST`-only synchronous `http.GET`, `20.1 s` timeout, stubbed `bridge.setSettings`, unenforced `scriptSignature`); the PeerTube example is verified against `:plugins` QuickJS in CI; and in-app QuickJS plugins ([Grayjay Plugin Hub](https://plugins.grayjay.app/), [Grayjay Plugin Development Guide](https://gitlab.futo.org/videostreaming/grayjay/-/blob/master/plugin-development.md)) are separated from external FCast casting ([FCast](https://fcast.org/)).

### CUJ 4: Maintainer Building Play Store Release Bundles With Synced Website Verification

*   **Given:** The maintainer bumps [`Versions.kt`](../../buildSrc/src/main/kotlin/Versions.kt) (or asks the AI via chat to build Play Store bundles for `libre`, `tv`, and `wear`).
*   **When:** The release script (`.github/scripts/release-bundles.sh`) runs tag/R8 preflight, prints user-visible commits since the last `v*` tag, builds and verifies the website into `_site/`, runs the shared serialized 3-AAB Gradle helper with fail-closed signing, and verifies certificate parity.
*   **Then:**
    *   Release signing fails fast at Gradle configuration time if `SPATIALFIN_KEYSTORE` is missing (unless `--allow-debug-signing` is passed), and all three built `.aab` files share one non-debug SHA-256 certificate fingerprint.
    *   `Versions.kt` metadata in `_site/` passes offline verification, and [`docs/web/`](../web/), [`docs/CNAME`](../CNAME), and [`docs/images/SpatialFin-banner.png`](../images/SpatialFin-banner.png) remain untouched.
    *   When changes are pushed to `main`, GitHub Actions Pages builds and deploys `_site/` automatically without committing generated HTML diffs.

## Technical Plan (Architecture)

```text
  [buildSrc/.../Versions.kt]   [site/pages/*.html]   [site/examples/peertube/*]
               \                       |                        /
                +------> [site/build.mjs (Node 22)] <----------+
               /            ^          |          ^             \
  [site/data/*.json] -------+          |          +--- [site/templates/*]
  [site/assets/*, site/images/*]       |               [docs/web/** (read-only copy)]
                                       |               [docs/CNAME (read-only copy)]
                                       v
                     [_site/ — gitignored build output]
                      ├── .nojekyll, robots.txt, sitemap.xml, 404.html
                      ├── index.html, guides/*, companion/, plugins/
                      ├── smart.html, companion.html, plugins.html (redirects)
                      ├── assets/ (style.css, site.js)
                      ├── images/ (WebP screenshots, icon.png, og-card.png, trailer-poster.webp)
                      ├── CNAME        (copied read-only from docs/CNAME)
                      └── web/**       (copied read-only from docs/web/**)
                                       |
                      +----------------+-----------------+
                      v                                  v
        [site/verify.mjs (offline)]         [.github/workflows/pages.yml]
        ├── on-disk links, #anchors,        └── upload-pages-artifact +
        │   CSS vars, SEO/JSON-LD,              deploy-pages -> spatialfin.martinez.fyi
        │   byte caps, orphan images
        └── --browser (CI web job via
            spatialfin-webxr Puppeteer)

  [.github/scripts/release-bundles.sh]
   ├── .github/scripts/tag-release.sh --check-only  (v* tag & APP_CODE guard)
   ├── R8 catalog parity check (libs.versions.toml == settings.gradle.kts)
   ├── site/build.mjs + site/verify.mjs (offline)
   ├── .github/scripts/build-release-bundles.sh     (shared with ci.yml; -PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true)
   └── keytool -printcert -jarfile parity check across libre / tv / wear .aab files
```

### 1. Site Compiler & GitHub Actions Pages Isolation (`site/` → `_site/`)

Node.js 22 is already provisioned in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) for `spatialfin-webxr/`. `site/build.mjs` uses only Node 22 built-in modules and writes exclusively to a gitignored output directory (`_site/` by default), eliminating every directory collision with committed [`docs/`](../):

*   **Source layout under `site/`:**
    *   `site/templates/`: Shared HTML5 document shell (`shell.html`) and static redirect stub (`redirect.html`).
    *   `site/data/`: Global site constants (`site.json`) and canonical/redirect route registry (`pages.json`). `site/build.mjs` validates `pages.json` schema, unique slugs, and SEO field lengths at build time.
    *   `site/pages/`: Semantic HTML fragments for all canonical pages and `404.html`, supporting scoped build-time placeholders (`{{version}}`, `{{appCode}}`, `{{minSdk}}`, `{{wearMinSdk}}`) and HTML-escaped fixture includes (`{{include:examples/...}}`).
    *   `site/examples/peertube/`: Standalone PeerTube plugin fixtures (`manifest.json`, `script.js`) included into `/plugins/` at build time and executed by a `:plugins` QuickJS JVM unit test.
    *   `site/assets/`: First-party stylesheet (`style.css`) and minimal progressive-enhancement script (`site.js`).
    *   `site/images/`: Committed website-only images (`icon.png`, `og-card.png`, `trailer-poster.webp`, and curated WebP device screenshots). [`docs/images/SpatialFin-banner.png`](../images/SpatialFin-banner.png) stays in `docs/images/` solely for [`README.md`](../../README.md) and never enters `_site/images/`.
*   **Build-time inputs & assembly:** `site/build.mjs` reads `APP_NAME`, `APP_CODE`, and `MIN_SDK` from [`Versions.kt`](../../buildSrc/src/main/kotlin/Versions.kt) and Wear `minSdk` from [`companion/wear/build.gradle.kts`](../../companion/wear/build.gradle.kts), assigns deterministic heading slug `id`s and `On this page` TOCs for docs layouts, emits `.nojekyll`, `robots.txt` (`Allow: /` + `Sitemap`, without `Disallow: /web/`), `sitemap.xml` (canonical pages only; excludes redirects, `404.html`, and `/web/`), and `404.html`, and copies [`docs/web/`](../web/) and [`docs/CNAME`](../CNAME) read-only into `_site/web/` and `_site/CNAME`.
*   **GitHub Actions Pages deployment:** `.github/workflows/pages.yml` runs `node site/build.mjs` and `node site/verify.mjs` on pushes to `main` touching website, WebXR, or version inputs, then publishes `_site/` via `actions/upload-pages-artifact` and `actions/deploy-pages`. `docs/web/` and `docs/CNAME` are never written by website tooling, `docs/designs/` is never published publicly, and Android `Versions.kt` bumps republish updated website metadata automatically with zero generated HTML diffs in git.

### 2. Information Architecture, Feature Definitions & Visual System

*   **Four-zone canonical URL layout (10 canonical pages + 3 legacy redirect stubs + `404.html`):**
    *   **Zone 1 (Discovery):** `/` (`index.html`) — Above-the-fold multi-platform overview (Android XR, Android Phone, Android TV, Wear OS, WebXR), 5-surface capability matrix noting unsupported features per platform, mechanism-led differentiation cards linked to guides, click-to-load trailer facade, multi-device screenshots, Companion Hub & Plugins summaries, single Google Play Store listing (`libre`/`tv`/`wear` tracks delivered automatically per device) + GitHub Releases + `/web/` install block, and FAQ.
    *   **Zone 2 (User Guides):** `/guides/` hub (plain grouped HTML sections, no filter JS) plus 6 cohesive workflow guides following a consistent 5-part structure (*What it does & optionality → Prerequisites → Step-by-step → Verify it works → Troubleshooting*):
        1.  `/guides/getting-started/` — 3-choice onboarding (Jellyfin server, Quick Connect `123 456` / Android LAN `/setup` on port `41230` with code `K7P-9M2` via [`LanSetupServer.kt`](../../setup/src/main/java/dev/jdtech/jellyfin/setup/lan/LanSetupServer.kt), or no-server local/SMB/NFS/plugin mode) and device approval.
        2.  `/guides/xr-cinema-voice/` — Android XR Home Space vs. Full Space; curved cinema presets (`Flat`, `Subtle 9.0m`, `Cinema 6.5m`, `Deep 5.2m`); graduated passthrough dimming (`0%–100%`); persistence model (browsing panel scale `xrAppPanelScale` via [`UnifiedMainActivity.kt`](../../app/unified/src/main/java/dev/spatialfin/unified/UnifiedMainActivity.kt); free-floating player spawns centered while preserving `xrPlayerPanelScale` and `xrPlayerPanelDepth` via [`PlayerPoseStorage.kt`](../../player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerPoseStorage.kt); cross-session 3D room placement exclusively via optional ARCore wall anchoring on flat cinema via [`WallAnchorController.kt`](../../player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/WallAnchorController.kt)); stereoscopic 3D & spatial audio; and XR + Android phone Voice AI (open-palm trigger / closed-fist interrupt, on-device `gemma-4-E2B-it.litertlm` / AICore Gemini Nano / optional `gemini-3.1-flash-lite-preview`, 20-minute rolling subtitle buffer with disk fallback, `"Who is this?"` spatial cast card).
        3.  `/guides/playback-offline/` — Smart Playback (ordered spoken languages, original audio preference, forced vs. full subtitles, per-series memory), `libass` ASS/SSA, live audio (`±5 s`) and subtitle (`±10 s`) sync, 4-tier intro/outro skip (Jellyfin Media Segments → legacy Intro Skipper → embedded chapter titles → AniSkip), bitstream passthrough, Stats for Nerds, resumable downloads to `Download/SpatialFin` ([`DownloadStorageManager.kt`](../../data/src/main/java/dev/jdtech/jellyfin/downloads/DownloadStorageManager.kt)), automatic deletion of watched downloads, pre-flight and airplane-mode on-device Media3 `Transformer` transcoding, download integrity verification, SMB/NFS + TMDB, local library & system `"Open With"`, and App Lock (Biometric vs. PBKDF2 PIN) + AES-256-CTR encrypted downloads + threat model.
        4.  `/guides/casting-music/` — Multi-protocol casting (FCast v4, Google Cast, unencrypted AirPlay v1 video-only per [`FCastSessionPickerSheet.kt`](../../fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/FCastSessionPickerSheet.kt), explicitly noting audio-only AirPlay speakers and AirPlay 2 pairing are unsupported), subtitle-fidelity preservation & burn-in fallback, Split-A/V (local video on XR/phone paired with remote private earbud/headphone audio over FCast, Wear OS, or WebXR with live audio-delay calibration), SyncPlay, LAN remote control, Music Assistant controller (queue/playlist/party mode), SendSpin synchronized multi-room audio endpoint, and explicit off-LAN WebRTC limits (SendSpin audio, home rows, cover art, and basic playback/group control work off-LAN; full search/detail browsing remains LAN-only; TURN hard-NAT relay pending).
        5.  `/guides/wear-os/` — **SpatialFin for Wear OS** (`minSdk 33`, third Play Store track under `dev.spatialfin`), rotary crown scrubbing, ProtoLayout tiles & complications, wrist voice transcript relay, and Split-A/V private earbud audio.
        6.  `/guides/webxr/` — SpatialFin WebXR (`/web/`) Chrome 142 Local Network Access, Jellyfin Dashboard CORS origin setup, Tailscale Serve HTTPS fallback, `wss://` requirement for Companion FCast receiver, and PWA offline & native feature parity table.
    *   **Zone 3 (Self-Hosted Companion Server):** `/companion/` — **SpatialFin Companion Hub** (optional self-hosted Docker server, distinct from **SpatialFin for Wear OS**): verified quick-start from [`SpatialFin-Companion`](https://github.com/ignacio82/SpatialFin-Companion) (`master`, `docker-compose.yaml`, `docker compose up -d --build`, port `1982`), first-time admin auth (`bootstrapPin` / `COMPANION_ADMIN_PASSWORD`), bundled SearXNG web-search sidecar (`GET /api/v1/search`, `COMPANION_SEARXNG_URL`), `GET /healthz`, `COMPANION_AUTO_UPDATE=false`, plain-English QR / 6-char Quick Connect pairing overview (placing `qc_broker_v1` and `X-Setup-Token` inside an optional technical disclosure), remote logs, local-only App Lock boundary, and link to [`SpatialFin-Companion`](https://github.com/ignacio82/SpatialFin-Companion) for full server administration.
    *   **Zone 4 (Developer & Extension Docs):** `/plugins/` — QuickJS runtime architecture (fresh VM per call, `20.1 s` (`20,100 ms`) timeout, 2 MiB download / 4 MiB response caps), Grayjay compatibility matrix citing `:plugins` sources ([`PluginModels.kt`](../../plugins/src/main/java/dev/jdtech/jellyfin/plugins/model/PluginModels.kt), [`PluginEngine.kt`](../../plugins/src/main/java/dev/jdtech/jellyfin/plugins/engine/PluginEngine.kt), [`PluginClient.kt`](../../plugins/src/main/java/dev/jdtech/jellyfin/plugins/engine/PluginClient.kt), [`RealHttpBridge.kt`](../../plugins/src/main/java/dev/jdtech/jellyfin/plugins/bridge/RealHttpBridge.kt), [`PluginContentRepository.kt`](../../plugins/src/main/java/dev/jdtech/jellyfin/plugins/repository/PluginContentRepository.kt)): synchronous `http.GET`/`POST` only, `UrlTargetPolicy.PUBLIC_INTERNET_ONLY` SSRF guard (contrasted with manual LAN HTTP plugin install), disabled `usePlatformAuth` cookies, `PluginSetting` auth flow, stubbed `bridge.isLoggedIn` / `bridge.getSettings` / `bridge.setSettings` (with empty `savedState` passed to `source.enable(config, settings, '')` and uncalled `source.saveState()`), unenforced `scriptSignature` security warning, SpatialFin `PluginConfig` extensions (`homeRows`, `homeRowTemplates`, `presentation: "verticalVideoFeed"`, `projection`, `stereoMode`, `isLive`), CI-tested PeerTube example, verified UI install path (**Sources → Plugins → Install Plugin** or QR scan), outbound links to [Grayjay Plugin Hub](https://plugins.grayjay.app/) and [Grayjay Plugin Development Guide](https://gitlab.futo.org/videostreaming/grayjay/-/blob/master/plugin-development.md), and a separate FCast casting callout linking to [FCast](https://fcast.org/).
    *   **Legacy Redirects (excluded from `sitemap.xml`):** `/smart.html` → `/guides/playback-offline/`, `/companion.html` → `/companion/`, `/plugins.html` → `/plugins/`.
*   **Visual tokens, typography, navigation & privacy:**
    *   `site/assets/style.css` adapts [`DESIGN.md`](../../DESIGN.md)'s dark cool-blue base palette, TV accent colors, radii, and spacing tokens, adding two web-only elevated surface shades (`#171A21`, `#1E222C`).
    *   Typography uses a zero-download system-UI sans and monospace stack aligned with [`DESIGN.md`](../../DESIGN.md) line 213 — removing Google Fonts requests, WOFF2 binary commits, and font-swap layout shifts.
    *   Global header provides 5 links (`Platforms`, `Guides`, `Companion`, `Plugins`, `Web App`) plus a persistent `Get the App` CTA, `aria-current="page"`, and a `< 760px` button (`aria-expanded`, `aria-controls`, Escape-to-close) opening a full-link mobile drawer.
    *   Replace eager YouTube `<iframe>` embeds with a click-to-load local poster facade (`PMHOc9WXA9s`, with `<noscript>` watch link). Load cookieless self-hosted Umami (`https://umami.martinez.fyi/script.js`) on canonical docs pages and `404.html` (never on redirect stubs, native apps, or `/web/`), documented in the global footer and [`PRIVACY`](../../PRIVACY).

### 3. Committed Media Assets (No Custom Import Pipeline)

Because [`.gitignore`](../../.gitignore) excludes `fastlane/metadata/android/en-US/images/` and `/screenshots/`, optimized screenshots are converted ad-hoc using `ffmpeg` (already installed locally) and committed directly under `site/images/` with no custom import script or local-path manifest:

```bash
ffmpeg -i <source.png> -vf "scale='min(1440,iw)':-2" -map_metadata -1 -c:v libwebp -quality 82 site/images/<target>.webp
```

Deterministic recipes generate `site/images/og-card.png` (`1200×630`, `<= 500 KB`) and `site/images/trailer-poster.webp` (`<= 250 KB`) as specified in Phase 2 of the Detailed Implementation Plan.

### 4. Verification Architecture (`site/verify.mjs` & `:plugins` QuickJS Test)

*   **Fast offline on-disk checks (`node site/verify.mjs`, target runtime `< 5 s` locally and `< 10 s` in CI `website` job):**
    *   Runs `site/build.mjs` into `_site/` (or verifies an existing build directory via `--out`), validates `_site/CNAME`, `_site/.nojekyll`, `_site/web/index.html`, and `_site/web/manifest.webmanifest`.
    *   Resolves all internal `href`, `src`, and `#fragment` targets on disk against `_site/` after stripping `<pre>`, `<code>`, `<script>`, `<style>`, and HTML comments so code examples never trigger false positives.
    *   Applies a per-page-class SEO/HTML validation matrix separating the 10 canonical pages (unique `<title>`, `<meta name="description">`, single `<h1>`, sequential heading nesting, `<link rel="canonical">` parity with `sitemap.xml`, OG/Twitter tags, valid JSON-LD with `softwareVersion === APP_NAME` scoped strictly to `SoftwareApplication` on `/`, Umami script presence), the 3 redirect stubs (`meta refresh`, canonical, `noindex, follow`, no Umami), and `404.html` (excluded from `sitemap.xml`, Umami present).
    *   Checks `:root` definitions for every CSS `var(--*)` usage, `<img>` `width`/`height`/`alt`, PNG IHDR dimensions (`>= 1200×630`) on `og-card.png`, per-file and total image byte caps, orphan image detection collecting `<img>`, `<link rel="icon">`, `og:image`, and `twitter:image` references, and PeerTube fixture syntax (`JSON.parse` + `node --check`).
*   **CI headless browser audit (`node site/verify.mjs --browser`, run in existing CI `web` job):**
    *   Resolves Puppeteer from `spatialfin-webxr/package.json` via `createRequire` (failing with `cd spatialfin-webxr && npm ci` instructions when absent), serves `_site/` on an ephemeral `127.0.0.1` loopback port, and checks `1440×900`, `768×1024`, and `390×844` viewports for zero `console.error`/`pageerror`, zero horizontal overflow, working `< 760px` mobile drawer open/close with `aria-expanded` / Escape-key close / `aria-current="page"`, loaded images, and primary text visible with JavaScript disabled.
*   **Real `:plugins` QuickJS runtime verification:**
    *   A JVM unit test in `:plugins` (`DocsPeerTubeExampleTest.kt`) deserializes `site/examples/peertube/manifest.json` with Kotlin `PluginConfig` and executes `site/examples/peertube/script.js` inside `quickjs-kt-jvm` against `okhttp-mockwebserver`, added to [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) via `:plugins:testLibreDebugUnitTest`.

### 5. Shared Release-Bundle Plumbing & CI Wiring

Release automation lives alongside existing repo scripts in `.github/scripts/`:

*   **Shared Gradle signing helper (`buildSrc/src/main/kotlin/SpatialfinSigning.kt`):** Replaces duplicated keystore lookup in [`app/unified/build.gradle.kts`](../../app/unified/build.gradle.kts) and [`companion/wear/build.gradle.kts`](../../companion/wear/build.gradle.kts), resolves paths via `rootProject.file(...)` (fixing subproject-relative path divergence), and honors `-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true` to fail fast at Gradle configuration time when signing credentials are absent while preserving CI's debug fallback when unset.
*   **Tag & version monotonicity check (`.github/scripts/tag-release.sh --check-only`):** Adds a read-only `--check-only` flag (with shallow-clone warning) to existing [`tag-release.sh`](../../.github/scripts/tag-release.sh) (tag format `v<APP_NAME>`, checking local + `origin` tags and asserting `APP_CODE` exceeds the highest tagged code) instead of duplicating tag logic.
*   **Shared serialized 3-AAB builder (`.github/scripts/build-release-bundles.sh`):** Single script invoked by both [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) and `.github/scripts/release-bundles.sh`, executing `:app:unified:bundleLibreRelease`, `:app:unified:bundleTvRelease`, and `:companion:wear:bundleRelease` serially with CI's exact OOM- and R8-safe JVM flags (including `-Dcom.android.tools.r8.enableKeepAnnotations=true`).
*   **Offline-capable release orchestrator (`.github/scripts/release-bundles.sh`):** Supports `--site-only`, `--check-only`, `--allow-debug-signing`, and `--skip-site` (rejecting conflicting flags):
    1.  Preflight: runs `.github/scripts/tag-release.sh --check-only` and verifies `r8` in [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml) matches [`settings.gradle.kts`](../../settings.gradle.kts) line 42. Never invokes mutating `./gradlew versionCatalogUpdate`.
    2.  Release diff surface: prints user-visible commits since the last `v*` tag (falling back to the last 20 commits if no `v*` tag exists) so the maintainer or AI updates `site/pages/` and `README.md` only when user-facing capabilities changed.
    3.  Website build & verify: runs `node site/build.mjs` and `node site/verify.mjs` offline.
    4.  Serialized 3-AAB build: calls `.github/scripts/build-release-bundles.sh` passing `-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true` (omitted only under `--allow-debug-signing`).
    5.  Certificate parity check: runs JDK 21 `keytool -printcert -jarfile` across all three `.aab` files, asserting identical `SHA256:` fingerprints and rejecting `CN=Android Debug` unless `--allow-debug-signing` is set.
    6.  Summary & tag handoff: prints output `.aab` paths, byte sizes, Play version codes (`libre`, `tv`, `wear`), SHA-256 checksums, and next-step commit + `.github/scripts/tag-release.sh` instructions without auto-committing or auto-pushing.
*   **CI & AI assistant integration:** Add a standalone zero-install Node 22 `website` job to [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) (running `node --test site/test/site-scripts.test.mjs`, `node site/build.mjs`, `node site/verify.mjs`), add `node site/verify.mjs --browser` to the existing `web` job, add `:plugins:testLibreDebugUnitTest` to the Android unit-test step, add `.github/workflows/pages.yml`, and document the canonical workflow once in [`GEMINI.md`](../../GEMINI.md) (referenced by [`CLAUDE.md`](../../CLAUDE.md)).

## Alternatives Considered

### Alternative A: Third-Party Static Site Generator (Astro / Eleventy / VitePress / Hugo)

*   **Pros:** Mature Markdown and template ecosystems with built-in syntax highlighting.
*   **Cons:** Introduces a second web toolchain, dependency tree, and lockfile alongside `spatialfin-webxr/` for a 10-page static documentation site, adding ongoing package-update and security-advisory maintenance.
*   **Reason for Rejection:** A ~200-line zero-dependency Node 22 template compiler reuses the Node 22 runtime already provisioned in CI, needs zero npm packages, and keeps build logic inspectable in one file.

### Alternative B: Branch-Based `main:/docs` Publishing Instead of GitHub Actions Pages (`_site/`)

*   **Pros:** Requires zero changes to GitHub repository Pages settings and keeps rendered HTML browsable directly in git.
*   **Cons:** Committing generated HTML into `docs/` collides with [`docs/web/`](../web/), [`docs/images/SpatialFin-banner.png`](../images/SpatialFin-banner.png), and `docs/designs/` (publicly exposing internal design specs under `/designs/`), duplicates assets or splits source ownership, triggers multi-file CI freshness failures whenever `Versions.kt` bumps, and requires overlapping manifest/git/hash guards.
*   **Reason for Rejection:** Emitting to gitignored `_site/` and deploying via GitHub Actions Pages isolates `docs/web/`, `docs/CNAME`, `docs/images/SpatialFin-banner.png`, and `docs/designs/` structurally and republishes `Versions.kt` updates with zero generated HTML diffs in git.

### Alternative C: Hand-Edited Flat HTML With No Templating

*   **Pros:** Zero build script and zero source-to-output indirection.
*   **Cons:** Four hand-edited files already drifted into mismatched navigation bars, hidden mobile navigation, undefined CSS variables, missing footers/analytics, and skipped heading levels. Keeping 10 pages, JSON-LD, `Versions.kt`, and `sitemap.xml` synchronized by hand repeats that drift on every release.
*   **Reason for Rejection:** Shared layout templates and `pages.json` metadata eliminate repeated page chrome and keep version and sitemap metadata deterministic.

### Alternative D: Self-Hosted WOFF2 Webfonts Instead of System-UI + Monospace Stack

*   **Pros:** Renders identical letterforms across Android, macOS, Windows, and Linux without external font requests.
*   **Cons:** Adds committed binary `.woff2` files, OFL license tracking, `@font-face` weight/subset maintenance, `<link rel="preload" crossorigin>` overhead, and font-swap layout shifts, while [`DESIGN.md`](../../DESIGN.md) line 213 already specifies platform system fonts.
*   **Reason for Rejection:** A zero-download system-UI and monospace font stack matches `DESIGN.md`, achieves zero font transfer bytes and zero font-swap CLS, and avoids committing binary font assets.

### Alternative E: Named Competitor Comparison Matrix & Automated Competitor Denylist Linter

*   **Pros:** Matches head-to-head search queries; an automated regex linter could enforce editorial rules in CI.
*   **Cons:** Competitor feature tables go stale quickly and invite correction churn when other clients change. Maintaining a 27-term regex linter with DOM exemptions (`X-Emby-Token`, `Findroid` credits) false-positives on common English words (`Infuse`) and adds parser complexity for a solo-maintainer editorial rule.
*   **Reason for Rejection:** Mechanism-led differentiation paired with a 5-surface SpatialFin capability matrix communicates strengths directly, and a one-line editorial policy in [`GEMINI.md`](../../GEMINI.md) avoids false-positive linter maintenance.

### Alternative F: Automated Cross-Repo `SpatialFin-Companion` Code Scraper & Custom External HTTP Crawler

*   **Pros:** Detects upstream `SpatialFin-Companion` changes or dead outbound links automatically during release runs.
*   **Cons:** Companion API endpoints live in `index.js` rather than `README.md`, coupling SpatialFin Android releases to an external repo's internal file layout and causing false-positive blocks on unrelated Companion commits; custom HTTP crawlers hit bot blocks (`403`/`429` on Google Play) and break offline urgent hotfixes.
*   **Reason for Rejection:** Fixing `/companion/` directly against verified upstream facts (`master`, `8668c4d2`) and linking out to [`SpatialFin-Companion`](https://github.com/ignacio82/SpatialFin-Companion) keeps Android releases offline-capable; scheduled link audits can later use a standard tool (`lychee`) in an independent cron workflow.

### Alternative G: Local Asset-Import Script (`sync-website-images.mjs`) Reading Gitignored Folders

*   **Pros:** Automates resizing captures from `fastlane/metadata/android/en-US/images/` and `/screenshots/`.
*   **Cons:** Both source directories are gitignored, so the script cannot run in CI or clean clones, and maintaining a dedicated Node script + JSON manifest duplicates a one-line `ffmpeg` command for occasional manual screenshot refreshes.
*   **Reason for Rejection:** Committing optimized WebP screenshots under `site/images/` with a documented `ffmpeg` command keeps builds reproducible from git without maintaining unused local-only tooling.

### Alternative H: Prompt-Only AI Instructions Owning Website Verification and Play Bundle Builds

*   **Pros:** Requires no standalone shell or Node scripts.
*   **Cons:** LLM-driven command assembly is non-deterministic across chat sessions, risking omitted R8 keep-annotation flags, silent debug signing, skipped bundles, or missed SEO/layout regressions.
*   **Reason for Rejection:** Deterministic scripts under `site/` and `.github/scripts/` make verification and 3-bundle builds repeatable regardless of which AI tool or human invokes them.

## Non-Functional Requirements

*   **Security & Privacy:**
    *   Eliminate third-party font requests (`fonts.googleapis.com`) and eager third-party YouTube `<iframe>` embeds.
    *   Load cookieless self-hosted Umami (`https://umami.martinez.fyi/script.js`) only on static canonical docs pages and `404.html` (never on redirect stubs, native apps, or `/web/`), and synchronize that disclosure in the footer and [`PRIVACY`](../../PRIVACY).
    *   Enforce non-debug release signing at Gradle configuration time via `-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true` and verify identical SHA-256 certificate fingerprints across `libre`, `tv`, and `wear` via JDK 21 `keytool` so Wear OS Data Layer pairing cannot break silently.
    *   Document QuickJS sandbox limits (`UrlTargetPolicy.PUBLIC_INTERNET_ONLY`, disabled `usePlatformAuth`, 2 MiB / 4 MiB caps) and display a prominent warning on `/plugins/` that third-party plugin `scriptSignature` verification is not enforced.
*   **Performance, Storage & CI Budget:**
    *   Offline build + static verification (`node site/build.mjs && node site/verify.mjs`): target runtime `< 5 s` locally, `< 10 s` in a standalone CI `website` job without adding RAM load to the serialized Android `build` job.
    *   Headless browser audit (`node site/verify.mjs --browser`): runs inside the existing `web` CI job where `spatialfin-webxr/node_modules/puppeteer` is already installed.
    *   Asset caps: `<= 350 KB` per screenshot WebP, `<= 250 KB` for `trailer-poster.webp`, `<= 500 KB` for `og-card.png`, and `<= 15 MB` total `site/images/` — protecting page load speed and GitHub Pages' 1 GB site / 100 GB monthly soft bandwidth limits.
*   **Rollout, Cutover & Rollback:**
    *   Preserve inbound links to legacy URLs via static redirect stubs at `/smart.html`, `/companion.html`, and `/plugins.html`.
    *   **One-time GitHub Pages cutover:** Immediately upon merging the implementation PR, switch GitHub repo **Settings → Pages → Build and deployment → Source** from `Deploy from a branch (main /docs)` to `GitHub Actions` and verify `.github/workflows/pages.yml` deploys cleanly to [spatialfin.martinez.fyi](https://spatialfin.martinez.fyi/) (including `/web/`).
    *   **Rollback:** Flip GitHub Pages Source back to `main /docs` or revert the cutover commit; [`docs/web/`](../web/), [`docs/CNAME`](../CNAME), and [`docs/images/SpatialFin-banner.png`](../images/SpatialFin-banner.png) remain untouched throughout.

## Risks & Mitigations

*   **One-time GitHub Pages Source switchover timing:** Deleting legacy `docs/*.html` before switching Pages Source to `GitHub Actions` would temporarily break `https://spatialfin.martinez.fyi/` until the setting is flipped. *Mitigation:* Deploy and verify `.github/workflows/pages.yml` first, flip Settings → Pages to `GitHub Actions`, confirm HTTP 200 on `/`, `/guides/`, `/companion/`, `/plugins/`, and `/web/`, and delete legacy `docs/{index,smart,companion,plugins}.html`, `docs/style.css`, and legacy `docs/images/*` (except `SpatialFin-banner.png`) in the cutover follow-up or immediately after switchover.
*   **GitHub Pages operational limits:** GitHub Pages uses case-sensitive routing, cannot send HTTP 301 headers for static redirect stubs, and enforces a 1 GB site / 100 GB monthly soft bandwidth limit. *Mitigation:* Enforce lowercase slugs in `site/build.mjs`, combine `<meta http-equiv="refresh">`, `<link rel="canonical">`, `noindex, follow`, and `#hash`-preserving JS redirects on legacy stubs, and enforce per-file and 15 MB total image byte caps in `site/verify.mjs`.
*   **`/web/` Workbox service-worker scope isolation:** Copying `docs/web/` into `_site/web/` must preserve its `/web/` path prefix and `manifest.webmanifest` without injecting docs scripts or headers into `/web/`. *Mitigation:* `site/build.mjs` performs a verbatim byte-for-byte directory copy of `docs/web/` into `_site/web/`, and `site/verify.mjs` checks `_site/web/index.html` and `_site/web/manifest.webmanifest` exist untouched.
*   **Silent prose drift on non-conventional commits:** `.github/scripts/release-bundles.sh` surfaces `git log <last-v-tag>..HEAD` for maintainer/AI review, but poorly worded commits can still omit user-facing changes. *Mitigation:* Keep user-facing prose single-homed on the website (with slimmed `README.md` linking out), enforce `Versions.kt` and PeerTube plugin parity automatically in CI, and print all commits since the last `v*` tag (not only `feat:` commits).
*   **Third-party QuickJS plugin code execution:** Installing unverified third-party QuickJS scripts via manual LAN HTTP or QR carries code-execution risk while `scriptSignature` is unenforced. *Mitigation:* Place an explicit security warning banner on `/plugins/` distinguishing manual local install from runtime `UrlTargetPolicy.PUBLIC_INTERNET_ONLY` network isolation.
*   **Zero-dependency HTML scanning fragility:** Regex scanning of HTML/CSS can misfire on `<pre><code>` snippets or HTML comments. *Mitigation:* Strip `<pre>`, `<code>`, `<script>`, `<style>`, and `<!--...-->` before scanning headings, anchors, or tags, and cover code-block edge cases in `site/test/site-scripts.test.mjs`.

## Additional Resources

*   **Product & design references:** [`README.md`](../../README.md), [`DESIGN.md`](../../DESIGN.md), [`GEMINI.md`](../../GEMINI.md), [`CLAUDE.md`](../../CLAUDE.md), [`PRIVACY`](../../PRIVACY)
*   **Build & release plumbing:** [`Versions.kt`](../../buildSrc/src/main/kotlin/Versions.kt), [`ci.yml`](../../.github/workflows/ci.yml), [`tag-release.sh`](../../.github/scripts/tag-release.sh), [`settings.gradle.kts`](../../settings.gradle.kts), [`gradle.properties`](../../gradle.properties), [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml), [`vite.config.ts`](../../spatialfin-webxr/vite.config.ts), [`app/unified/build.gradle.kts`](../../app/unified/build.gradle.kts), [`companion/wear/build.gradle.kts`](../../companion/wear/build.gradle.kts), [`plugins/build.gradle.kts`](../../plugins/build.gradle.kts)
*   **Verified source citations:** [`UnifiedMainActivity.kt`](../../app/unified/src/main/java/dev/spatialfin/unified/UnifiedMainActivity.kt), [`PlayerPoseStorage.kt`](../../player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerPoseStorage.kt), [`WallAnchorController.kt`](../../player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/WallAnchorController.kt), [`FCastSessionPickerSheet.kt`](../../fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/FCastSessionPickerSheet.kt), [`LanSetupServer.kt`](../../setup/src/main/java/dev/jdtech/jellyfin/setup/lan/LanSetupServer.kt), [`DownloadStorageManager.kt`](../../data/src/main/java/dev/jdtech/jellyfin/downloads/DownloadStorageManager.kt), [`PluginModels.kt`](../../plugins/src/main/java/dev/jdtech/jellyfin/plugins/model/PluginModels.kt), [`PluginEngine.kt`](../../plugins/src/main/java/dev/jdtech/jellyfin/plugins/engine/PluginEngine.kt), [`PluginClient.kt`](../../plugins/src/main/java/dev/jdtech/jellyfin/plugins/engine/PluginClient.kt), [`RealHttpBridge.kt`](../../plugins/src/main/java/dev/jdtech/jellyfin/plugins/bridge/RealHttpBridge.kt), [`PluginContentRepository.kt`](../../plugins/src/main/java/dev/jdtech/jellyfin/plugins/repository/PluginContentRepository.kt)
*   **External ecosystem references:** [Grayjay Plugin Hub](https://plugins.grayjay.app/), [Grayjay Plugin Development Guide](https://gitlab.futo.org/videostreaming/grayjay/-/blob/master/plugin-development.md), [FCast](https://fcast.org/), [SpatialFin Companion Hub](https://github.com/ignacio82/SpatialFin-Companion)

## Future Improvements

*   **Scheduled external link audit:** Add a weekly GitHub Actions cron workflow running `lychee` over `_site/` (plus a `https://discord.com/api/v10/invites/9MHD52r9` check) so dead third-party links or expired Discord invites are caught between Play releases without blocking PRs or offline hotfixes.
*   **Automated emulator screenshot refresh:** Connect [`TabletScreenshotActivity.kt`](../../app/unified/src/debug/java/dev/spatialfin/unified/screenshots/TabletScreenshotActivity.kt) and Wear [`StoreScreenshotActivity.kt`](../../companion/wear/src/debug/java/dev/spatialfin/companion/wear/screenshots/StoreScreenshotActivity.kt) to an opt-in capture script once automated capture pipelines are needed.
*   **Vendor slash-command wrappers:** Add optional `.claude/commands/release-bundles.md` (`$ARGUMENTS` pass-through) and `.gemini/commands/release-bundles.toml` (`description`, `prompt`, `{{args}}`) if chat instructions in `GEMINI.md` prove insufficient.
*   **Lightweight client-side guide search:** Consider a build-time JSON index only if the guide set grows past ~25 pages.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

Work is partitioned into four incremental phases; each phase leaves the repository stable, buildable, and verified by its checkpoint command.

### Phase 1: Schemas, Templates, Assets, Examples, Site Compiler/Verifier & Tests

#### Target Files to Create, Modify, or Delete

1.  **`.gitignore` (Modify):** Add `_site/` to ignored build outputs.
2.  **`site/data/site.json` (Create):** Global constants with exact schema and canonical repository defaults:

    ```json
    {
      "siteName": "SpatialFin",
      "origin": "https://spatialfin.martinez.fyi",
      "repoUrl": "https://github.com/ignacio82/spatialfin",
      "playStoreUrl": "https://play.google.com/store/apps/details?id=dev.spatialfin",
      "releasesUrl": "https://github.com/ignacio82/spatialfin/releases",
      "discordUrl": "https://discord.gg/9MHD52r9",
      "privacyUrl": "https://raw.githubusercontent.com/ignacio82/spatialfin/refs/heads/main/PRIVACY",
      "companionRepoUrl": "https://github.com/ignacio82/SpatialFin-Companion",
      "grayjayHubUrl": "https://plugins.grayjay.app/",
      "grayjayDocsUrl": "https://gitlab.futo.org/videostreaming/grayjay/-/blob/master/plugin-development.md",
      "fcastUrl": "https://fcast.org/",
      "youtubeTrailerId": "PMHOc9WXA9s",
      "umamiSrc": "https://umami.martinez.fyi/script.js",
      "umamiWebsiteId": "536d31bc-9ef8-4e22-aa79-3c8a97a11da5",
      "defaultOgImage": "/images/og-card.png"
    }
    ```

3.  **`site/data/pages.json` (Create):** Array of route descriptors covering all 10 canonical pages, 3 legacy redirect stubs, and `404.html`:

    ```json
    [
      {
        "kind": "page",
        "slug": "/",
        "outputPath": "index.html",
        "zone": "discovery",
        "layout": "landing",
        "title": "SpatialFin — Android XR, Phone, TV, Wear OS & WebXR Media Client",
        "description": "Open-source media client for Android XR, phones, Android TV, Wear OS, and WebXR with libass subtitles, Split-A/V casting, voice AI, and QuickJS plugins.",
        "schemaType": "SoftwareApplication",
        "fragmentPath": "site/pages/index.html",
        "ogImage": "/images/og-card.png"
      },
      {
        "kind": "redirect",
        "slug": "/smart.html",
        "outputPath": "smart.html",
        "redirectTo": "/guides/playback-offline/",
        "noindex": true
      },
      {
        "kind": "notFound",
        "slug": "/404.html",
        "outputPath": "404.html",
        "layout": "landing",
        "title": "Page Not Found — SpatialFin",
        "description": "The requested SpatialFin documentation page could not be found.",
        "noindex": true,
        "fragmentPath": "site/pages/404.html"
      }
    ]
    ```

    *   Allowed enums: `kind` ∈ `{"page", "redirect", "notFound"}`; `zone` ∈ `{"discovery", "guides", "companion", "plugins"}`; `layout` ∈ `{"landing", "docs"}`; `schemaType` ∈ `{"SoftwareApplication", "WebSite", "HowTo", "TechArticle"}` (docs pages also emit `BreadcrumbList`). Docs entries optionally include `sidebarGroup`, `prev`, and `next`.

4.  **`site/templates/shell.html` and `site/templates/redirect.html` (Create):**
    *   `shell.html` slots: `{{title}}`, `{{description}}`, `{{canonicalTag}}`, `{{robotsMeta}}`, `{{ogMeta}}`, `{{jsonLd}}`, `{{umamiTag}}`, `{{navLinks}}`, `{{layoutClass}}`, `{{sidebarNav}}`, `{{breadcrumbs}}`, `{{content}}`, `{{prevNext}}`, `{{toc}}`, `{{version}}`.
    *   All asset URLs are root-relative (`/assets/style.css`, `/assets/site.js`, `/images/...`) so `404.html` and nested `/guides/*/` pages resolve identically.
    *   Header has 5 links (`/#platforms` `Platforms`, `/guides/` `Guides`, `/companion/` `Companion`, `/plugins/` `Plugins`, `/web/` `Web App`), persistent `/#download` `Get the App` CTA, `aria-current="page"` on the active route, and `<button data-nav-toggle aria-expanded="false" aria-controls="mobile-nav">` below `760px`.
    *   Footer renders build-time `v{{version}}` badge and app-vs-website privacy notice (neither native apps nor `/web/` collect analytics; static docs pages use cookieless self-hosted Umami).
    *   `redirect.html` slots: `{{redirectTo}}`, `{{canonicalUrl}}`. Emits `<meta http-equiv="refresh" content="0; url=...">`, `<link rel="canonical">`, `<meta name="robots" content="noindex, follow">`, inline `#hash`-preserving `location.replace`, visible fallback link, and zero Umami script.

5.  **`site/assets/style.css` and `site/assets/site.js` (Create):**
    *   `style.css`: `:root` tokens adapting `DESIGN.md` dark cool-blue palette (`--bg: #111318`, `--primary: #A4C9FE`, `--on-primary: #00315C`, `--primary-container: #1F4876`, `--text: #E1E2E8`, `--text-muted: #C3C6CF`, `--border: #43474E`), TV accent highlights (`--accent-cyan: #7DDAFF`, `--accent-mint: #6EE4C5`, `--accent-amber: #FFBE74`), web-only elevated surfaces (`--surface: #171A21`, `--surface-elevated: #1E222C`, `--surface-glass: rgba(17, 19, 24, 0.88)`), radii (`10/16/32/9999px`), spacing (`4/8/16/24/32/64px`), and system-UI + monospace font stacks (`ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif` and `ui-monospace, SFMono-Regular, Menlo, Consolas, monospace`). Keeps all primary content visible without JS (no `opacity: 0` or `display: none` content gating).
    *   `site.js`: Three progressive-enhancement behaviors with explicit DOM contracts:
        *   Mobile drawer: toggles `#mobile-nav` via `[data-nav-toggle]`, updates `aria-expanded`, closes on `Escape` and link click.
        *   Code copy buttons: injects a `<button class="copy-btn">` onto every `pre > code` block using `navigator.clipboard.writeText`.
        *   Trailer facade: activates `[data-youtube-id="PMHOc9WXA9s"]` on click, swapping the local `<img>` poster for an `https://www.youtube-nocookie.com/embed/PMHOc9WXA9s?autoplay=1` `<iframe>`; static HTML retains a `<noscript>` direct YouTube link.

6.  **`site/examples/peertube/manifest.json` and `site/examples/peertube/script.js` (Create):**
    *   Minimal runnable PeerTube plugin conforming to [`PluginModels.kt`](../../plugins/src/main/java/dev/jdtech/jellyfin/plugins/model/PluginModels.kt) (`PluginConfig`: `id`, `name`, `author`, `description`, `version`, `scriptUrl`, `capabilities: ["home", "search", "resolve"]`, `settings: [PluginSetting]`) and implementing `source.enable(config, settings, savedState)`, `source.getHome()`, `source.search(query)`, and `source.resolveVideoUrl(videoUrl)` using synchronous `http.GET(url, headers)` returning `{ isOk, code, body, headers }` against public HTTPS endpoints (`UrlTargetPolicy.PUBLIC_INTERNET_ONLY`) and returning a media stream URL strictly distinct from `videoUrl` (per `PluginContentRepository.kt` line 222).

7.  **`site/build.mjs` (Create):**
    *   Zero-dependency Node 22 ESM compiler exporting `buildSite({ rootDir, outDir })` and supporting CLI flags `--root <dir>` (default repo root) and `--out <dir>` (default `_site`).
    *   Reads `APP_NAME`, `APP_CODE`, `MIN_SDK` from `buildSrc/src/main/kotlin/Versions.kt` and Wear `minSdk` from `companion/wear/build.gradle.kts` (aborting with non-zero exit if any regex fails).
    *   Validates `site/data/pages.json` schema, unique slugs/titles/descriptions, and SEO length bounds (`<title>` 20–65 chars, `<meta name="description">` 70–160 chars for `kind: "page"`).
    *   Expands only the known placeholders (`{{version}}`, `{{appCode}}`, `{{minSdk}}`, `{{wearMinSdk}}`, HTML-escaped `{{include:examples/<file>}}`), leaving any unknown `{{...}}` sequences inside `<code>`/`<pre>` untouched and aborting on unknown placeholders outside code blocks.
    *   Generates deterministic lowercase-hyphen heading slug `id`s for `<h2>`/`<h3>` on docs pages (appending `-2`, `-3` on collisions) and builds the right-rail `On this page` TOC and breadcrumbs.
    *   Copies `site/assets/`, `site/images/`, `docs/web/`, and `docs/CNAME` read-only into `<outDir>/`, and emits `.nojekyll`, `robots.txt` (`Allow: /` + `Sitemap`, no `Disallow: /web/`), and `sitemap.xml` (canonical `kind: "page"` entries only). Aborts with non-zero exit if `outDir` resolves inside `docs/web/`, `docs/CNAME`, or `docs/images/SpatialFin-banner.png`.

8.  **`site/verify.mjs` (Create):**
    *   Zero-dependency Node 22 ESM verifier exporting `verifySite({ rootDir, outDir, browser })` and supporting `--root <dir>`, `--out <dir>` (default `_site`), and `--browser`.
    *   Offline on-disk checks over `<outDir>`:
        *   Protected copies exist: `<outDir>/CNAME === "spatialfin.martinez.fyi"`, `<outDir>/.nojekyll`, `<outDir>/web/index.html`, `<outDir>/web/manifest.webmanifest`.
        *   Internal `href`/`src` and `#anchor` target resolution across all generated HTML pages (stripping `<pre>`, `<code>`, `<script>`, `<style>`, and `<!--...-->` before scanning).
        *   Per-page-class matrix:
            *   `kind: "page"` (10 canonical pages): unique `<title>` (20–65 chars), `<meta name="description">` (70–160 chars), single `<h1>`, zero heading-level skips, exact `<link rel="canonical">` parity with `sitemap.xml`, OG/Twitter tags, valid JSON-LD (`softwareVersion === APP_NAME` checked on `SoftwareApplication` at `/`), Umami script present.
            *   `kind: "redirect"` (3 stubs): `<meta http-equiv="refresh">`, `<link rel="canonical">`, `noindex, follow`, excluded from `sitemap.xml`, zero Umami script.
            *   `kind: "notFound"` (`404.html`): excluded from `sitemap.xml`, root-relative `/assets/` & `/images/` paths, Umami script present.
        *   CSS `:root` definition check for every `var(--*)`; `<img>` `width`/`height`/`alt`; PNG IHDR dimensions (`>= 1200×630`, bytes 16–23) for `og-card.png`; asset byte caps (`<= 350 KB` WebP screenshots, `<= 250 KB` `trailer-poster.webp`, `<= 500 KB` `og-card.png`, `<= 15 MB` total `<outDir>/images/`); orphan-image check collecting `<img>`, `<link rel="icon">`, `og:image`, and `twitter:image` references; PeerTube `manifest.json` (`JSON.parse`) and `script.js` (`node --check`).
    *   Optional `--browser`: loads Puppeteer via `createRequire(resolve(rootDir, 'spatialfin-webxr/package.json'))('puppeteer')` (exiting non-zero with `cd spatialfin-webxr && npm ci` guidance if absent), serves `<outDir>` on an ephemeral `127.0.0.1` port, and verifies `1440×900`, `768×1024`, and `390×844` viewports for zero `console.error`/`pageerror`, `scrollWidth <= innerWidth`, `< 760px` drawer open/close + `aria-expanded` toggle + Escape dismissal + `aria-current="page"`, all `<img>` loaded (`complete && naturalWidth > 0`), and primary text visible with JS disabled.
    *   Diagnostics: `[FAIL] <file>:<line> — <issue> — Fix: <action>` (omitting `:<line>` for file-level/binary errors).

9.  **`site/test/site-scripts.test.mjs` (Create):**
    *   `node --test` unit suite exercising `buildSite` and `verifySite` on isolated temporary fixture directories: placeholder expansion, literal passthrough of `{{...}}` inside `<code>`, HTML escaping of `{{include:...}}`, malformed `Versions.kt` failure, duplicate heading slug deduplication (`-2`), path-refusal guard (`outDir` targeting `docs/web/`), undefined CSS variable detection, heading skip detection, broken `#anchor` detection, redirect & `404.html` sitemap exclusion, byte-cap overflow (`> 350 KB`, `> 500 KB`), orphan image detection, PNG IHDR dimension rejection, and invalid PeerTube JSON/JS detection.

10. **`plugins/src/test/java/dev/jdtech/jellyfin/plugins/engine/DocsPeerTubeExampleTest.kt` (Create):**
    *   JVM unit test deserializing `site/examples/peertube/manifest.json` with `PluginConfig` and executing `source.enable`, `source.getHome`, `source.search`, and `source.resolveVideoUrl` in `quickjs-kt-jvm` against `okhttp-mockwebserver`, asserting valid return shapes and `resolvedUrl != inputUrl`.

*   **Phase 1 Checkpoint:** `node --test site/test/site-scripts.test.mjs` exits `0` and `./gradlew :plugins:testLibreDebugUnitTest --tests "*.DocsPeerTubeExampleTest"` exits `0`.

---

### Phase 2: Committed Website Media Assets (`site/images/`)

#### Target Files to Create, Modify, or Delete

1.  **`site/images/icon.png` (Create):** Copy from `docs/images/icon.png` (referenced in `<link rel="icon">`, header logo, and footer logo).
2.  **Curated WebP Screenshots under `site/images/` (Create via `ffmpeg` from local `fastlane/metadata/android/en-US/images/` and `screenshots/playstore_pixel10/01_home.png`, each `<= 350 KB`, `maxWidth <= 1440px`, EXIF stripped):**
    *   `site/images/xr-home.webp` (from `fastlane/metadata/android/en-US/images/screenshot_xr_20260523_182256.png`) — used on `/` and `/guides/xr-cinema-voice/`.
    *   `site/images/xr-cinema.webp` (from `fastlane/metadata/android/en-US/images/screenshot_xr_20260523_182131.png`) — used on `/` and `/guides/xr-cinema-voice/`.
    *   `site/images/phone-home.webp` (from `screenshots/playstore_pixel10/01_home.png`) — used on `/` and `/guides/getting-started/`.
    *   `site/images/tablet-downloads.webp` (from `fastlane/metadata/android/en-US/images/tenInchScreenshots/5_tablet_downloads.png`) — used on `/guides/playback-offline/`.
    *   `site/images/tablet-music.webp` (from `fastlane/metadata/android/en-US/images/tenInchScreenshots/6_tablet_music.png`) — used on `/guides/casting-music/`.
    *   `site/images/tv-home.webp` (from `fastlane/metadata/android/en-US/images/tvScreenshots/tv_home_1.png`) — used on `/` and `/guides/getting-started/`.
    *   `site/images/wear-remote.webp` (from `fastlane/metadata/android/en-US/images/wearScreenshots/1_remote_control.png`) — used on `/` and `/guides/wear-os/`.
    *   `site/images/wear-private-audio.webp` (from `fastlane/metadata/android/en-US/images/wearScreenshots/6_private_audio.png`) — used on `/guides/wear-os/` and `/guides/casting-music/`.
3.  **`site/images/og-card.png` (Create):** Deterministically generated `1200×630` PNG (`<= 500 KB`) composited via `ffmpeg` (scaling/padding `docs/images/SpatialFin-banner.png` onto `#111318` canvas to exact `1200×630`) or rendered via Puppeteer from a minimal `site/og-card.html` template.
4.  **`site/images/trailer-poster.webp` (Create):** Downloaded from YouTube `https://i.ytimg.com/vi/PMHOc9WXA9s/maxresdefault.jpg` (fallback `hqdefault.jpg`) and converted via `ffmpeg` (`<= 250 KB` WebP).

*   **Phase 2 Checkpoint:** Confirm every file listed above exists in `site/images/`, `og-card.png` is `>= 1200×630` (`<= 500 KB`), every screenshot WebP is `<= 350 KB`, and total `site/images/` size is `<= 15 MB`.

---

### Phase 3: Page Fragments, Site Build & Full Verification

#### Target Files to Create, Modify, or Delete

1.  **All 11 Semantic HTML Fragments under `site/pages/` (Create):**
    *   `site/pages/index.html` — Anchor IDs `#platforms`, `#capabilities`, `#download`, `#faq`. Includes the 5-surface capability matrix covering 10 rows (Jellyfin & Local/SMB/NFS Sources, `libass` ASS/SSA Subtitles, Stereoscopic 3D / VR180 / 360°, Curved Cinema & ARCore Wall Anchor, On-Device Voice AI & Cast Card, Multi-Protocol Casting & Split-A/V, Encrypted Offline Downloads & Media3 Transcoding, Music Assistant & SendSpin, QuickJS / Grayjay Plugins, Rotary Crown & Tiles) across 5 columns (`Android XR`, `Android Phone`, `Android TV`, `Wear OS`, `WebXR`) explicitly marking `Supported`, `Partial` (with footnote), or `Not applicable`.
    *   `site/pages/guides/index.html` — Grouped HTML guide index linking all 6 workflow guides.
    *   `site/pages/guides/getting-started.html`
    *   `site/pages/guides/xr-cinema-voice.html`
    *   `site/pages/guides/playback-offline.html`
    *   `site/pages/guides/casting-music.html`
    *   `site/pages/guides/wear-os.html`
    *   `site/pages/guides/webxr.html`
    *   `site/pages/companion/index.html`
    *   `site/pages/plugins/index.html`
    *   `site/pages/404.html`
2.  **`.github/workflows/pages.yml` (Create):** GitHub Actions Pages workflow (`permissions: pages: write, id-token: write`) triggered on `push` to `main` (paths: `site/**`, `docs/web/**`, `docs/CNAME`, `buildSrc/src/main/kotlin/Versions.kt`, `companion/wear/build.gradle.kts`, `.github/workflows/pages.yml`) and `workflow_dispatch`. Uses Node 22 (no `cache: npm`), runs `node site/build.mjs` + `node site/verify.mjs`, uploads `_site/` via `actions/upload-pages-artifact@v3`, and deploys via `actions/deploy-pages@v4`.
3.  **Legacy `docs/` cleanup (Delete upon GitHub Actions Pages cutover; keep `docs/web/`, `docs/CNAME`, `docs/images/SpatialFin-banner.png`, and `docs/designs/` untouched):**
    *   Delete `docs/index.html`, `docs/smart.html`, `docs/companion.html`, `docs/plugins.html`, `docs/style.css` (confirming zero references in `docs/web/index.html`), `docs/images/Screenshot_20260315_093602_SpatialFin~2.jpg`, `docs/images/Screenshot_20260315_102004_SpatialFin~2.jpg`, `docs/images/beam-icon.png` (unreferenced), and `docs/images/icon.png` (superseded by `site/images/icon.png`).

*   **Phase 3 Checkpoint:** Run `node site/build.mjs && node site/verify.mjs` (exit `0` in `< 5 s`), then `cd spatialfin-webxr && npm ci && cd .. && node site/verify.mjs --browser` (exit `0` across all 3 viewports).

---

### Phase 4: Shared Release Plumbing, CI Wiring & Repo-Doc Reconciliation

#### Target Files to Create, Modify, or Delete

1.  **`buildSrc/src/main/kotlin/SpatialfinSigning.kt` (Create) & `app/unified/build.gradle.kts`, `companion/wear/build.gradle.kts` (Modify):**
    *   Centralize `SPATIALFIN_KEYSTORE` / password / alias resolution using `rootProject.file(...)` (fixing subproject-relative path divergence). When `-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true` is passed and signing config is missing or the keystore file does not exist, throw `GradleException` at configuration time; when unset (default CI), retain the debug-signing fallback.
2.  **`.github/scripts/tag-release.sh` (Modify):**
    *   Accept optional `--check-only` flag: run existing local tag, `origin` tag (with offline/shallow-clone warning), and `APP_CODE > HIGHEST` validation, then exit `0` before `git tag -a`.
3.  **`.github/scripts/build-release-bundles.sh` (Create):**
    *   Shared serialized 3-AAB Gradle runner called by both `.github/workflows/ci.yml` and `.github/scripts/release-bundles.sh`: passes `--no-daemon --no-parallel --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx5120m -Dfile.encoding=UTF-8 -Dcom.android.tools.r8.enableKeepAnnotations=true" -Pkotlin.compiler.execution.strategy=in-process` (plus optional extra Gradle args like `-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true`) sequentially for `:app:unified:bundleLibreRelease`, `:app:unified:bundleTvRelease`, and `:companion:wear:bundleRelease`.
4.  **`.github/scripts/release-bundles.sh` (Create):**
    *   Offline-capable release orchestrator with explicit 4-flag × 6-stage matrix:
        *   Flags: `--site-only` (Stages 2–3 only), `--check-only` (Stages 1–3 only, read-only), `--allow-debug-signing` (omits `-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true` and allows `CN=Android Debug`), `--skip-site` (skips Stage 3). Rejects mutually exclusive `--site-only --skip-site` with exit `2`.
        *   Stage 1 (Preflight): runs `.github/scripts/tag-release.sh --check-only` and verifies `r8 = "..."` in `gradle/libs.versions.toml` matches `com.android.tools:r8:...` in `settings.gradle.kts`.
        *   Stage 2 (Diff surface): resolves `LAST_TAG="$(git tag -l 'v*' --sort=-v:refname | head -n1)"` and prints `git log "${LAST_TAG}..HEAD" --oneline` (falling back to `git log -n 20 --oneline` if no tag exists).
        *   Stage 3 (Website build & verify): runs `node site/build.mjs` and `node site/verify.mjs`.
        *   Stage 4 (3-AAB build): calls `.github/scripts/build-release-bundles.sh` (with `-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true` unless `--allow-debug-signing`).
        *   Stage 5 (Cert parity): runs `LC_ALL=C "${JAVA_HOME:-$(dirname "$(dirname "$(command -v keytool)")")}/bin/keytool" -printcert -jarfile <aab>` on `app/unified/build/outputs/bundle/libreRelease/spatialfin-libre-release.aab`, `app/unified/build/outputs/bundle/tvRelease/spatialfin-tv-release.aab`, and `companion/wear/build/outputs/bundle/release/wear-release.aab`, asserting identical `SHA256:` fingerprints and rejecting `Owner:` matching `CN=Android Debug` unless `--allow-debug-signing`.
        *   Stage 6 (Summary): prints `.aab` paths, sizes, Play version codes (`libre`, `tv`, `wear`), SHA-256 checksums, and manual commit + `.github/scripts/tag-release.sh` instructions.
5.  **`.github/scripts/test-release-bundles.sh` (Create):**
    *   Isolated temp-git-repo smoke test with stubbed `gradlew` and `keytool` verifying flag-conflict errors, `r8` mismatch rejection, `APP_CODE <= highest tag` rejection, `CN=Android Debug` rejection, cert-mismatch detection, and `--site-only` / `--check-only` behavior.
6.  **`.github/workflows/ci.yml` (Modify):**
    *   Add standalone `website` job (`actions/checkout@v5`, `actions/setup-node@v5` with `node-version: '22'` and **no** `cache: npm`) running `node --test site/test/site-scripts.test.mjs`, `node site/build.mjs`, `node site/verify.mjs`, and `bash .github/scripts/test-release-bundles.sh`.
    *   Add `node site/build.mjs && node site/verify.mjs --browser` as a trailing step in the existing `web` job (after `npm ci`).
    *   Add `:plugins:testLibreDebugUnitTest` to the Android unit-test command and call `.github/scripts/build-release-bundles.sh` in the Android release-bundle step.
7.  **Repository Documentation Reconciliation (Modify):**
    *   `GEMINI.md` (must remain under 700 lines; currently 685): update Self-Update Mandate and Play Track Bundles sections to document `site/` + `.github/workflows/pages.yml` + `.github/scripts/release-bundles.sh` + no-competitor-name editorial rule; fix stale lines: line 6 (include `wear` bundle `APP_CODE + 2_000_000`), line 285 (`bumps all three bundles`), line 287 (manual pre-release dependency updates outside `release-bundles.sh`), lines 299 & 544–546 (remove legacy `PanelPoseController` / non-existent `poseApproximatelyEqual` claims), line 422 (curved presets `9.0m`/`6.5m`/`5.2m` instead of `radius = 25 m`), line 447 (replace `Gemma .task model` with `gemma-4-E2B-it.litertlm`), and line 576 (include `:companion:wear:bundleRelease`).
    *   `CLAUDE.md`: update bundle count from two to three (`libre`, `tv`, `wear`) and reference `site/` and `.github/scripts/release-bundles.sh` via `GEMINI.md`.
    *   `README.md`: slim into a concise GitHub overview + install/build summary linking out to [spatialfin.martinez.fyi](https://spatialfin.martinez.fyi/) guides, fixing stale lines: lines 5, 14, and 421 (five surfaces, three Play bundles under one Play listing), lines 107, 159, 294, and 312 (`Download/SpatialFin` matching line 75 and `DownloadStorageManager.kt`), line 260 (browsing scale + player scale/depth + flat-cinema ARCore wall anchoring), AirPlay v1 video-only, and 20-minute subtitle buffer with disk fallback.
    *   `PRIVACY`: update `Last updated:` (line 4), add a note in Section 1 (`SUMMARY`) and Section 2 (`INFORMATION COLLECTED BY THE DEVELOPER`) clarifying that neither the native SpatialFin apps nor `/web/` collect analytics while the static documentation website (`spatialfin.martinez.fyi`) uses cookieless self-hosted Umami, and change `Downloads/SpatialFin` on line 58 to `Download/SpatialFin`.
    *   `fastlane/metadata/android/en-US/full_description.txt` (keep strictly `<= 4,000` characters): update line 9 to describe centered cinema zoom/depth persistence + optional ARCore wall anchoring, align line 25 (`Wear OS Companion` → `SpatialFin for Wear OS`), and update line 40 to clarify that a Jellyfin server is optional (local files, SMB/NFS, and plugins also work).

*   **Phase 4 Checkpoint:** Run all PR-executable verification commands below and confirm exit `0`.

### Verification Plan

*   **Unit & Script Tests (PR CI):**
    *   `node --test site/test/site-scripts.test.mjs` — exits `0` with zero failures.
    *   `bash .github/scripts/test-release-bundles.sh` — exits `0` with zero failures.
    *   `./gradlew :plugins:testLibreDebugUnitTest --tests "*.DocsPeerTubeExampleTest"` — exits `0`, proving the PeerTube example runs inside `:plugins` QuickJS.
*   **Website Build & Offline Verification (PR CI `website` job):**
    *   `node site/build.mjs && node site/verify.mjs` — exits `0` in `< 5 s` locally (`< 10 s` in CI), verifying all 10 canonical pages, 3 redirect stubs, `404.html`, `sitemap.xml`, `robots.txt`, JSON-LD, CSS custom properties, image dimensions/byte caps, and read-only copies of `docs/web/` and `docs/CNAME`.
    *   Confirm `git status --porcelain -- docs/web/ docs/CNAME docs/images/SpatialFin-banner.png` outputs nothing.
*   **Browser Responsive & Accessibility Audit (PR CI `web` job):**
    *   `cd spatialfin-webxr && npm ci && cd .. && node site/verify.mjs --browser` — exits `0` across `1440×900`, `768×1024`, and `390×844` with zero `console.error`/`pageerror`, zero horizontal overflow, working `< 760px` drawer open/close + `aria-expanded` + Escape dismissal + `aria-current="page"`, loaded images, and visible primary text with JS disabled.
*   **Release Script Dry-Run (PR-executable without release keystore or version bump):**
    *   `bash .github/scripts/release-bundles.sh --site-only` — exits `0`.
    *   `bash .github/scripts/release-bundles.sh --check-only --allow-debug-signing` (on a branch with bumped `APP_CODE`) — exits `0` without writing `.aab` files.
*   **Maintainer Release-Time Verification (requires bumped `Versions.kt` + configured `SPATIALFIN_KEYSTORE`):**
    *   `bash .github/scripts/release-bundles.sh` — exits `0`, emits all three `.aab` files under serialized CI R8 flags, and confirms identical non-debug SHA-256 certificate fingerprints via `keytool -printcert -jarfile`.
