const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const net = require('node:net');
const path = require('node:path');
const {spawn} = require('node:child_process');
const puppeteer = require('puppeteer');

const artworkPng = fs.readFileSync(path.join(__dirname, 'public', 'app-icon.png'));
const animeSubtitleAss = fs.readFileSync(
  path.join(__dirname, 'test-fixtures', 'anime-styled.ass'),
  'utf8',
);
const absoluteTimingSrt = fs.readFileSync(
  path.join(__dirname, 'test-fixtures', 'absolute-timing.srt'),
  'utf8',
);
const animeSubtitleFont = fs.readFileSync(
  path.join(__dirname, 'public', 'libass', 'default.woff2'),
);

function getFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      const port = typeof address === 'object' && address ? address.port : 4173;
      server.close(() => resolve(port));
    });
  });
}

async function waitForServer(url) {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // Vite is still starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Vite did not start at ${url}`);
}

async function waitForRecordedRequest(requests, predicate, description) {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    const match = requests.find(predicate);
    if (match) return match;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`Timed out waiting for ${description}`);
}

async function startVite() {
  if (process.env.BASE_URL) return {baseUrl: process.env.BASE_URL, process: null};
  const port = await getFreePort();
  const viteBin = path.join(__dirname, 'node_modules', 'vite', 'bin', 'vite.js');
  const child = spawn(
    process.execPath,
    [viteBin, '--host', '127.0.0.1', '--port', String(port), '--strictPort'],
    {cwd: __dirname, stdio: ['ignore', 'pipe', 'pipe']},
  );
  let output = '';
  child.stdout.on('data', (chunk) => { output += chunk.toString(); });
  child.stderr.on('data', (chunk) => { output += chunk.toString(); });
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    await waitForServer(baseUrl);
  } catch (error) {
    child.kill('SIGTERM');
    throw new Error(`${error.message}\n${output}`);
  }
  return {baseUrl, process: child};
}

async function startFontFixtureServer() {
  const port = await getFreePort();
  const requests = [];
  const server = http.createServer((request, response) => {
    const url = new URL(request.url, `http://127.0.0.1:${port}`);
    requests.push({
      method: request.method,
      path: `${url.pathname}${url.search}`,
      authorization: request.headers.authorization,
    });
    if (/^\/Videos\/episode-1\/mock-media-source\/Attachments\/(0|1)$/.test(url.pathname)) {
      response.writeHead(200, {
        'Access-Control-Allow-Origin': '*',
        'Content-Type': 'font/woff2',
        'Content-Length': animeSubtitleFont.length,
      });
      response.end(animeSubtitleFont);
      return;
    }
    if (url.pathname === '/Videos/episode-1/mock-media-source/Subtitles/3/Stream.ssa') {
      response.writeHead(200, {
        'Access-Control-Allow-Origin': '*',
        'Content-Type': 'text/x-ssa',
        'Content-Length': Buffer.byteLength(animeSubtitleAss),
      });
      response.end(animeSubtitleAss);
      return;
    }
    response.writeHead(404, {'Access-Control-Allow-Origin': '*'});
    response.end();
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    requests,
    close: () => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())),
  };
}

function mockJson(request, body, status = 200) {
  return request.respond({
    status,
    contentType: 'application/json',
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    },
    body: JSON.stringify(body),
  });
}

function mockItem(id, name, type = 'Movie', index = 1, extra = {}) {
  return {
    Id: id,
    Name: name,
    Type: type,
    Overview: `${name} follows an unlikely crew through a vivid spatial adventure.`,
    Genres: ['Adventure', index % 2 ? 'Animation' : 'Drama', 'Fantasy'],
    CommunityRating: 6.8 + index / 10,
    CriticRating: 72 + index,
    OfficialRating: index % 2 ? 'PG' : 'TV-14',
    ProductionYear: 2020 + index,
    RunTimeTicks: 6_000_000_000,
    ImageTags: {Primary: `primary-${id}`},
    BackdropImageTags: [`backdrop-${id}`],
    UserData: {Played: false},
    ...extra,
  };
}

async function activateCanvasZone(page, viewName, zoneId) {
  await page.evaluate(({viewName, zoneId}) => {
    let view;
    window.xb.scene.traverse((object) => {
      if (object.name === viewName) view = object;
    });
    if (!view) throw new Error(`Canvas view not found: ${viewName}`);
    const zone = view.hitZones.find((candidate) => candidate.id === zoneId);
    if (!zone) throw new Error(`Hit zone not found: ${zoneId}`);
    zone.action();
  }, {viewName, zoneId});
}

async function waitForCanvasScreen(page, screen) {
  await page.waitForFunction((expected) => {
    let found = false;
    window.xb?.scene?.traverse((object) => {
      if (object.name === 'Android XR home surface' && object.userData.screen === expected) {
        found = true;
      }
    });
    return found;
  }, {timeout: 20_000}, screen);
}

async function pixelStats(page, screenshot) {
  return page.evaluate(async (base64) => {
    const image = new Image();
    image.src = `data:image/png;base64,${base64}`;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.width;
    canvas.height = image.height;
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0);
    const data = context.getImageData(0, 0, canvas.width, canvas.height).data;
    let visible = 0;
    let bright = 0;
    for (let index = 0; index < data.length; index += 16) {
      const sum = data[index] + data[index + 1] + data[index + 2];
      if (sum > 12) visible++;
      if (sum > 180) bright++;
    }
    return {visible, bright, sampled: data.length / 16};
  }, Buffer.from(screenshot).toString('base64'));
}

async function subtitleCanvasAlphaStats(page) {
  return page.evaluate(() => {
    const canvas = document.querySelector('canvas[width="2048"][height="1152"]');
    if (!(canvas instanceof HTMLCanvasElement)) return null;
    const context = canvas.getContext('2d');
    if (!context) return null;
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    let nontransparent = 0;
    let opaque = 0;
    for (let index = 3; index < pixels.length; index += 4) {
      if (pixels[index] > 0) nontransparent++;
      if (pixels[index] > 192) opaque++;
    }
    return {width: canvas.width, height: canvas.height, nontransparent, opaque};
  });
}

async function run() {
  const vite = await startVite();
  const fontFixture = await startFontFixtureServer();
  const browser = await puppeteer.launch({
    headless: true,
    args: [
      '--no-sandbox',
      '--enable-unsafe-swiftshader',
      '--use-gl=swiftshader',
      '--use-fake-device-for-media-stream',
      '--use-fake-ui-for-media-stream',
    ],
  });
  const browserErrors = [];
  const requests = [];
  let loginAttempts = 0;
  let subtitleMockSession = null;
  let pageInterceptionEnabled = true;

  try {
    const page = await browser.newPage(); page.on("console", msg => console.log("BROWSER: " + msg.text())); page.on("pageerror", err => console.log("BROWSER ERROR: " + err.toString()));
    page.setDefaultTimeout(60_000);
    await page.setViewport({width: 1440, height: 900, deviceScaleFactor: 1});
    page.on('pageerror', (error) => browserErrors.push(`pageerror: ${error.message}`));
    page.on('console', (message) => {
      if (message.type() === 'error') browserErrors.push(`console: ${message.text()}`);
    });

    await page.setRequestInterception(true);
    page.on('request', (request) => {
      if (!pageInterceptionEnabled) return;
      const url = new URL(request.url());
      if (url.hostname !== 'mock-jellyfin.test') {
        void request.continue().catch(() => undefined);
        return;
      }
      const apiPath = url.pathname.startsWith('/jellyfin-proxy/')
        ? url.pathname.slice('/jellyfin-proxy'.length)
        : url.pathname;
      requests.push({
        method: request.method(),
        path: `${url.pathname}${url.search}`,
        authorization: request.headers().authorization,
        playbackHeader: request.headers()['x-mock-playback'],
        body: request.postData(),
      });
      if (request.method() === 'OPTIONS') {
        request.respond({
          status: 204,
          headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Headers': '*',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
          },
        });
        return;
      }
      if (apiPath === '/Users/AuthenticateByName') {
        loginAttempts++;
        if (loginAttempts === 1) return void mockJson(request, {message: 'Unauthorized'}, 401);
        return void mockJson(request, {
          AccessToken: 'mock-access-token',
          User: {Id: 'mock-user', Name: 'Demo User'},
        });
      }
      if (apiPath === '/Users/Me') return void mockJson(request, {message: 'Session expired'}, 404);
      if (apiPath === '/UserViews') {
        return void mockJson(request, {
          Items: [
            {Id: 'movies', Name: 'Movies', CollectionType: 'movies'},
            {Id: 'shows', Name: 'TV Shows', CollectionType: 'tvshows'},
          ],
        });
      }
      if (apiPath === '/Items/Suggestions') {
        return void mockJson(request, {Items: [
          mockItem('featured-1', 'Aurora Run', 'Movie', 1),
          mockItem('featured-series-1', 'Lantern Valley', 'Series', 2),
          mockItem('featured-3', 'Sprite Flight', 'Movie', 3),
          mockItem('featured-4', 'Ocean Signal', 'Movie', 4),
          mockItem('featured-5', 'Aster House', 'Movie', 5),
          mockItem('featured-6', 'Moon Garden', 'Movie', 6),
        ]});
      }
      if (apiPath === '/UserItems/Resume') {
        return void mockJson(request, {Items: Array.from({length: 6}, (_, index) =>
          mockItem(`resume-${index + 1}`, `Continue Story ${index + 1}`, index === 1 ? 'Episode' : 'Movie', index + 1, {
            SeriesName: index === 1 ? 'Lantern Valley' : undefined,
            ParentIndexNumber: index === 1 ? 1 : undefined,
            IndexNumber: index === 1 ? 2 : undefined,
            UserData: {PlaybackPositionTicks: 2_100_000_000 + index * 100_000_000, Played: false},
          }))});
      }
      if (apiPath === '/Shows/NextUp') {
        return void mockJson(request, {Items: Array.from({length: 6}, (_, index) =>
          mockItem(`next-${index + 1}`, `Next Episode ${index + 1}`, 'Episode', index + 1, {
            SeriesName: `Series ${index + 1}`,
            ParentIndexNumber: 1,
            IndexNumber: index + 1,
          }))});
      }
      if (apiPath === '/Items/Latest') {
        const parentId = url.searchParams.get('parentId');
        const series = parentId === 'shows';
        return void mockJson(request, Array.from({length: 8}, (_, index) =>
          mockItem(
            `${parentId}-${index + 1}`,
            `${series ? 'Mock Series' : 'Mock Movie'} ${index + 1}`,
            series ? 'Series' : 'Movie',
            index + 1,
          )));
      }
      if (apiPath === '/Items') {
        const parentId = url.searchParams.get('parentId');
        const series = parentId === 'shows';
        return void mockJson(request, {Items: Array.from({length: 8}, (_, index) =>
          mockItem(`${parentId}-${index + 1}`, `${series ? 'Mock Series' : 'Mock Movie'} ${index + 1}`, series ? 'Series' : 'Movie', index + 1))});
      }
      if (/^\/Shows\/[^/]+\/Episodes$/.test(apiPath)) {
        return void mockJson(request, {Items: Array.from({length: 8}, (_, index) =>
          mockItem(`episode-${index + 1}`, `Mock Episode ${index + 1}`, 'Episode', index + 1, {
            SeriesName: 'Lantern Valley', ParentIndexNumber: 1, IndexNumber: index + 1,
          }))});
      }
      if (/^\/(?:Users\/[^/]+\/)?Items\/[^/]+$/.test(apiPath)) {
        const itemId = apiPath.split('/').pop();
        return void mockJson(request, mockItem(
          itemId,
          itemId === 'movies-1' ? 'Mock Movie 1' : 'Mock Item',
          'Movie',
          1,
          {Genres: ['Animation']},
        ));
      }
      if (/^\/Items\/[^/]+\/PlaybackInfo$/.test(apiPath)) {
        const playbackRequest = JSON.parse(request.postData() || '{}');
        const requestedAudioStreamIndex = Number.isInteger(playbackRequest.AudioStreamIndex)
          ? playbackRequest.AudioStreamIndex
          : 1;
        return void mockJson(request, {
          MediaSources: [{
            Id: 'mock-media-source',
            SupportsTranscoding: true,
            TranscodingUrl: `/Videos/episode-1/master.m3u8?MediaSourceId=mock-media-source&PlaySessionId=mock-play-session&AudioStreamIndex=${requestedAudioStreamIndex}`,
            RequiredHttpHeaders: {'X-Mock-Playback': 'spatialfin'},
            DefaultAudioStreamIndex: requestedAudioStreamIndex,
            MediaStreams: [
              ...(playbackRequest.AudioStreamIndex === undefined || requestedAudioStreamIndex === 0 ? [{
                Type: 'Audio',
                Codec: 'aac',
                Index: 0,
                DisplayTitle: 'Japanese AAC Stereo',
                Language: 'jpn',
                IsDefault: true,
              }] : []),
              ...(playbackRequest.AudioStreamIndex === undefined || requestedAudioStreamIndex === 1 ? [{
                Type: 'Audio',
                Codec: 'aac',
                Index: 1,
                DisplayTitle: 'English AAC Stereo',
                Language: 'eng',
                IsDefault: false,
              }] : []),
              {
                Type: 'Subtitle',
                Codec: 'ass',
                Index: 2,
                DisplayTitle: 'English - Full dialogue (ASS)',
                Language: 'eng',
                IsDefault: true,
                IsForced: false,
                IsTextSubtitleStream: true,
                DeliveryMethod: 'External',
                DeliveryUrl: '/Videos/episode-1/mock-media-source/Subtitles/2/Stream.ass',
              },
              {
                Type: 'Subtitle',
                Codec: 'ssa',
                Index: 3,
                DisplayTitle: 'English - Signs & Songs (SSA)',
                Language: 'eng',
                IsDefault: false,
                IsForced: true,
                IsTextSubtitleStream: true,
                DeliveryMethod: 'External',
                DeliveryUrl: `${fontFixture.baseUrl}/Videos/episode-1/mock-media-source/Subtitles/3/Stream.ssa`,
              },
              {
                Type: 'Subtitle',
                Codec: 'subrip',
                Index: 4,
                DisplayTitle: 'English - Embedded SRT fallback',
                Language: 'eng',
                IsDefault: false,
                IsForced: false,
                IsTextSubtitleStream: true,
                SupportsExternalStream: true,
                // Reproduce the bad response shape: Jellyfin selected Embed
                // and therefore omitted DeliveryUrl despite an External-only
                // client profile.
                DeliveryMethod: 'Embed',
                DeliveryUrl: null,
              },
            ],
            MediaAttachments: [{
              Codec: 'ttf',
              Index: 0,
              FileName: 'LiberationSans-Regular.woff2',
              MimeType: 'font/ttf',
              DeliveryUrl: `${fontFixture.baseUrl}/Videos/episode-1/mock-media-source/Attachments/0?api_key=mock-access-token`,
            }, {
              Codec: 'otf',
              Index: 1,
              FileName: 'AnimeSigns-Alternate.woff2',
              MimeType: 'font/otf',
              DeliveryUrl: `${fontFixture.baseUrl}/Videos/episode-1/mock-media-source/Attachments/1?api_key=mock-access-token`,
            }],
          }],
          PlaySessionId: 'mock-play-session',
        });
      }
      if (/^\/Videos\/episode-1\/mock-media-source\/Subtitles\/(2\/Stream\.ass|3\/Stream\.ssa)$/.test(apiPath)) {
        return void request.respond({
          status: 200,
          contentType: apiPath.endsWith('.ssa') ? 'text/x-ssa' : 'text/x-ass',
          headers: {'Access-Control-Allow-Origin': '*'},
          body: animeSubtitleAss,
        });
      }
      if (/^\/Videos\/(?:episode-1|movies-1)\/mock-media-source\/Subtitles\/4\/Stream\.srt$/.test(apiPath)) {
        return void request.respond({
          status: 200,
          contentType: 'application/x-subrip',
          headers: {'Access-Control-Allow-Origin': '*'},
          body: absoluteTimingSrt,
        });
      }
      if (/^\/Videos\/[^/]+\/master\.m3u8$/.test(apiPath)) {
        return void request.respond({
          status: 200,
          contentType: 'application/vnd.apple.mpegurl',
          headers: {'Access-Control-Allow-Origin': '*'},
          body: '#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=128000,RESOLUTION=16x16,CODECS="avc1.42e01e,mp4a.40.2"\n' +
            '/Videos/episode-1/hls1/main/0.m3u8?PlaySessionId=mock-play-session\n',
        });
      }
      if (/^\/Videos\/[^/]+\/hls1\/main\/0\.m3u8$/.test(apiPath)) {
        return void request.respond({
          status: 200,
          contentType: 'application/vnd.apple.mpegurl',
          headers: {'Access-Control-Allow-Origin': '*'},
          body: '#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-ENDLIST\n',
        });
      }
      if (/^\/Items\/[^/]+\/Images\/(Primary|Backdrop|Logo)(\/0)?$/.test(apiPath)) {
        return void request.respond({
          status: 200,
          contentType: 'image/png',
          headers: {'Access-Control-Allow-Origin': '*'},
          body: artworkPng,
        });
      }
      if (apiPath.startsWith('/Sessions/Playing')) return void mockJson(request, {});
      mockJson(request, {message: 'Not found'}, 404);
    });

    await page.goto(`${vite.baseUrl}/?xrAutomation=1`, {waitUntil: 'networkidle2'});
    await page.waitForSelector('#login-overlay:not([hidden])');

    const urlPolicy = await page.evaluate(() => {
      const test = window.__spatialfinNetworkTest;
      if (!test) throw new Error('SpatialFin network test API is unavailable');
      const httpsPage = {origin: 'https://spatialfin.example', protocol: 'https:'};
      return {
        browserClassified: [
          '192.168.0.1',
          '100.64.0.1',
          '198.18.0.1',
          '127.0.0.1',
          'localhost',
          'jellyfin.local',
          'JELLYFIN.LOCAL.',
          '[::1]',
          '[fc00::1]',
          '[::ffff:192.168.1.5]',
          '8.8.8.8',
          'jellyfin.home.arpa',
          'jellyfin.lan',
          'jellyfin.internal',
          'jellyfin',
          'jellyfin.local.evil.example',
          '[2001:4860:4860::8888]',
        ].map((hostname) => [hostname, test.browserClassifiesAddressSpace(hostname)]),
        privateUrl: test.normalizeServerUrl(
          '192.168.1.5:8096/web/index.html',
          httpsPage,
        ),
        localUrl: test.normalizeServerUrl('jellyfin.local:8096', httpsPage),
        publicUrl: test.normalizeServerUrl('media.example.com', httpsPage),
        explicitHttpUrl: test.normalizeServerUrl('http://media.example.com:8096', httpsPage),
        proxyUrl: test.normalizeServerUrl('/jellyfin-proxy', httpsPage),
        privateTarget: test.localNetworkTargetForRequest(
          'http://192.168.1.5:8096',
          true,
        ),
        loopbackTarget: test.localNetworkTargetForRequest(
          'http://localhost:8096',
          true,
        ),
        localNameTarget: test.localNetworkTargetForRequest(
          'http://jellyfin.local:8096',
          true,
        ),
        namedTarget: test.localNetworkTargetForRequest(
          'http://jellyfin.example:8096',
          true,
        ),
        secureTarget: test.localNetworkTargetForRequest(
          'https://jellyfin.example',
          true,
        ),
        decoratedNamedTarget: test.createJellyfinRequest(
          'http://jellyfin.example:8096',
          {},
          true,
        ).targetAddressSpace,
      };
    });
    assert.deepEqual(Object.fromEntries(urlPolicy.browserClassified), {
      '192.168.0.1': true,
      '100.64.0.1': true,
      '198.18.0.1': true,
      '127.0.0.1': true,
      localhost: true,
      'jellyfin.local': true,
      'JELLYFIN.LOCAL.': true,
      '[::1]': true,
      '[fc00::1]': true,
      '[::ffff:192.168.1.5]': true,
      '8.8.8.8': true,
      'jellyfin.home.arpa': false,
      'jellyfin.lan': false,
      'jellyfin.internal': false,
      jellyfin: false,
      'jellyfin.local.evil.example': false,
      '[2001:4860:4860::8888]': true,
    });
    assert.equal(urlPolicy.privateUrl, 'https://192.168.1.5:8096');
    assert.equal(urlPolicy.localUrl, 'https://jellyfin.local:8096');
    assert.equal(urlPolicy.publicUrl, 'https://media.example.com');
    assert.equal(urlPolicy.explicitHttpUrl, 'http://media.example.com:8096');
    assert.equal(urlPolicy.proxyUrl, 'https://spatialfin.example/jellyfin-proxy');
    assert.equal(urlPolicy.privateTarget, null);
    assert.equal(urlPolicy.loopbackTarget, null);
    assert.equal(urlPolicy.localNameTarget, null);
    assert.equal(urlPolicy.namedTarget, 'local');
    assert.equal(urlPolicy.secureTarget, null);
    assert.equal(urlPolicy.decoratedNamedTarget, 'local');

    await page.evaluate(() => {
      const nativeFetch = window.fetch.bind(window);
      window.__spatialfinObservedRequests = [];
      window.fetch = (input, init) => {
        const request = input instanceof Request ? input : new Request(input, init);
        window.__spatialfinObservedRequests.push({
          url: request.url,
          targetAddressSpace: request.targetAddressSpace ?? null,
        });
        return nativeFetch(input, init);
      };
    });

    await page.setViewport({width: 390, height: 844, deviceScaleFactor: 1});
    const mobileLayout = await page.evaluate(() => ({
      viewportWidth: window.innerWidth,
      documentWidth: document.documentElement.scrollWidth,
      controlsFit: Array.from(document.querySelectorAll('input, button')).every((element) => {
        const rect = element.getBoundingClientRect();
        return rect.left >= 0 && rect.right <= window.innerWidth;
      }),
    }));
    assert.ok(mobileLayout.documentWidth <= mobileLayout.viewportWidth, 'mobile login should not overflow');
    assert.ok(mobileLayout.controlsFit, 'mobile login controls should fit the viewport');
    await page.screenshot({path: '/tmp/spatialfin-webxr-login-mobile.png'});

    await page.setViewport({width: 1440, height: 900, deviceScaleFactor: 1});
    await page.screenshot({path: '/tmp/spatialfin-webxr-login-desktop.png'});
    
    // Switch to Manual Connect tab (added in user's UI redesign)
    const tabBtn = await page.$('#tab-btn-manual');
    if (tabBtn) await tabBtn.click();
    
    await page.type('#server', 'http://mock-jellyfin.test/jellyfin-proxy');
    await page.type('#username', 'demo');
    await page.type('#password', 'password');
    await page.click('#connect-button');
    await page.waitForFunction(() => document.querySelector('#login-error')?.textContent?.includes('rejected'));
    assert.equal(await page.$eval('#login-overlay', (element) => element.hidden), false);
    await page.waitForFunction(() => !document.querySelector('#connect-button')?.disabled);
    await page.$eval('#connect-button', (element) => element.click());
    await page.waitForFunction(() => sessionStorage.getItem('spatialfin_session_v1') !== null);
    assert.equal(await page.evaluate(() => localStorage.getItem('spatialfin_session_v1')), null);

    await page.waitForSelector('#browser-app:not([hidden])');
    try {
      await page.waitForFunction(() => Array.from(document.querySelectorAll('.browser-shelf h2'))
        .some((heading) => heading.textContent?.includes('Continue watching')));
    } catch (error) {
      const browserState = await page.$eval('#browser-app', (element) => element.textContent);
      throw new Error(`${error.message}\nBrowser state: ${browserState}\nRequests: ${JSON.stringify(requests)}\nErrors: ${browserErrors.join('\n')}`);
    }
    assert.equal(
      await page.evaluate(() => Boolean(window.xb?.core?.renderer?.xr?.isPresenting)),
      false,
      'the browser view should not enter an immersive XR session automatically',
    );
    assert.ok(await page.$('.browser-hero .primary-action'), 'browser home should expose a normal media interface');
    const initialLnaRequests = await page.evaluate(() => window.__spatialfinObservedRequests);
    assert.ok(initialLnaRequests.some(({url, targetAddressSpace}) =>
      url.includes('/Users/AuthenticateByName') && targetAddressSpace === 'local'));
    assert.ok(initialLnaRequests.some(({url, targetAddressSpace}) =>
      url.includes('/Items/Suggestions?') && targetAddressSpace === 'local'));
    const browserScreenshotPath = '/tmp/spatialfin-browser-home.png';
    await page.screenshot({path: browserScreenshotPath});
    await page.$eval('[data-browser-route="libraries"]', (element) => element.click());
    try {
      await page.waitForFunction(() => document.querySelector('.browser-page-heading h1')?.textContent === 'Libraries');
    } catch (error) {
      const browserState = await page.$eval('#browser-app', (element) => element.textContent);
      throw new Error(`${error.message}\nBrowser state: ${browserState}\nErrors: ${browserErrors.join('\n')}`);
    }
    await page.$eval('.library-tile', (element) => element.click());
    try {
      await page.waitForSelector('.media-grid .media-card');
    } catch (error) {
      const browserState = await page.$eval('#browser-app', (element) => element.textContent);
      throw new Error(`${error.message}\\nBrowser state: ${browserState}\\nErrors: ${browserErrors.join('\\n')}`);
    }
    await page.$eval('.media-grid .media-card', (element) => element.click());
    await page.waitForFunction(() =>
      document.querySelector('.detail-page h1')?.textContent === 'Mock Movie 1');
    await page.evaluate(() => {
      localStorage.setItem(
        'spatialfin_subtitle_preferences_v1',
        JSON.stringify({'movies-1': null}),
      );
    });
    await page.$eval('.detail-page .primary-action', (element) => element.click());
    // Wait for playbackReady, not just button visibility: the audio/subtitle
    // buttons are shown the instant the player opens, but this.playback (and its
    // subtitle tracks, including the synthesized SRT fallback) is only populated
    // after two PlaybackInfo round-trips. Track dialogs render once from that
    // snapshot, so opening before it lands renders an Off-only list and the SRT
    // assertion below flakes (green locally, red on slower CI).
    await page.waitForFunction(() => {
      const player = document.querySelector('#browser-player');
      const audioBtn = document.querySelector('#browser-player-audio-btn');
      return player && !player.hidden && player.dataset.playbackReady === 'true' &&
        audioBtn && !audioBtn.hidden;
    }, {timeout: 20_000});
    await page.$eval('#browser-player-subtitles-btn', (element) => element.click());
    await page.waitForSelector('#browser-player-dialog-backdrop:not([hidden]) .player-dialog-item');
    await page.evaluate(() => {
      const item = Array.from(
        document.querySelectorAll('#browser-player-dialog-backdrop .player-dialog-item'),
      ).find((element) => element.textContent.includes('Embedded SRT fallback'));
      if (!(item instanceof HTMLButtonElement)) {
        throw new Error('The synthesized SRT subtitle was not offered by the browser player');
      }
      item.click();
    });
    const browserSrtRequest = await waitForRecordedRequest(
      requests,
      ({method, path: requestPath}) =>
        method === 'GET' &&
        requestPath.startsWith(
          '/jellyfin-proxy/Videos/movies-1/mock-media-source/Subtitles/4/Stream.srt',
        ),
      'the browser synthesized SRT sidecar',
    );
    assert.ok(
      browserSrtRequest.authorization?.includes('Token="mock-access-token"'),
      'the synthesized browser SRT request should use the Jellyfin session',
    );
    await page.$eval('#browser-player-subtitles-btn', (element) => element.click());
    await page.waitForSelector('#browser-player-dialog-backdrop:not([hidden]) .player-dialog-item');
    await page.evaluate(() => {
      const off = Array.from(
        document.querySelectorAll('#browser-player-dialog-backdrop .player-dialog-item'),
      ).find((element) => element.textContent.trim().startsWith('Off'));
      if (!(off instanceof HTMLButtonElement)) throw new Error('Subtitle Off option is unavailable');
      off.click();
    });
    await page.$eval('#browser-player-audio-btn', (element) => element.click());
    await page.waitForSelector('#browser-player-dialog-backdrop:not([hidden]) .player-dialog-item');
    const isJapaneseSelected = await page.evaluate(() => {
      const items = Array.from(document.querySelectorAll('#browser-player-dialog-backdrop .player-dialog-item'));
      const japaneseItem = items.find((el) => el.textContent.includes('Japanese'));
      return japaneseItem ? japaneseItem.classList.contains('is-selected') : false;
    });
    assert.ok(isJapaneseSelected, 'Japanese audio should be selected initially');
    assert.ok(requests.some(({method, path: requestPath, body}) => {
      if (method !== 'POST' || requestPath !== '/jellyfin-proxy/Items/movies-1/PlaybackInfo' || !body) return false;
      const requestBody = JSON.parse(body);
      return requestBody.MediaSourceId === 'mock-media-source' && requestBody.AudioStreamIndex === 0;
    }), 'anime playback should pin Japanese/original audio even when Jellyfin defaults to English');
    await page.evaluate(() => {
      const items = Array.from(document.querySelectorAll('#browser-player-dialog-backdrop .player-dialog-item'));
      const englishItem = items.find((el) => el.textContent.includes('English'));
      if (englishItem) englishItem.click();
    });
    await waitForRecordedRequest(
      requests,
      ({method, path: requestPath, body}) => {
        if (method !== 'POST' || requestPath !== '/jellyfin-proxy/Items/movies-1/PlaybackInfo' || !body) return false;
        const requestBody = JSON.parse(body);
        return requestBody.MediaSourceId === 'mock-media-source' && requestBody.AudioStreamIndex === 1;
      },
      'the browser audio renegotiation',
    );
    await page.waitForFunction(() => {
      const preferences = JSON.parse(localStorage.getItem('spatialfin_audio_preferences_v1') || '{}');
      return typeof preferences['movies-1'] === 'string' && preferences['movies-1'].includes('en');
    }, {timeout: 20_000});
    await page.$eval('#browser-player-audio-btn', (element) => element.click());
    await page.waitForSelector('#browser-player-dialog-backdrop:not([hidden]) .player-dialog-item');
    const isEnglishSelected = await page.evaluate(() => {
      const items = Array.from(document.querySelectorAll('#browser-player-dialog-backdrop .player-dialog-item'));
      const englishItem = items.find((el) => el.textContent.includes('English'));
      return englishItem ? englishItem.classList.contains('is-selected') : false;
    });
    assert.ok(isEnglishSelected, 'English audio should be selected after switching');
    await page.$eval('#browser-player-dialog-backdrop', (element) => element.click());
    const browserEnglishNegotiations = requests.filter(({method, path: requestPath, body}) => {
      if (method !== 'POST' || requestPath !== '/jellyfin-proxy/Items/movies-1/PlaybackInfo' || !body) return false;
      return JSON.parse(body).AudioStreamIndex === 1;
    }).length;
    await page.$eval('#browser-player-back', (element) => element.click());
    await page.waitForFunction(() => document.querySelector('#browser-player')?.hidden === true);
    await page.$eval('.detail-page .primary-action', (element) => element.click());
    await page.waitForFunction(() => {
      const player = document.querySelector('#browser-player');
      const audioBtn = document.querySelector('#browser-player-audio-btn');
      return player && !player.hidden && audioBtn && !audioBtn.hidden;
    }, {timeout: 20_000});
    await waitForRecordedRequest(
      requests,
      ({method, path: requestPath, body}, index) => {
        if (method !== 'POST' || requestPath !== '/jellyfin-proxy/Items/movies-1/PlaybackInfo' || !body) return false;
        return JSON.parse(body).AudioStreamIndex === 1 && requests.slice(0, index).filter((r) => {
          if (r.method !== 'POST' || r.path !== '/jellyfin-proxy/Items/movies-1/PlaybackInfo' || !r.body) return false;
          return JSON.parse(r.body).AudioStreamIndex === 1;
        }).length >= browserEnglishNegotiations;
      },
      'the per-series browser audio choice renegotiation on replay',
    );
    await page.$eval('#browser-player-back', (element) => element.click());
    await page.waitForFunction(() => document.querySelector('#browser-player')?.hidden === true);
    await page.waitForFunction(() => !document.querySelector('#enter-xr-button')?.disabled);
    await page.$eval('#enter-xr-button', (element) => element.click());

    await waitForCanvasScreen(page, 'home');
    await page.waitForFunction(() => window.xb?.core?.scriptsManager?.initializingScripts?.size === 0);
    await page.waitForFunction(() => {
      let labels = [];
      window.xb.scene.traverse((object) => {
        if (object.name === 'Android XR home surface') labels = object.userData.uiLabels || [];
      });
      return labels.includes('Aurora Run') && labels.includes('Continue watching') && labels.includes('Next up');
    });
    await new Promise((resolve) => setTimeout(resolve, 800));

    const homeScene = await page.evaluate(() => {
      const names = [];
      const panels = [];
      let homePanel;
      let homeCanvas;
      let invalidViewScales = 0;
      window.xb.scene.updateMatrixWorld(true);
      window.xb.scene.traverse((object) => {
        if (object.name) names.push(object.name);
        if (object.isPanel) panels.push(object.name);
        if (object.name === 'SpatialFin Android XR home panel') homePanel = object;
        if (object.name === 'Android XR home surface') homeCanvas = object;
        if (object.isView) {
          const scale = object.getWorldScale(object.scale.clone());
          if (!Number.isFinite(scale.x) || !Number.isFinite(scale.y) || scale.x <= 0 || scale.y <= 0) invalidViewScales++;
        }
      });
      return {
        names,
        panels,
        panelDp: homePanel?.userData.androidDpSize,
        panelWorldScale: homePanel?.userData.worldScale,
        panelWidthMeters: homePanel?.getWidth(),
        panelAspect: homePanel?.getWidth() / homePanel?.getHeight(),
        labels: homeCanvas?.userData.uiLabels,
        layout: homeCanvas?.userData.layout,
        canvasRaster: {width: homeCanvas?.canvas?.width, height: homeCanvas?.canvas?.height},
        hitZoneIds: homeCanvas?.hitZones.map((zone) => zone.id),
        invalidViewScales,
        scripts: window.xb.core.scriptsManager.scripts.size,
      };
    });
    assert.deepEqual(homeScene.panelDp, {width: 1400, height: 824});
    assert.equal(homeScene.panelWorldScale, 1.7);
    assert.ok(homeScene.panelWidthMeters > 2, 'home panel should be large enough for headset reading');
    assert.ok(Math.abs(homeScene.panelAspect - 1400 / 824) < 1e-4, 'home panel should match Android aspect');
    assert.equal(homeScene.layout, 'android-xr-home');
    assert.deepEqual(homeScene.canvasRaster, {width: 4200, height: 2472});
    assert.ok(homeScene.hitZoneIds.includes('settings') && homeScene.hitZoneIds.includes('close'));
    assert.ok(!homeScene.hitZoneIds.includes('search') && !homeScene.hitZoneIds.includes('rail-media') && !homeScene.hitZoneIds.includes('rail-multitask'));
    assert.ok(!homeScene.hitZoneIds.includes('rail-downloads') && !homeScene.hitZoneIds.includes('rail-voice'));
    assert.ok(homeScene.hitZoneIds.filter((id) => id.startsWith('hero-') && !id.includes('-play-') && !id.includes('-watched-') && !id.includes('-fav-')).length === 3, 'three Android-style heroes should render');
    assert.equal(homeScene.invalidViewScales, 0);
    assert.ok(homeScene.scripts > 10);
    assert.ok(requests.some(({path: requestPath}) => requestPath.startsWith('/jellyfin-proxy/Items/Suggestions?')));
    assert.ok(requests.some(({path: requestPath}) => requestPath.startsWith('/jellyfin-proxy/UserItems/Resume?')));
    assert.ok(requests.some(({path: requestPath}) => requestPath.startsWith('/jellyfin-proxy/Shows/NextUp?')));
    assert.ok(requests.some(({path: requestPath}) => requestPath.includes('/Images/Backdrop/0')));

    const homeScreenshotPath = '/tmp/spatialfin-webxr-home.png';
    const homeScreenshot = await page.screenshot({path: homeScreenshotPath});
    const pixels = await pixelStats(page, homeScreenshot);
    assert.ok(pixels.visible / pixels.sampled > 0.1, 'rendered home should not be blank');
    assert.ok(pixels.bright > 1_000, 'rendered home should contain visible UI');

    await activateCanvasZone(page, 'Android XR home surface', 'hero-featured-series-1');
    await waitForCanvasScreen(page, 'details');
    await page.waitForFunction(() => {
      let labels = [];
      window.xb.scene.traverse((object) => {
        if (object.name === 'Android XR home surface') labels = object.userData.uiLabels || [];
      });
      return labels.includes('Lantern Valley') && labels.includes('Episodes');
    });
    await page.screenshot({path: '/tmp/spatialfin-webxr-details.png'});

    await activateCanvasZone(page, 'Android XR home surface', 'detail-play');
    await waitForCanvasScreen(page, 'library');
    await page.waitForFunction(() => {
      let labels = [];
      window.xb.scene.traverse((object) => {
        if (object.name === 'Android XR home surface') labels = object.userData.uiLabels || [];
      });
      return labels.includes('Mock Episode 1');
    });
    await activateCanvasZone(page, 'Android XR home surface', 'library-episode-1');
    await waitForCanvasScreen(page, 'details');
    await page.evaluate(() => {
      localStorage.setItem(
        'spatialfin_subtitle_preferences_v1',
        JSON.stringify({'Lantern Valley': null}),
      );
    });
    await activateCanvasZone(page, 'Android XR home surface', 'detail-play');

    await page.waitForFunction(() => {
      const names = new Set();
      window.xb.scene.traverse((object) => { if (object.name) names.add(object.name); });
      return names.has('Cinema screen') && names.has('Playback controls') &&
        names.has('Stage controls') && names.has('Track options') && names.has('Session controls');
    }, {timeout: 20_000});
    await page.waitForFunction(() => window.xb.core.scriptsManager.initializingScripts.size === 0);

    const playerScene = await page.evaluate(() => {
      let screen;
      let transport;
      let stage;
      let track;
      let session;
      let affordance;
      window.xb.scene.updateMatrixWorld(true);
      window.xb.scene.traverse((object) => {
        if (object.name === 'Cinema screen') screen = object;
        if (object.name === 'Android XR transport controls') transport = object;
        if (object.name === 'Stage controls orbiter') stage = object;
        if (object.name === 'Track options orbiter') track = object;
        if (object.name === 'Session orbiter') session = object;
        if (object.name === 'Video move affordance') affordance = object;
      });
      affordance.geometry.computeBoundingBox();
      const box = affordance.geometry.boundingBox;
      return {
        position: screen.position.toArray(),
        quaternion: screen.quaternion.toArray(),
        scale: screen.scale.toArray(),
        projection: screen.userData.projection,
        movementPolicy: screen.userData.movementPolicy,
        grabMarginMeters: screen.userData.grabMarginMeters,
        affordance: {width: box.max.x - box.min.x, height: box.max.y - box.min.y},
        transportLabels: transport.userData.uiLabels,
        stageLabels: stage.userData.uiLabels,
        trackLabels: track.userData.uiLabels,
        sessionLabels: session.userData.uiLabels,
      };
    });
    assert.equal(playerScene.projection, 'flat');
    assert.equal(playerScene.movementPolicy, 'fixed-depth-translation');
    assert.equal(playerScene.grabMarginMeters, 0.4);
    assert.ok(Math.abs(playerScene.position[2] + 6) < 1e-4);
    assert.ok(playerScene.scale.every((value) => Math.abs(value - 1.39) < 1e-4));
    assert.ok(Math.abs(playerScene.affordance.width - 8.8) < 1e-4);
    assert.ok(Math.abs(playerScene.affordance.height - 5.3) < 1e-4);
    assert.ok(playerScene.transportLabels.includes('Rewind') && playerScene.transportLabels.includes('Chapters'));
    assert.ok(playerScene.stageLabels.includes('Smaller') && playerScene.stageLabels.includes('Lock'));
    assert.ok(playerScene.trackLabels.includes('Flat'));
    assert.ok(playerScene.sessionLabels.includes('Cast') && (playerScene.sessionLabels.includes('SyncPlay') || playerScene.sessionLabels.includes('Create Group')));

    const playbackInfoRequest = requests.find(({method, path: requestPath}) =>
      method === 'POST' && requestPath === '/jellyfin-proxy/Items/episode-1/PlaybackInfo');
    assert.ok(playbackInfoRequest?.body, 'XR playback should negotiate a Jellyfin device profile');
    const playbackInfoBody = JSON.parse(playbackInfoRequest.body);
    const subtitleProfiles = playbackInfoBody.DeviceProfile.SubtitleProfiles;
    assert.ok(
      subtitleProfiles.every(({Method}) => Method === 'External'),
      'PlaybackInfo must never advertise embedded text subtitles',
    );
    assert.deepEqual(
      subtitleProfiles.map(({Format}) => Format.toLowerCase()).sort(),
      ['ass', 'ssa', 'srt', 'subrip', 'vtt', 'webvtt'].sort(),
      'PlaybackInfo should advertise every supported raw text format as External-only',
    );

    await page.waitForFunction(() => {
      let labels = [];
      window.xb.scene.traverse((object) => {
        if (object.name === 'Track options orbiter') labels = object.userData.uiLabels || [];
      });
      return labels.some((label) => label.includes('Japanese AAC Stereo'));
    }, {timeout: 20_000});

    await activateCanvasZone(page, 'Track options orbiter', 'audio');
    await waitForRecordedRequest(
      requests,
      ({method, path: requestPath, body}) => {
        if (method !== 'POST' || requestPath !== '/jellyfin-proxy/Items/episode-1/PlaybackInfo' || !body) return false;
        const requestBody = JSON.parse(body);
        return requestBody.MediaSourceId === 'mock-media-source' && requestBody.AudioStreamIndex === 1;
      },
      'the XR audio renegotiation',
    );
    await page.waitForFunction(() => {
      const preferences = JSON.parse(localStorage.getItem('spatialfin_audio_preferences_v1') || '{}');
      let labels = [];
      window.xb.scene.traverse((object) => {
        if (object.name === 'Track options orbiter') labels = object.userData.uiLabels || [];
      });
      return typeof preferences['Lantern Valley'] === 'string' &&
        preferences['Lantern Valley'].includes('en') &&
        labels.some((label) => label.includes('English AAC Stereo'));
    }, {timeout: 20_000});
    await waitForRecordedRequest(
      requests,
      ({method, path: requestPath}) =>
        method === 'GET' && requestPath.includes('AudioStreamIndex=1'),
      'the XR audio-pinned HLS stream',
    );

    await page.waitForFunction(() => {
      let player;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Player: Mock Episode 1') player = object;
      });
      const state = player?.userData.subtitleRendererStateForAutomation?.();
      return typeof player?.userData.selectSubtitleForAutomation === 'function' && state?.trackCount === 3;
    }, {timeout: 20_000});
    await page.evaluate(async () => {
      let player;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Player: Mock Episode 1') player = object;
      });
      await player.userData.selectSubtitleForAutomation(-1);
    });
    await page.waitForFunction(() => {
      let player;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Player: Mock Episode 1') player = object;
      });
      return player?.userData.subtitleRendererStateForAutomation?.().selectedIndex === -1;
    }, {timeout: 20_000});
    // HLS and the PWA service worker may keep harmless background requests
    // alive. Network-idle is only a best-effort handoff before switching from
    // Puppeteer interception to CDP interception; subtitle state above is the
    // actual prerequisite.
    await page.waitForNetworkIdle({idleTime: 250, timeout: 10_000}).catch(() => undefined);
    pageInterceptionEnabled = false;
    await page.setRequestInterception(false);
    subtitleMockSession = await page.createCDPSession();
    subtitleMockSession.on('Fetch.requestPaused', async ({requestId, request}) => {
      const url = new URL(request.url);
      const authorization = Object.entries(request.headers)
        .find(([name]) => name.toLowerCase() === 'authorization')?.[1];
      requests.push({
        method: request.method,
        path: `${url.pathname}${url.search}`,
        authorization,
      });
      const corsHeaders = [
        {name: 'Access-Control-Allow-Origin', value: '*'},
        {name: 'Access-Control-Allow-Headers', value: '*'},
        {name: 'Access-Control-Allow-Methods', value: 'GET, OPTIONS'},
      ];
      try {
        if (request.method === 'OPTIONS') {
          await subtitleMockSession.send('Fetch.fulfillRequest', {
            requestId,
            responseCode: 204,
            responseHeaders: corsHeaders,
          });
        } else {
          const isSrt = url.pathname.endsWith('/Subtitles/4/Stream.srt');
          await subtitleMockSession.send('Fetch.fulfillRequest', {
            requestId,
            responseCode: 200,
            responseHeaders: [
              ...corsHeaders,
              {name: 'Content-Type', value: isSrt ? 'application/x-subrip' : 'text/x-ass'},
            ],
            body: Buffer.from(isSrt ? absoluteTimingSrt : animeSubtitleAss).toString('base64'),
          });
        }
      } catch {
        // The request can disappear if the selected track is changed quickly.
      }
    });
    await subtitleMockSession.send('Fetch.enable', {patterns: [{
      urlPattern: '*mock-jellyfin.test*/jellyfin-proxy/Videos/episode-1/mock-media-source/Subtitles/2/Stream.ass*',
      requestStage: 'Request',
    }, {
      urlPattern: '*mock-jellyfin.test*/jellyfin-proxy/Videos/episode-1/mock-media-source/Subtitles/4/Stream.srt*',
      requestStage: 'Request',
    }]});
    await page.evaluate(() => {
      let track;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Track options orbiter') track = object;
      });
      const subtitles = track?.hitZones.find((zone) => zone.id === 'subtitles');
      if (!subtitles) throw new Error('XR subtitle selector is unavailable');
      subtitles.action();
    });
    const assSubtitleRequest = await waitForRecordedRequest(
      requests,
      ({method, path: requestPath}) => method === 'GET' && requestPath.startsWith('/jellyfin-proxy/Videos/episode-1/mock-media-source/Subtitles/2/Stream.ass'),
      'the selected raw ASS subtitle',
    );
    let subtitleFontRequest;
    let secondSubtitleFontRequest;
    try {
      subtitleFontRequest = await waitForRecordedRequest(
        fontFixture.requests,
        ({method, path: requestPath}) => method === 'GET' && requestPath.startsWith('/Videos/episode-1/mock-media-source/Attachments/0?'),
        'the selected subtitle attachment font',
      );
      secondSubtitleFontRequest = await waitForRecordedRequest(
        fontFixture.requests,
        ({method, path: requestPath}) => method === 'GET' && requestPath.startsWith('/Videos/episode-1/mock-media-source/Attachments/1?'),
        'the second selected subtitle attachment font',
      );
    } catch (error) {
      const subtitleState = await page.evaluate(() => {
        let player;
        window.xb?.scene?.traverse((object) => {
          if (object.name === 'Player: Mock Episode 1') player = object;
        });
        return player?.userData.subtitleRendererStateForAutomation?.() ?? null;
      });
      throw new Error(`${error.message}\nSubtitle state: ${JSON.stringify(subtitleState)}\nBrowser errors: ${browserErrors.join('\n')}`);
    }
    assert.ok(assSubtitleRequest.authorization?.includes('Token="mock-access-token"'), 'raw ASS should use the Jellyfin session');
    assert.equal(subtitleFontRequest.authorization, undefined, 'worker font fetch should not expose an Authorization header');
    assert.equal(secondSubtitleFontRequest.authorization, undefined, 'second worker font fetch should not expose an Authorization header');
    assert.ok(subtitleFontRequest.path.includes('api_key=mock-access-token'), 'worker font fetch should authenticate with Jellyfin api_key');
    assert.ok(secondSubtitleFontRequest.path.includes('api_key=mock-access-token'), 'second worker font fetch should authenticate with Jellyfin api_key');
    await page.waitForFunction(() => {
      let player;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Player: Mock Episode 1') player = object;
      });
      const state = player?.userData.subtitleRendererStateForAutomation?.();
      return state?.selectedIndex === 0 && state.ready === true;
    }, {timeout: 20_000});
    await page.evaluate(() => {
      let player;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Player: Mock Episode 1') player = object;
      });
      player.userData.setSubtitleTimeForAutomation(3);
    });
    try {
      await page.waitForFunction(() => {
        let player;
        window.xb.scene.traverse((object) => {
          if (object.name === 'Player: Mock Episode 1') player = object;
        });
        const state = player?.userData.subtitleRendererStateForAutomation?.();
        if (state?.selectedIndex !== 0 || state.ready !== true) return false;
        const canvas = document.querySelector('canvas[width="2048"][height="1152"]');
        if (!(canvas instanceof HTMLCanvasElement)) return false;
        const context = canvas.getContext('2d');
        if (!context) return false;
        const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
        let visible = 0;
        for (let index = 3; index < pixels.length; index += 16) {
          if (pixels[index] > 0 && ++visible >= 100) return true;
        }
        return false;
      }, {timeout: 20_000, polling: 100});
    } catch (error) {
      const subtitleDiagnostics = await page.evaluate(() => {
        let player;
        window.xb?.scene?.traverse((object) => {
          if (object.name === 'Player: Mock Episode 1') player = object;
        });
        return player?.userData.subtitleRendererStateForAutomation?.() ?? null;
      });
      throw new Error(`${error.message}\nSubtitle state: ${JSON.stringify(subtitleDiagnostics)}\nBrowser errors: ${browserErrors.join('\n')}`);
    }
    const firstSubtitlePixels = await subtitleCanvasAlphaStats(page);
    assert.ok(firstSubtitlePixels?.nontransparent > 1_000, 'libass should paint the styled ASS fixture into the XR canvas');
    assert.ok(firstSubtitlePixels?.opaque > 100, 'the rendered ASS fixture should have solid glyph pixels');
    const cjkSubtitlePixels = await page.evaluate(() => {
      const canvas = document.querySelector('canvas[width="2048"][height="1152"]');
      if (!(canvas instanceof HTMLCanvasElement)) return 0;
      const context = canvas.getContext('2d');
      if (!context) return 0;
      // The fixture's Noto Sans JP event is centered at ASS (640, 360), which
      // maps into this otherwise-empty center band of the 2048x1152 overlay.
      const pixels = context.getImageData(640, 480, 768, 200).data;
      let visible = 0;
      for (let index = 3; index < pixels.length; index += 4) {
        if (pixels[index] > 0) visible++;
      }
      return visible;
    });
    assert.ok(cjkSubtitlePixels > 500, 'bundled Noto Sans JP should render the Japanese ASS fixture event');
    const workerFontResources = (await Promise.all(page.workers().map(async (worker) => {
      try {
        return await worker.evaluate(() => performance.getEntriesByType('resource').map((entry) => ({
          name: entry.name,
          initiatorType: entry.initiatorType,
        })));
      } catch {
        return [];
      }
    }))).flat();
    assert.ok(
      workerFontResources.some(({name}) => new URL(name).pathname === '/web/libass/noto-sans-jp.woff2'),
      `libass worker should GET bundled Noto Sans JP; resources: ${JSON.stringify(workerFontResources)}`,
    );

    const firstSubtitleLabels = await page.evaluate(() => {
      let labels = [];
      window.xb.scene.traverse((object) => {
        if (object.name === 'Track options orbiter') labels = object.userData.uiLabels || [];
      });
      return labels;
    });
    await activateCanvasZone(page, 'Track options orbiter', 'subtitles');
    await page.waitForFunction(() => {
      let player;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Player: Mock Episode 1') player = object;
      });
      return player?.userData.subtitleRendererStateForAutomation?.().selectedIndex === 1;
    }, {timeout: 20_000});
    await waitForRecordedRequest(
      fontFixture.requests,
      ({method, path: requestPath}) => method === 'GET' && requestPath.startsWith('/Videos/episode-1/mock-media-source/Subtitles/3/Stream.ssa'),
      'the cycled raw SSA subtitle',
    );
    await page.waitForFunction((previousLabels) => {
      let labels = [];
      window.xb.scene.traverse((object) => {
        if (object.name === 'Track options orbiter') labels = object.userData.uiLabels || [];
      });
      return JSON.stringify(labels) !== JSON.stringify(previousLabels);
    }, {timeout: 20_000}, firstSubtitleLabels);

    await activateCanvasZone(page, 'Track options orbiter', 'subtitles');
    await page.waitForFunction(() => {
      let player;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Player: Mock Episode 1') player = object;
      });
      const state = player?.userData.subtitleRendererStateForAutomation?.();
      if (state?.selectedIndex !== 2 || state.ready !== true) return false;
      const canvas = document.querySelector('canvas[width="2048"][height="1152"]');
      if (!(canvas instanceof HTMLCanvasElement)) return false;
      const context = canvas.getContext('2d');
      if (!context) return false;
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
      let visible = 0;
      for (let index = 3; index < pixels.length; index += 4) {
        if (pixels[index] > 0 && ++visible >= 100) return true;
      }
      return false;
    }, {timeout: 20_000, polling: 100});
    const xrSrtRequest = await waitForRecordedRequest(
      requests,
      ({method, path: requestPath}) =>
        method === 'GET' &&
        requestPath.startsWith(
          '/jellyfin-proxy/Videos/episode-1/mock-media-source/Subtitles/4/Stream.srt',
        ),
      'the XR synthesized SRT sidecar',
    );
    assert.ok(
      xrSrtRequest.authorization?.includes('Token="mock-access-token"'),
      'the synthesized XR SRT request should use the Jellyfin session',
    );

    await page.evaluate(() => {
      let player;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Player: Mock Episode 1') player = object;
      });
      player.userData.setSubtitleTimeForAutomation(0);
    });
    await page.waitForFunction(() => {
      const canvas = document.querySelector('canvas[width="2048"][height="1152"]');
      if (!(canvas instanceof HTMLCanvasElement)) return false;
      const context = canvas.getContext('2d');
      if (!context) return false;
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
      for (let index = 3; index < pixels.length; index += 4) {
        if (pixels[index] !== 0) return false;
      }
      return true;
    }, {timeout: 20_000, polling: 100});
    await page.evaluate(() => {
      let player;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Player: Mock Episode 1') player = object;
      });
      player.userData.setSubtitleTimeForAutomation(3);
    });
    await page.waitForFunction(() => {
      const canvas = document.querySelector('canvas[width="2048"][height="1152"]');
      if (!(canvas instanceof HTMLCanvasElement)) return false;
      const context = canvas.getContext('2d');
      if (!context) return false;
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
      let visible = 0;
      for (let index = 3; index < pixels.length; index += 4) {
        if (pixels[index] > 0 && ++visible >= 100) return true;
      }
      return false;
    }, {timeout: 20_000, polling: 100});

    await activateCanvasZone(page, 'Track options orbiter', 'subtitles');
    await page.waitForFunction(() => {
      let player;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Player: Mock Episode 1') player = object;
      });
      if (player?.userData.subtitleRendererStateForAutomation?.().selectedIndex !== -1) return false;
      const canvas = document.querySelector('canvas[width="2048"][height="1152"]');
      if (!(canvas instanceof HTMLCanvasElement)) return false;
      const context = canvas.getContext('2d');
      if (!context) return false;
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
      for (let index = 3; index < pixels.length; index += 4) {
        if (pixels[index] !== 0) return false;
      }
      return true;
    }, {timeout: 20_000, polling: 100});
    const subtitlesOffPixels = await subtitleCanvasAlphaStats(page);
    assert.equal(subtitlesOffPixels?.nontransparent, 0, 'cycling past the final subtitle should clear the XR overlay');

    const movement = await page.evaluate(() => {
      let screen;
      let controls;
      let player;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Cinema screen') screen = object;
        if (object.name === 'Playback controls') controls = object;
        if (object.name === 'Player: Mock Episode 1') player = object;
      });
      const userHeight = window.xb.user.height;
      const origin = {x: 0, y: userHeight, z: 0};
      const beforeControls = controls.position.toArray();
      player.userData.forcePlayingForAutomation();
      const quaternion = new screen.quaternion.constructor();
      const vector = new screen.position.constructor(0, 1, 0);
      const controller = {
        userData: {id: 31},
        getWorldQuaternion: (target) => target.copy(quaternion),
      };
      screen.onObjectSelectStart({target: controller});
      quaternion.setFromAxisAngle(vector, 0.22);
      screen.update();
      screen.onObjectSelectEnd({target: controller});
      const dx = screen.position.x - origin.x;
      const dy = screen.position.y - origin.y;
      const dz = screen.position.z - origin.z;
      return {
        depth: Math.sqrt(dx * dx + dy * dy + dz * dz),
        position: screen.position.toArray(),
        quaternion: screen.quaternion.toArray(),
        beforeControls,
        afterControls: controls.position.toArray(),
        projectedFromVideo: controls.userData.projectedFromVideo,
      };
    });
    assert.ok(Math.abs(movement.depth - 6) < 1e-4, 'screen movement must preserve radial depth');
    assert.deepEqual(movement.quaternion.map((value) => Math.round(value * 1e6) / 1e6), [0, 0, 0, 1]);
    assert.notDeepEqual(movement.afterControls, movement.beforeControls, 'controls should follow a moved video');
    assert.ok(Math.abs(movement.projectedFromVideo.x - movement.position[0]) < 1e-4);

    await page.screenshot({path: '/tmp/spatialfin-webxr-player.png'});

    const projections = await page.evaluate(() => {
      let screen;
      let track;
      let stage;
      window.xb.scene.traverse((object) => {
        if (object.name === 'Cinema screen') screen = object;
        if (object.name === 'Track options orbiter') track = object;
        if (object.name === 'Stage controls orbiter') stage = object;
      });
      const cycle = () => {
        track.hitZones.find((zone) => zone.id === 'projection').action();
        let geometry;
        screen.children.forEach((child) => { if (!geometry && child.name === 'Left-eye video surface') geometry = child.geometry; });
        geometry.computeBoundingBox();
        return {
          mode: screen.userData.projection,
          movable: screen.userData.movable,
          stageLabels: stage.userData.uiLabels,
          minZ: geometry.boundingBox.min.z,
          maxZ: geometry.boundingBox.max.z,
        };
      };
      return [cycle(), cycle(), cycle()];
    });
    assert.deepEqual(projections.map((entry) => entry.mode), ['180', '360', 'flat']);
    assert.equal(projections[0].movable, false);
    assert.equal(projections[1].movable, false);
    assert.equal(projections[2].movable, true);
    assert.ok(projections[0].minZ < -40 && projections[0].maxZ <= 0.01);
    assert.ok(projections[1].minZ < -40 && projections[1].maxZ > 40);
    assert.ok(projections[0].stageLabels.includes('Recenter'), 'immersive projections should expose Android-style recentering');

    await subtitleMockSession?.send('Fetch.disable');
    await subtitleMockSession?.detach();
    subtitleMockSession = null;
    pageInterceptionEnabled = true;
    await page.setRequestInterception(true);
    await activateCanvasZone(page, 'Android XR transport controls', 'back');
    await waitForCanvasScreen(page, 'home');
    assert.equal(await page.evaluate(() => document.querySelectorAll('video:not(#browser-video)').length), 0);
    assert.equal(await page.$eval('#browser-video', (video) => video.getAttribute('src')), null);
    assert.equal(
      await page.evaluate(() => document.querySelectorAll('canvas[width="2048"][height="1152"]').length),
      0,
      'leaving XR playback should dispose the libass subtitle canvas',
    );
    assert.ok(requests.some(({method, path: requestPath, body}) =>
      method === 'POST' && requestPath === '/jellyfin-proxy/Items/episode-1/PlaybackInfo' && body?.includes('DeviceProfile')));
    assert.ok(requests.some(({path: requestPath}) => requestPath.startsWith('/jellyfin-proxy/Shows/featured-series-1/Episodes?')));
    assert.ok(requests.some(({path: requestPath, authorization, playbackHeader}) =>
      requestPath.startsWith('/jellyfin-proxy/Videos/episode-1/master.m3u8?') &&
      authorization?.includes('Token="mock-access-token"') && playbackHeader === 'spatialfin'));
    const playbackLnaRequests = await page.evaluate(() => window.__spatialfinObservedRequests);
    assert.ok(playbackLnaRequests.some(({url, targetAddressSpace}) =>
      url.includes('/Videos/episode-1/master.m3u8?') && targetAddressSpace === 'local'));

    await page.evaluate(() => {
      window.__spatialfinExitXrCount = 0;
      window.addEventListener('spatialfin:exit-xr', () => { window.__spatialfinExitXrCount++; });
    });
    await activateCanvasZone(page, 'Android XR home surface', 'close');
    assert.equal(await page.evaluate(() => window.__spatialfinExitXrCount), 1, 'Exit XR should request the active XR session to end');
    await page.waitForSelector('#browser-app:not([hidden])');

    // Verify Music Assistant & SendSpin UI
    await page.$eval('#ma-status-button', (element) => element.click());
    assert.equal(await page.evaluate(() => Boolean(document.querySelector('#ma-settings-dialog')?.open)), true, 'MA settings dialog should open');
    await page.type('#ma-server-url', 'http://127.0.0.1:8095');
    await page.$eval('#ma-settings-close', (element) => element.click());
    assert.equal(await page.evaluate(() => Boolean(document.querySelector('#ma-settings-dialog')?.open)), false, 'MA settings dialog should close');

    await page.$eval('button[data-browser-route="music"]', (element) => element.click());
    await page.waitForFunction(() => document.querySelector('#browser-content h2')?.textContent?.includes('Music Assistant'));

    // Verify Hero Actions Bar & Overflow Menu
    await page.$eval('button[data-browser-route="home"]', (element) => element.click());
    await page.waitForSelector('.browser-hero-actions');
    const heroButtons = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.browser-hero-actions button')).map(b => (b.textContent + ' ' + (b.getAttribute('data-tooltip') || '') + ' ' + (b.getAttribute('aria-label') || '')).trim())
    );
    assert.ok(heroButtons.some(t => t.includes('Play')), 'Hero bar should have Play button');
    assert.ok(heroButtons.some(t => t.includes('Watched')), 'Hero bar should have Watched button');
    assert.ok(heroButtons.some(t => t.includes('Favorite')), 'Hero bar should have Favorite button');
    assert.ok(heroButtons.some(t => t.includes('More')), 'Hero bar should have Overflow More button');

    // Test D-Pad Remote Control Navigation
    await page.keyboard.press('ArrowDown');
    await page.waitForFunction(() => Boolean(document.activeElement && document.activeElement.tagName !== 'BODY'), {timeout: 5_000});
    let focusedTag = await page.evaluate(() => document.activeElement?.tagName);
    assert.ok(focusedTag && focusedTag !== 'BODY', 'Remote D-pad should focus an interactive element');

    // Test Remote D-Pad opening Overflow Dropdown and selecting with Enter/Escape
    await page.evaluate(() => document.querySelector('.hero-overflow-toggle-btn')?.focus());
    await page.keyboard.press('Enter');
    assert.equal(await page.evaluate(() => Boolean(document.querySelector('.hero-overflow-dropdown:not([hidden])'))), true, 'Remote D-pad Enter should open overflow dropdown');
    let focusedOverflowItem = await page.evaluate(() => document.activeElement?.classList.contains('overflow-item'));
    assert.equal(focusedOverflowItem, true, 'Remote D-pad should focus first item in overflow dropdown');

    // Press Escape on remote to close overflow
    await page.keyboard.press('Escape');
    assert.equal(await page.evaluate(() => Boolean(document.querySelector('.hero-overflow-dropdown:not([hidden])'))), false, 'Remote D-pad Escape should close overflow dropdown');

    // Click Edit external IDs in overflow
    await page.click('.hero-overflow-toggle-btn');
    await page.click('.hero-edit-ids-btn');
    assert.equal(await page.evaluate(() => Boolean(document.querySelector('#edit-external-ids-dialog')?.open)), true, 'Edit external IDs dialog should open');
    await page.keyboard.press('Escape');
    assert.equal(await page.evaluate(() => Boolean(document.querySelector('#edit-external-ids-dialog')?.open)), false, 'Remote D-pad Escape should close Edit external IDs dialog');

    await subtitleMockSession?.send('Fetch.disable');
    await subtitleMockSession?.detach();
    subtitleMockSession = null;
    pageInterceptionEnabled = true;
    await page.setRequestInterception(true);
    await page.reload({waitUntil: 'networkidle2'});
    await page.waitForSelector('#login-overlay:not([hidden])');
    await page.waitForFunction(() => document.querySelector('#login-error')?.textContent?.includes('no longer points'));
    assert.equal(await page.evaluate(() => Boolean(window.xb)), false);

    const relevantErrors = browserErrors.filter((message) =>
      !message.includes('Failed to load resource') &&
      !message.includes('WebSocket connection to') &&
      !message.includes('libass: Failed to load fonctconfig fonts!') &&
      !message.includes('width or height is 0. You should specify width & height for resize.') &&
      !message.includes('Automatic fallback to software WebGL'));
    assert.deepEqual(relevantErrors, [], `unexpected browser errors: ${relevantErrors.join('\n')}`);

    console.log(JSON.stringify({
      result: 'ok',
      homePanel: homeScene.panelDp,
      homeLabels: homeScene.labels,
      playerProjection: playerScene.projection,
      movementDepth: movement.depth,
      subtitlePixels: firstSubtitlePixels,
      requests: requests.length,
      pixelStats: pixels,
      screenshots: {
        browser: browserScreenshotPath,
        home: homeScreenshotPath,
        details: '/tmp/spatialfin-webxr-details.png',
        player: '/tmp/spatialfin-webxr-player.png',
      },
    }, null, 2));
  } finally {
    await browser.close();
    await fontFixture.close();
    vite.process?.kill('SIGTERM');
  }
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
