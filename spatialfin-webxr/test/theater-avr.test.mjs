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

const originalFetch = globalThis.fetch;
globalThis.localStorage = createMemoryStorage();

const theater = await import('../src/theater/TheaterAvrClient.ts');

describe('WebXR Theater Audio (spatialfin-avr) client', () => {
  let fetchCalls;
  let fetchHandler;

  beforeEach(() => {
    globalThis.localStorage.clear();
    fetchCalls = [];
    fetchHandler = async () =>
      new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    globalThis.fetch = async (input, init) => {
      const req = input instanceof Request ? input : new Request(input, init);
      const bodyText = req.method === 'GET' || req.method === 'HEAD' ? null : await req.clone().text();
      fetchCalls.push({
        url: req.url,
        method: req.method,
        headers: Object.fromEntries(req.headers.entries()),
        bodyText,
      });
      return await fetchHandler(req, init);
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('normalizes AVR URLs and enforces HTTPS mixed-content guard with setup guidance', () => {
    assert.equal(theater.normalizeAvrBaseUrl('192.168.1.90', 'http:'), 'http://192.168.1.90:1984');
    assert.equal(theater.normalizeAvrBaseUrl('192.168.1.90:1986', 'http:'), 'http://192.168.1.90:1986');
    assert.equal(theater.normalizeAvrBaseUrl('[fd00::1]:1986', 'http:'), 'http://[fd00::1]:1986');
    assert.equal(theater.normalizeAvrBaseUrl('avr.lan:1987', 'https:'), 'https://avr.lan:1987');
    assert.equal(
      theater.normalizeAvrBaseUrl('https://avr.lan:1987/', 'https:'),
      'https://avr.lan:1987',
    );

    assert.throws(
      () => theater.normalizeAvrBaseUrl('http://192.168.1.90:1986', 'https:'),
      (err) =>
        err instanceof theater.TheaterAvrError &&
        err.code === 'mixed_content_blocked' &&
        err.message.includes('SPATIALFIN_AVR_CORS_ORIGINS'),
    );
    assert.throws(
      () => theater.normalizeAvrBaseUrl('ftp://192.168.1.90:1986', 'http:'),
      theater.TheaterAvrError,
    );
    assert.throws(
      () => theater.normalizeAvrBaseUrl('http://user:pass@192.168.1.90:1986', 'http:'),
      theater.TheaterAvrError,
    );
  });

  it('formats browser WS ticket subprotocols, credential-free Jellyfin URLs, and Spec 0 envelopes', () => {
    assert.deepEqual(
      theater.buildWsTicketSubprotocols('savr_wst_deadbeef'),
      ['spatialfin-avr.v1', 'ticket.savr_wst_deadbeef'],
    );
    assert.throws(() => theater.buildWsTicketSubprotocols('bad ticket'), theater.TheaterAvrError);

    const streamUrl = theater.buildDirectJellyfinStreamUrl(
      'https://jf.lan:8920/',
      'item-123',
      'src-456',
      2,
    );
    assert.equal(
      streamUrl,
      'https://jf.lan:8920/Videos/item-123/stream?static=true&MediaSourceId=src-456&AudioStreamIndex=2',
    );
    assert.equal(streamUrl.includes('api_key'), false);
    assert.equal(streamUrl.includes('X-Emby-Token'), false);

    const env = theater.buildWsEnvelope(
      'playback.video_timing',
      { barrier_seq: 1, media_pts_us: 5000000, session_id: 'must-be-stripped' },
      { sessionId: 'sess-top', tsUs: 123456 },
    );
    assert.equal(env.v, 1);
    assert.equal(env.type, 'playback.video_timing');
    assert.equal(env.session_id, 'sess-top');
    assert.equal(env.ts_us, 123456);
    assert.equal('session_id' in env.payload, false);
  });

  it('pairs via PIN, persists rooms to localStorage, updates controls with X-Avr-Room-Id, and clears on unpair', async () => {
    fetchHandler = async (req) => {
      if (req.url.endsWith('/api/v1/pair/claim')) {
        return new Response(
          JSON.stringify({ avr_token: 'savr_tok_webxr', server_id: 'avr-11223344' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      if (req.url.endsWith('/api/v1/rooms')) {
        assert.equal(req.headers.get('authorization'), 'Bearer savr_tok_webxr');
        return new Response(
          JSON.stringify({
            rooms: [
              {
                room_id: 'room-living',
                room_name: 'Living Room',
                layout: '7.1.4',
                is_default: true,
                speaker_count: 12,
                active: false,
              },
              {
                room_id: 'room-bedroom',
                room_name: 'Bedroom',
                layout: '2.0',
                is_default: false,
                speaker_count: 2,
                active: false,
              },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      if (req.url.endsWith('/api/v1/room/controls')) {
        assert.equal(req.headers.get('x-avr-room-id'), 'room-bedroom');
        return new Response(
          JSON.stringify({
            master_volume: 0.65,
            muted: false,
            eq_bypass: true,
            upmix_mode: 'neural_height',
            renderer_mode: 'truehd_vbap',
            active_oamd_objects: 11,
            fallback_active: false,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('Not found', { status: 404 });
    };

    const client = new theater.TheaterAvrClient();
    const paired = await client.pairWithPin('https://avr.lan:1987', '123456', 'SpatialFin WebXR', 'https:');
    assert.equal(paired.serverId, 'avr-11223344');
    assert.equal(paired.selectedRoomId, 'room-living');
    assert.equal(paired.rooms.length, 2);

    // Reload from localStorage in a new client instance
    const reloaded = new theater.TheaterAvrClient();
    assert.equal(reloaded.getState().baseUrl, 'https://avr.lan:1987');
    assert.equal(reloaded.getState().selectedRoomId, 'room-living');

    reloaded.selectRoom('room-bedroom');
    await reloaded.updateRoomControls({ master_volume: 0.65, eq_bypass: true, upmix_mode: 'neural_height' });
    assert.equal(reloaded.getState().masterVolume, 0.65);
    assert.equal(reloaded.getState().eqBypass, true);
    assert.equal(reloaded.getState().upmixMode, 'neural_height');
    assert.equal(reloaded.getState().activeOamdObjects, 11);

    reloaded.disconnectAndForget();
    assert.equal(globalThis.localStorage.getItem(theater.THEATER_STORAGE_KEY), null);
    assert.equal(reloaded.getState().enabled, false);
  });
});
