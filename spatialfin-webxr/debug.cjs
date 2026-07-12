const assert = require('node:assert/strict');
const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
const {spawn} = require('node:child_process');
const puppeteer = require('puppeteer');

const artworkPng = fs.readFileSync(path.join(__dirname, 'public', 'app-icon.png'));

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

async function run() {
  const vite = await startVite();
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

  try {
    const page = await browser.newPage();
    await page.setViewport({width: 1440, height: 900, deviceScaleFactor: 1});
    page.on('pageerror', (error) => browserErrors.push(`pageerror: ${error.message}`));
    page.on('console', (message) => {
      if (message.type() === 'error') browserErrors.push(`console: ${message.text()}`);
    });

    await page.setRequestInterception(true);
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (url.hostname !== 'mock-jellyfin.test') {
        request.continue();
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
      if (/^\/Items\/[^/]+\/PlaybackInfo$/.test(apiPath)) {
        return void mockJson(request, {
          MediaSources: [{
            Id: 'mock-media-source',
            SupportsTranscoding: true,
            TranscodingUrl: '/Videos/episode-1/master.m3u8?MediaSourceId=mock-media-source&PlaySessionId=mock-play-session',
            RequiredHttpHeaders: {'X-Mock-Playback': 'spatialfin'},
          }],
          PlaySessionId: 'mock-play-session',
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
    await page.type('#server', 'http://mock-jellyfin.test/jellyfin-proxy');
    await page.type('#username', 'demo');
    await page.type('#password', 'password');
    await page.click('#connect-button');
    await page.waitForFunction(() => document.querySelector('#login-error')?.textContent?.includes('rejected'));
    assert.equal(await page.$eval('#login-overlay', (element) => element.hidden), false);
    await page.click('#connect-button');
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
    await page.click('[data-browser-route="libraries"]');
    await page.waitForFunction(() => document.querySelector('.browser-page-heading h1')?.textContent === 'Libraries');
    await page.click('.library-tile');
    await page.waitForSelector('.media-grid .media-card');
    await page.waitForFunction(() => !document.querySelector('#enter-xr-button')?.disabled);
    await page.click('#enter-xr-button');

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
    assert.ok(homeScene.hitZoneIds.filter((id) => id.startsWith('hero-')).length === 3, 'three Android-style heroes should render');
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

    await page.evaluate(() => {
      window.__spatialfinExitXrCount = 0;
      window.addEventListener('spatialfin:exit-xr', () => { window.__spatialfinExitXrCount++; });
    });
    await activateCanvasZone(page, 'Android XR home surface', 'close');
    assert.equal(await page.evaluate(() => window.__spatialfinExitXrCount), 1, 'Exit XR should request the active XR session to end');
    await page.waitForSelector('#browser-app:not([hidden])');
    await page.waitForFunction(() => !document.querySelector('#enter-xr-button')?.disabled);
    await page.click('#enter-xr-button');
    await page.waitForSelector('#browser-app[hidden]');

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
    assert.ok(playerScene.trackLabels.includes('Subtitles') && playerScene.trackLabels.includes('Flat'));
    assert.ok(playerScene.sessionLabels.includes('Cast') && playerScene.sessionLabels.includes('SyncPlay'));

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

    await activateCanvasZone(page, 'Android XR transport controls', 'back');
    await waitForCanvasScreen(page, 'home');
    assert.equal(await page.evaluate(() => document.querySelectorAll('video:not(#browser-video)').length), 0);
    assert.equal(await page.$eval('#browser-video', (video) => video.getAttribute('src')), null);
    assert.equal(
      await page.evaluate(() => document.querySelectorAll('canvas[width="2048"][height="1024"]').length),
      0,
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

    await page.reload({waitUntil: 'networkidle2'});
    await page.waitForSelector('#login-overlay:not([hidden])');
    await page.waitForFunction(() => document.querySelector('#login-error')?.textContent?.includes('no longer points'));
    assert.equal(await page.evaluate(() => Boolean(window.xb)), false);

    const relevantErrors = browserErrors.filter((message) =>
      !message.includes('Failed to load resource') &&
      !message.includes('Automatic fallback to software WebGL'));
    assert.deepEqual(relevantErrors, [], `unexpected browser errors: ${relevantErrors.join('\n')}`);

    console.log(JSON.stringify({
      result: 'ok',
      homePanel: homeScene.panelDp,
      homeLabels: homeScene.labels,
      playerProjection: playerScene.projection,
      movementDepth: movement.depth,
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
    vite.process?.kill('SIGTERM');
  }
}

run().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
