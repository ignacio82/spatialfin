import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';

function createMemoryStorage() {
  const store = new Map();
  return {
    get length() {
      return store.size;
    },
    clear() {
      store.clear();
    },
    getItem(key) {
      return store.has(key) ? store.get(key) : null;
    },
    key(index) {
      return Array.from(store.keys())[index] ?? null;
    },
    removeItem(key) {
      store.delete(key);
    },
    setItem(key, value) {
      store.set(key, String(value));
    },
  };
}

// Ensure window is undefined to prove Node >= 22.18 ESM execution requires no DOM.
assert.equal(typeof globalThis.window, 'undefined');

const originalFetch = globalThis.fetch;
globalThis.localStorage = createMemoryStorage();
globalThis.sessionStorage = createMemoryStorage();

const auth = await import('../src/auth.ts');
const api = await import('../src/api.ts');
const fcast = await import('../src/fcast.ts');
const { MusicAssistantManager } = await import('../src/musicassistant/MusicAssistantManager.ts');

describe('WebXR Stage 1 Companion Auth, PNA Fetch, FCast, and Cleanup', () => {
  let fetchCalls;
  let fetchHandler;

  beforeEach(() => {
    globalThis.localStorage.clear();
    globalThis.sessionStorage.clear();
    api.clearPreferences();
    MusicAssistantManager.resetInstanceForTests();
    fetchCalls = [];
    fetchHandler = async () => new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    globalThis.fetch = async (input, init) => {
      const req = input instanceof Request ? input : new Request(input, init);
      fetchCalls.push({
        url: req.url,
        method: req.method,
        headers: Object.fromEntries(req.headers.entries()),
        signal: req.signal,
      });
      return await fetchHandler(req, init);
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    MusicAssistantManager.resetInstanceForTests();
    api.clearPreferences();
  });

  it('normalizes raw authority :port, IPv6 literals, and reverse-proxy base paths before URL parsing', () => {
    assert.equal(auth.normalizeCompanionUrl('192.168.1.90'), 'http://192.168.1.90:1982');
    assert.equal(auth.normalizeCompanionUrl('192.168.1.90:2000'), 'http://192.168.1.90:2000');
    assert.equal(auth.normalizeCompanionUrl('[fd00::1]'), 'http://[fd00::1]:1982');
    assert.equal(auth.normalizeCompanionUrl('[fd00::1]:1982'), 'http://[fd00::1]:1982');
    assert.equal(auth.normalizeCompanionUrl('fd00::1'), 'http://[fd00::1]:1982');
    assert.equal(
      auth.normalizeCompanionUrl('https://home.example.com/companion/'),
      'https://home.example.com/companion',
    );
    assert.equal(
      auth.normalizeCompanionUrl('companion.local/sub/path/'),
      'http://companion.local:1982/sub/path',
    );
    assert.throws(() => auth.normalizeCompanionUrl('ftp://192.168.1.90:1982'), auth.JellyfinAuthError);
    assert.throws(() => auth.normalizeCompanionUrl('http://user:pass@192.168.1.90:1982'), auth.JellyfinAuthError);
  });

  it('deletes address-only loginViaCompanion export', () => {
    assert.equal('loginViaCompanion' in auth, false);
  });

  it('probes server.addresses in order, sends capability and device headers, strips MA password, and persists setupToken + isDedicatedToken=false', async () => {
    fetchHandler = async (req) => {
      if (req.url === 'https://proxy.example.com/companion/api/v1/config') {
        assert.equal(req.headers.get('x-setup-token'), 'sf-setup-abc123');
        assert.equal(req.headers.get('x-spatialfin-capabilities'), 'qc_broker_v1, webxr_client_v1');
        assert.ok(req.headers.get('x-companion-device'));
        return new Response(
          JSON.stringify({
            servers: [
              {
                id: 'srv-1',
                addresses: ['http://10.0.0.5:8096', 'https://jf.example.com/jellyfin'],
                users: [
                  {
                    id: 'user-1',
                    username: 'alice',
                    access_token: 'jf-token-1',
                    musicAssistant: {
                      url: 'http://192.168.1.50:8095',
                      token: 'ma-secret-token',
                      username: 'alice',
                      password: 'should-be-stripped',
                    },
                  },
                ],
              },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      if (req.url === 'http://10.0.0.5:8096/Users/Me') {
        throw new TypeError('network unreachable');
      }
      if (req.url === 'https://jf.example.com/jellyfin/Users/Me') {
        return new Response(
          JSON.stringify({ Id: 'user-1', Name: 'Alice' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('Not found', { status: 404 });
    };

    const s = await auth.loginViaCompanionSetupToken(
      'https://proxy.example.com/companion/',
      'sf-setup-abc123',
    );
    assert.equal(s.serverUrl, 'https://jf.example.com/jellyfin');
    assert.equal(s.companionUrl, 'https://proxy.example.com/companion');
    assert.equal(s.setupToken, 'sf-setup-abc123');
    assert.equal(s.isDedicatedToken, false);
    assert.equal(s.musicAssistant?.url, 'http://192.168.1.50:8095');
    assert.equal(s.musicAssistant?.token, 'ma-secret-token');
    assert.equal('password' in (s.musicAssistant ?? {}), false);
  });

  it('propagates HTTP 401 response.status on JellyfinAuthError when Companion rejects setup token', async () => {
    fetchHandler = async () => new Response(JSON.stringify({ error: 'Unauthorized' }), { status: 401 });
    await assert.rejects(
      () => auth.loginViaCompanionSetupToken('192.168.1.90', 'bad-token'),
      (err) => err instanceof auth.JellyfinAuthError && err.status === 401,
    );
  });

  it('gates POST /Sessions/Logout on isDedicatedToken === true and clears MA + Seerr preferences without constructing MA singleton', async () => {
    // 1. Companion-adopted session (isDedicatedToken: false) should NOT POST /Sessions/Logout
    localStorage.setItem('spatialfin_ma_config', JSON.stringify({ serverUrl: 'http://ma.local:8095', token: 'tok' }));
    auth.saveSession(
      {
        accessToken: 'companion-token',
        serverUrl: 'http://192.168.1.10:8096',
        userId: 'u1',
        userName: 'Alice',
        companionUrl: 'http://192.168.1.90:1982',
        setupToken: 'sf-setup-1234',
        isDedicatedToken: false,
      },
      true,
    );

    fetchHandler = async (req) => {
      if (req.url.endsWith('/api/preferences')) {
        assert.equal(req.headers.get('x-setup-token'), 'sf-setup-1234');
        assert.equal(req.headers.get('x-companion-device'), auth.getDeviceId());
        return new Response(
          JSON.stringify({
            seerr_enabled: true,
            seerr_url: 'https://seerr.example.com/',
            seerr_api_key: 'seerr-secret',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('{}', { status: 200 });
    };

    await api.fetchPreferences();
    assert.equal(api.seerrEnabled, true);
    assert.equal(api.seerrUrl, 'https://seerr.example.com');
    assert.equal(api.seerrApiKey, 'seerr-secret');

    fetchCalls = [];
    await auth.logout(false);

    assert.equal(fetchCalls.some((c) => c.url.includes('/Sessions/Logout')), false);
    assert.equal(localStorage.getItem('spatialfin_ma_config'), null);
    assert.equal(MusicAssistantManager.hasInstance(), false);
    assert.equal(api.seerrEnabled, false);
    assert.equal(api.seerrUrl, '');
    assert.equal(api.seerrApiKey, '');

    // 2. Direct login (isDedicatedToken: true) MUST POST /Sessions/Logout
    fetchHandler = async (req) => {
      if (req.url.endsWith('/Users/AuthenticateByName')) {
        return new Response(
          JSON.stringify({ AccessToken: 'direct-tok', User: { Id: 'u2', Name: 'Bob' } }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('', { status: 204 });
    };
    const directSession = await auth.login('http://192.168.1.10:8096', 'bob', 'secret', '', false);
    assert.equal(directSession.isDedicatedToken, true);
    assert.equal(auth.getAccessToken(), 'direct-tok');

    fetchCalls = [];
    await auth.logout(false);
    assert.equal(fetchCalls.some((c) => c.url.endsWith('/Sessions/Logout') && c.method === 'POST'), true);
  });

  it('preserves sessionStorage vs localStorage tier on clearCompanionSetupToken and resolveCompanionSetupToken', async () => {
    // Store in sessionStorage (remember = false)
    auth.saveSession(
      {
        accessToken: 'tok-session',
        serverUrl: 'http://192.168.1.10:8096',
        userId: 'u1',
        userName: 'Alice',
        companionUrl: 'http://192.168.1.90:1982',
        setupToken: 'sf-setup-old',
        isDedicatedToken: false,
      },
      false,
    );

    assert.equal(localStorage.getItem('spatialfin_session_v1'), null);
    assert.ok(sessionStorage.getItem('spatialfin_session_v1'));

    // 401 from fetchPreferences clears setupToken while preserving sessionStorage tier + accessToken
    fetchHandler = async () => new Response('Unauthorized', { status: 401 });
    await api.fetchPreferences();

    assert.equal(localStorage.getItem('spatialfin_session_v1'), null);
    assert.equal(auth.getCompanionSetupToken(), undefined);
    assert.equal(auth.getAccessToken(), 'tok-session');

    // resolveCompanionSetupToken prompts and writes back to sessionStorage only
    let promptCount = 0;
    const resolved = await auth.resolveCompanionSetupToken(async () => {
      promptCount += 1;
      return '  sf-setup-fresh  ';
    });
    assert.equal(resolved, 'sf-setup-fresh');
    assert.equal(promptCount, 1);
    assert.equal(localStorage.getItem('spatialfin_session_v1'), null);
    assert.equal(auth.getCompanionSetupToken(), 'sf-setup-fresh');

    // Subsequent call returns cached token without re-prompting
    const cached = await auth.resolveCompanionSetupToken(async () => {
      promptCount += 1;
      return 'unused';
    });
    assert.equal(cached, 'sf-setup-fresh');
    assert.equal(promptCount, 1);
  });

  it('routes api.ts metadata mutations and searchSeerr through fetchWithTimeout with AbortSignal', async () => {
    auth.saveSession(
      {
        accessToken: 'tok-meta',
        serverUrl: 'http://192.168.1.10:8096',
        userId: 'u1',
        userName: 'Alice',
        companionUrl: 'http://192.168.1.90:1982',
        setupToken: 'sf-setup-1',
        isDedicatedToken: true,
      },
      true,
    );

    fetchHandler = async (req) => {
      if (req.url.endsWith('/api/preferences')) {
        return new Response(
          JSON.stringify({ seerr_enabled: true, seerr_url: 'http://192.168.1.20:5055', seerr_api_key: 'k' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      if (req.url.includes('/api/v1/search')) {
        return new Response(JSON.stringify({ results: [{ id: 1, mediaType: 'movie', title: 'Test' }] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response(JSON.stringify({ Id: 'item-1', Name: 'Movie', ProviderIds: {} }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    };

    await api.fetchPreferences();
    fetchCalls = [];

    const seerrResults = await api.searchSeerr('test');
    assert.equal(seerrResults.length, 1);
    await api.toggleFavorite('item-1', true);
    await api.toggleItemPlayed('item-1', true);
    await api.refreshItemMetadata('item-1');
    await api.updateItemExternalIds('item-1', { Tmdb: '12345' });

    assert.equal(fetchCalls.length, 6); // searchSeerr + toggleFavorite + toggleItemPlayed + refresh + fetchItem + updateExternalIds
    for (const call of fetchCalls) {
      assert.ok(call.signal instanceof AbortSignal, `Expected AbortSignal on ${call.url}`);
    }
  });

  it('validates FCast URLs, CSS background quoting, IPv6 receiver bracketing, and PLAY log redaction', () => {
    // URL allowlist
    assert.equal(fcast.parseSafeHttpUrl('https://192.168.1.10:8096/Items/1/Images/Primary')?.href, 'https://192.168.1.10:8096/Items/1/Images/Primary');
    assert.equal(fcast.parseSafeHttpUrl('http://192.168.1.10:8096/video.mp4')?.href, 'http://192.168.1.10:8096/video.mp4');
    assert.equal(fcast.parseSafeHttpUrl('javascript:alert(1)'), null);
    assert.equal(fcast.parseSafeHttpUrl('data:text/html,<script>alert(1)</script>'), null);
    assert.equal(fcast.parseSafeHttpUrl('file:///etc/passwd'), null);

    // CSS quoting
    const imgUrl = fcast.parseSafeHttpUrl('https://jf.local/img?a="b)&c=1');
    assert.ok(imgUrl);
    const css = fcast.formatCssBackgroundUrl(imgUrl);
    assert.ok(css.startsWith('url("') && css.endsWith('")'));

    // IPv4 + IPv6 bracketing & injection rejection
    assert.equal(fcast.buildFCastReceiverWsUrl('192.168.1.50', 46899), 'ws://192.168.1.50:46899');
    assert.equal(fcast.buildFCastReceiverWsUrl('fd00::42', 46899), 'ws://[fd00::42]:46899');
    assert.equal(fcast.buildFCastReceiverWsUrl('[fd00::42]', 46899), 'ws://[fd00::42]:46899');
    assert.throws(() => fcast.buildFCastReceiverWsUrl('192.168.1.50/evil?x=1', 46899));
    assert.throws(() => fcast.buildFCastReceiverWsUrl('192.168.1.50', 0));
    assert.throws(() => fcast.buildFCastReceiverWsUrl('192.168.1.50', 70000));

    // PLAY log redaction
    const redacted = fcast.redactFcastPlayPayloadForLog({
      url: 'http://192.168.1.10:8096/Videos/1/stream?static=true&api_key=TOPSECRET123',
      headers: { 'X-Emby-Token': 'SUPERSECRET' },
      metadata: {
        title: 'Test Movie',
        image: 'http://192.168.1.10:8096/Items/1/Images/Primary?apikey=IMGSECRET',
        custom: {
          subtitles: [
            { url: 'http://192.168.1.10:8096/Subtitles/0/Stream.ass?api_key=SUBSECRET' },
          ],
        },
      },
    });
    const serialized = JSON.stringify(redacted);
    assert.equal(serialized.includes('TOPSECRET123'), false);
    assert.equal(serialized.includes('SUPERSECRET'), false);
    assert.equal(serialized.includes('IMGSECRET'), false);
    assert.equal(serialized.includes('SUBSECRET'), false);
    assert.equal(redacted.headers, '[REDACTED]');
  });

  it('Stage 2 qc_broker_v1: mints per-browser dedicated token, enforces selectCandidate, and maps fallback/retry errors', async () => {
    let brokerMode = 'ok';
    fetchHandler = async (req) => {
      if (req.url === 'http://192.168.1.90:1982/api/v1/config') {
        assert.equal(req.headers.get('x-spatialfin-capabilities'), 'qc_broker_v1, webxr_client_v1');
        return new Response(
          JSON.stringify({
            servers: [
              {
                id: '11111111-2222-3333-4444-555566667777',
                name: 'Home JF',
                addresses: ['http://192.168.1.10:8096'],
                users: [
                  { id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', username: 'Alice', has_verified_token: true },
                  { id: 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff', username: 'Bob', has_verified_token: true },
                ],
              },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      if (req.url === 'http://192.168.1.10:8096/QuickConnect/Initiate') {
        return new Response(JSON.stringify({ Code: '123456', Secret: 'sec-1' }), { status: 200 });
      }
      if (req.url === 'http://192.168.1.90:1982/api/v1/auth/authorize-quick-connect') {
        if (brokerMode === 'ok') return new Response(JSON.stringify({ authorized: true }), { status: 200 });
        if (brokerMode === 'qc_disabled') return new Response(JSON.stringify({ error_code: 'qc_disabled' }), { status: 409 });
        if (brokerMode === 'unreachable') return new Response(JSON.stringify({ error_code: 'qc_broker_unreachable' }), { status: 503 });
      }
      if (req.url.startsWith('http://192.168.1.10:8096/QuickConnect/Connect')) {
        return new Response(JSON.stringify({ Authenticated: true }), { status: 200 });
      }
      if (req.url === 'http://192.168.1.10:8096/Users/AuthenticateWithQuickConnect') {
        return new Response(
          JSON.stringify({
            AccessToken: 'browser-dedicated-tok',
            User: { Id: 'aaaaaaaabbbbccccddddeeeeeeeeeeee', Name: 'Alice' },
          }),
          { status: 200 },
        );
      }
      return new Response('Not found', { status: 404 });
    };

    // Multi-user without selectCandidate -> CompanionUserSelectionRequiredError
    await assert.rejects(
      () => auth.loginViaCompanionSetupToken('192.168.1.90', 'sf-setup-tok'),
      (err) => err instanceof auth.CompanionUserSelectionRequiredError && err.candidates.length === 2,
    );

    // Cancelled selection -> CompanionUserSelectionCancelledError
    await assert.rejects(
      () => auth.loginViaCompanionSetupToken('192.168.1.90', 'sf-setup-tok', async () => null),
      (err) => err instanceof auth.CompanionUserSelectionCancelledError,
    );

    // Valid selection -> mints dedicated token (normalizeUuid matches hyphenless vs hyphenated)
    const minted = await auth.loginViaCompanionSetupToken('192.168.1.90', 'sf-setup-tok', async (cands) => cands[0]);
    assert.equal(minted.accessToken, 'browser-dedicated-tok');
    assert.equal(minted.isDedicatedToken, true);

    // 409 qc_disabled -> CompanionBrokerFallbackError with serverUrl & username
    brokerMode = 'qc_disabled';
    await assert.rejects(
      () => auth.loginViaCompanionSetupToken('192.168.1.90', 'sf-setup-tok', async (cands) => cands[0]),
      (err) =>
        err instanceof auth.CompanionBrokerFallbackError &&
        err.code === 'qc_disabled' &&
        err.serverUrl === 'http://192.168.1.10:8096' &&
        err.username === 'Alice',
    );

    // 503 qc_broker_unreachable -> retryable JellyfinAuthError (NOT fallback)
    brokerMode = 'unreachable';
    await assert.rejects(
      () => auth.loginViaCompanionSetupToken('192.168.1.90', 'sf-setup-tok', async (cands) => cands[0]),
      (err) => err instanceof auth.JellyfinAuthError && err.status === 503,
    );
  });

  it('Stage 2 switchCompanionUser preserves storage tier, clears stale MA config, and skips logout on same user', async () => {
    const prevSession = {
      accessToken: 'prev-ded-tok',
      serverUrl: 'http://192.168.1.10:8096',
      userId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      userName: 'Alice',
      companionUrl: 'http://192.168.1.90:1982',
      setupToken: 'sf-setup-tok',
      isDedicatedToken: true,
      musicAssistant: { url: 'http://192.168.1.50:8095', token: 'alice-ma' },
    };
    auth.saveSession(prevSession, false); // sessionStorage
    MusicAssistantManager.getInstance().saveConfig({ serverUrl: 'http://192.168.1.50:8095', token: 'alice-ma' });

    let nextUser = { id: 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff', name: 'Bob', token: 'bob-ded-tok' };
    fetchHandler = async (req) => {
      if (req.url === 'http://192.168.1.90:1982/api/v1/config') {
        return new Response(
          JSON.stringify({
            servers: [
              {
                id: '11111111-2222-3333-4444-555566667777',
                name: 'Home JF',
                addresses: ['http://192.168.1.10:8096'],
                users: [
                  { id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', username: 'Alice', has_verified_token: true },
                  { id: 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff', username: 'Bob', has_verified_token: true },
                ],
              },
            ],
          }),
          { status: 200 },
        );
      }
      if (req.url.endsWith('/QuickConnect/Initiate')) return new Response(JSON.stringify({ Code: '123456', Secret: 's2' }), { status: 200 });
      if (req.url.endsWith('/api/v1/auth/authorize-quick-connect')) return new Response(JSON.stringify({ authorized: true }), { status: 200 });
      if (req.url.includes('/QuickConnect/Connect')) return new Response(JSON.stringify({ Authenticated: true }), { status: 200 });
      if (req.url.endsWith('/Users/AuthenticateWithQuickConnect')) {
        return new Response(
          JSON.stringify({ AccessToken: nextUser.token, User: { Id: nextUser.id, Name: nextUser.name } }),
          { status: 200 },
        );
      }
      if (req.url.endsWith('/Sessions/Logout')) return new Response('', { status: 204 });
      return new Response('Not found', { status: 404 });
    };

    // Switch Alice -> Bob (no MA config): clears MA config, preserves sessionStorage tier, logs out Alice's dedicated token
    const switched = await auth.switchCompanionUser(
      prevSession,
      '192.168.1.90',
      'sf-setup-tok',
      async (cands) => cands[1],
    );
    assert.equal(switched.userName, 'Bob');
    assert.equal(localStorage.getItem('spatialfin_session_v1'), null);
    assert.ok(sessionStorage.getItem('spatialfin_session_v1'));
    assert.equal(localStorage.getItem('spatialfin_ma_config'), null);
    assert.equal(fetchCalls.some((c) => c.url.endsWith('/Sessions/Logout')), true);

    // Re-selecting same user (Bob -> Bob) skips /Sessions/Logout
    fetchCalls = [];
    await auth.switchCompanionUser(
      switched,
      '192.168.1.90',
      'sf-setup-tok',
      async (cands) => cands[1],
    );
    assert.equal(fetchCalls.some((c) => c.url.endsWith('/Sessions/Logout')), false);
  });
});
