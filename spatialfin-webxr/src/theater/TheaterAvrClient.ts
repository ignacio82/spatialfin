/**
 * Spec 0 Theater Audio (`spatialfin-avr`) browser & WebXR client.
 *
 * Pure TypeScript module (no DOM globals required at import time so Node unit tests can import it
 * directly). Handles URL normalization, HTTPS mixed-content protection, PIN / Bearer pairing,
 * `POST /api/v1/ws/ticket` + `Sec-WebSocket-Protocol: spatialfin-avr.v1, ticket.<id>` browser
 * WebSocket auth, credential-free direct Jellyfin stream URLs, 2 Hz `requestVideoFrameCallback`
 * `playback.video_timing` beacons, and unthrottled 2 s `session.heartbeat` ticks.
 */

export const THEATER_STORAGE_KEY = 'spatialfin_theater_avr_v1';
export const WS_SUBPROTOCOL_V1 = 'spatialfin-avr.v1';
export const DEFAULT_AVR_PORT = 1984;

export const UPMIX_CYCLE = [
  'native',
  'all_channel_stereo',
  'dts_height_upmix',
  'neural_height',
] as const;

export type UpmixMode = (typeof UPMIX_CYCLE)[number];

export interface TheaterRoomSummary {
  room_id: string;
  room_name: string;
  layout: string;
  is_default: boolean;
  speaker_count: number;
  active: boolean;
}

export interface TheaterAvrPersistedConfig {
  baseUrl: string;
  token: string;
  serverId: string;
  clientId: string;
  selectedRoomId: string | null;
  enabled: boolean;
  rooms: TheaterRoomSummary[];
}

export interface TheaterAvrState {
  baseUrl: string;
  token: string;
  serverId: string;
  clientId: string;
  rooms: TheaterRoomSummary[];
  selectedRoomId: string | null;
  enabled: boolean;
  connected: boolean;
  activeSessionId: string | null;
  masterVolume: number;
  muted: boolean;
  eqBypass: boolean;
  upmixMode: string;
  rendererMode: string | null;
  activeOamdObjects: number | null;
  fallbackActive: boolean;
  activeDecodeChain: string | null;
  lastError: string | null;
}

export interface TheaterPlaybackAttachParams {
  itemId: string;
  mediaSourceId: string;
  audioStreamIndex: number;
  sourceCodec?: string;
  jellyfinServerUrl: string;
  jellyfinToken: string;
  formFactor: 'webxr' | 'browser';
}

export interface WsEnvelope {
  v: 1;
  type: string;
  id?: string;
  session_id?: string;
  ts_us: number;
  payload: Record<string, unknown>;
}

export class TheaterAvrError extends Error {
  readonly code: string;
  readonly status?: number;

  constructor(code: string, message: string, status?: number) {
    super(message);
    this.name = 'TheaterAvrError';
    this.code = code;
    this.status = status;
  }
}

export function nextUpmixMode(current: string): UpmixMode {
  const idx = UPMIX_CYCLE.indexOf(current as UpmixMode);
  return UPMIX_CYCLE[(idx + 1) % UPMIX_CYCLE.length];
}

export function upmixModeLabel(mode: string): string {
  switch (mode) {
    case 'all_channel_stereo':
      return 'Upmix: All-Ch';
    case 'dts_height_upmix':
      return 'Upmix: Height';
    case 'neural_height':
      return 'Upmix: Neural';
    default:
      return 'Upmix: Native';
  }
}

/**
 * Normalizes a user-entered `spatialfin-avr` host/URL (`192.168.1.90:1986`, `https://avr.lan:1987`,
 * `[fd00::1]:1986`) and enforces the browser HTTPS mixed-content rule when running from an
 * `https:` origin.
 */
export function normalizeAvrBaseUrl(rawInput: string, pageProtocol?: string): string {
  let trimmed = rawInput.trim();
  if (!trimmed) {
    throw new TheaterAvrError('invalid_request', 'Enter a SpatialFin AVR host or URL.');
  }

  const effectivePageProto =
    pageProtocol ?? (typeof globalThis.location !== 'undefined' ? globalThis.location.protocol : 'http:');

  if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(trimmed)) {
    const defaultScheme = effectivePageProto === 'https:' ? 'https' : 'http';
    // Bracket bare IPv6 literals lacking port brackets
    const colonCount = (trimmed.match(/:/g) ?? []).length;
    if (colonCount >= 2 && !trimmed.startsWith('[')) {
      trimmed = `[${trimmed}]:${DEFAULT_AVR_PORT}`;
    } else if (!trimmed.includes(':') && !trimmed.startsWith('[')) {
      trimmed = `${trimmed}:${DEFAULT_AVR_PORT}`;
    } else if (trimmed.startsWith('[') && !trimmed.includes(']:')) {
      trimmed = `${trimmed}:${DEFAULT_AVR_PORT}`;
    }
    trimmed = `${defaultScheme}://${trimmed}`;
  }

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    throw new TheaterAvrError('invalid_request', `Invalid SpatialFin AVR address: ${rawInput}`);
  }

  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new TheaterAvrError('invalid_request', `Unsupported URL scheme: ${parsed.protocol}`);
  }
  if (parsed.username || parsed.password) {
    throw new TheaterAvrError('invalid_request', 'AVR URL must not contain embedded credentials.');
  }
  if (!parsed.hostname) {
    throw new TheaterAvrError('invalid_request', 'AVR URL is missing a hostname.');
  }

  if (effectivePageProto === 'https:' && parsed.protocol === 'http:') {
    throw new TheaterAvrError(
      'mixed_content_blocked',
      'HTTPS WebXR requires browser-trusted HTTPS/WSS on spatialfin-avr. Enable SPATIALFIN_AVR_USE_TLS=true with a CA-trusted PEM certificate (or Tailscale Serve) and set SPATIALFIN_AVR_CORS_ORIGINS=https://spatialfin.martinez.fyi.',
    );
  }

  const cleanPath = parsed.pathname.replace(/\/+$/, '');
  return `${parsed.origin}${cleanPath === '/' ? '' : cleanPath}`;
}

/** Formats the `Sec-WebSocket-Protocol` subprotocol list for browser WS ticket authentication. */
export function buildWsTicketSubprotocols(ticket: string): [string, string] {
  const clean = ticket.trim();
  if (!clean || /\s|,/.test(clean)) {
    throw new TheaterAvrError('auth_invalid', 'Invalid WebSocket ticket.');
  }
  return [WS_SUBPROTOCOL_V1, `ticket.${clean}`];
}

/**
 * Builds a credential-free direct Jellyfin stream URL for `spatialfin-avr` `session.start`.
 * Never embeds `?api_key=` or `X-Emby-Token` in the query string (the token travels in the
 * authenticated WS payload's `jellyfin_token` field per Spec 0).
 */
export function buildDirectJellyfinStreamUrl(
  jellyfinServerUrl: string,
  itemId: string,
  mediaSourceId: string,
  audioStreamIndex: number,
): string {
  const base = jellyfinServerUrl.trim().replace(/\/+$/, '');
  const parsed = new URL(`${base}/Videos/${encodeURIComponent(itemId)}/stream`);
  parsed.searchParams.set('static', 'true');
  parsed.searchParams.set('MediaSourceId', mediaSourceId);
  parsed.searchParams.set('AudioStreamIndex', String(Math.max(0, Math.floor(audioStreamIndex))));
  parsed.searchParams.delete('api_key');
  parsed.searchParams.delete('ApiKey');
  parsed.searchParams.delete('X-Emby-Token');
  return parsed.toString();
}

/** Constructs a canonical Spec 0 `v=1` WS envelope (`session_id` strictly on top-level envelope). */
export function buildWsEnvelope(
  msgType: string,
  payload: Record<string, unknown>,
  options: { id?: string; sessionId?: string; tsUs?: number } = {},
): WsEnvelope {
  const cleanPayload: Record<string, unknown> = { ...payload };
  delete cleanPayload.session_id;
  const env: WsEnvelope = {
    v: 1,
    type: msgType,
    ts_us: options.tsUs ?? Math.round((typeof performance !== 'undefined' ? performance.now() : Date.now()) * 1_000),
    payload: cleanPayload,
  };
  if (options.id) env.id = options.id;
  if (options.sessionId) env.session_id = options.sessionId;
  return env;
}

function nowUs(): number {
  return Math.round((typeof performance !== 'undefined' ? performance.now() : Date.now()) * 1_000);
}

function generateId(prefix: string): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `${prefix}-${crypto.randomUUID()}`;
  }
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function createWorkerInterval(intervalMs: number, onTick: () => void): () => void {
  if (typeof Worker !== 'undefined' && typeof Blob !== 'undefined' && typeof URL !== 'undefined') {
    try {
      const blob = new Blob(
        [`setInterval(() => postMessage(1), ${Math.max(100, Math.floor(intervalMs))});`],
        { type: 'application/javascript' },
      );
      const workerUrl = URL.createObjectURL(blob);
      const worker = new Worker(workerUrl);
      worker.onmessage = () => onTick();
      return () => {
        worker.terminate();
        URL.revokeObjectURL(workerUrl);
      };
    } catch {
      // Fall through to standard timer when CSP/environment disallows blob workers.
    }
  }
  const handle = globalThis.setInterval(onTick, intervalMs);
  return () => globalThis.clearInterval(handle);
}

export class TheaterAvrClient {
  private state: TheaterAvrState;
  private listeners = new Set<(state: TheaterAvrState) => void>();

  private ws: WebSocket | null = null;
  private rpcSeq = 0;
  private pendingRpcs = new Map<
    string,
    { resolve: (payload: Record<string, unknown>) => void; reject: (err: Error) => void; timer: ReturnType<typeof setTimeout> }
  >();
  private clockOffsetUs = 0;
  private clockUncertaintyUs = 2_000;
  private barrierSeq = 1;
  private fsmState: 'idle' | 'starting' | 'awaiting_go' | 'playing' | 'paused' = 'idle';

  private attachedVideo: HTMLVideoElement | null = null;
  private previousVideoMuted = false;
  private stopHeartbeat: (() => void) | null = null;
  private stopTimingLoop: (() => void) | null = null;
  private detachVideoListeners: (() => void) | null = null;

  constructor() {
    this.state = this.loadPersistedState();
  }

  getState(): TheaterAvrState {
    return { ...this.state, rooms: [...this.state.rooms] };
  }

  subscribe(listener: (state: TheaterAvrState) => void): () => void {
    this.listeners.add(listener);
    listener(this.getState());
    return () => {
      this.listeners.delete(listener);
    };
  }

  private emitState(patch: Partial<TheaterAvrState>, persist = false) {
    this.state = { ...this.state, ...patch };
    if (persist) this.savePersistedState();
    const snapshot = this.getState();
    this.listeners.forEach((fn) => {
      try {
        fn(snapshot);
      } catch {
        // Ignore subscriber errors.
      }
    });
  }

  private loadPersistedState(): TheaterAvrState {
    const defaults: TheaterAvrState = {
      baseUrl: '',
      token: '',
      serverId: '',
      clientId: generateId('webxr'),
      rooms: [],
      selectedRoomId: null,
      enabled: false,
      connected: false,
      activeSessionId: null,
      masterVolume: 0.75,
      muted: false,
      eqBypass: false,
      upmixMode: 'native',
      rendererMode: null,
      activeOamdObjects: null,
      fallbackActive: false,
      activeDecodeChain: null,
      lastError: null,
    };
    if (typeof globalThis.localStorage === 'undefined') return defaults;
    try {
      const raw = globalThis.localStorage.getItem(THEATER_STORAGE_KEY);
      if (!raw) return defaults;
      const parsed = JSON.parse(raw) as Partial<TheaterAvrPersistedConfig>;
      return {
        ...defaults,
        baseUrl: typeof parsed.baseUrl === 'string' ? parsed.baseUrl : '',
        token: typeof parsed.token === 'string' ? parsed.token : '',
        serverId: typeof parsed.serverId === 'string' ? parsed.serverId : '',
        clientId: typeof parsed.clientId === 'string' && parsed.clientId ? parsed.clientId : defaults.clientId,
        selectedRoomId: typeof parsed.selectedRoomId === 'string' ? parsed.selectedRoomId : null,
        enabled: Boolean(parsed.enabled && parsed.baseUrl && parsed.token),
        rooms: Array.isArray(parsed.rooms) ? parsed.rooms : [],
      };
    } catch {
      return defaults;
    }
  }

  private savePersistedState() {
    if (typeof globalThis.localStorage === 'undefined') return;
    const payload: TheaterAvrPersistedConfig = {
      baseUrl: this.state.baseUrl,
      token: this.state.token,
      serverId: this.state.serverId,
      clientId: this.state.clientId,
      selectedRoomId: this.state.selectedRoomId,
      enabled: this.state.enabled,
      rooms: this.state.rooms,
    };
    globalThis.localStorage.setItem(THEATER_STORAGE_KEY, JSON.stringify(payload));
  }

  private authHeaders(roomId?: string | null): Record<string, string> {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.state.token}`,
    };
    const rid = roomId ?? this.state.selectedRoomId;
    if (rid) headers['X-Avr-Room-Id'] = rid;
    return headers;
  }

  async pairWithPin(
    rawHost: string,
    pin: string,
    clientName = 'SpatialFin WebXR',
    pageProtocol?: string,
  ): Promise<TheaterAvrState> {
    const baseUrl = normalizeAvrBaseUrl(rawHost, pageProtocol);
    const cleanPin = pin.replace(/\D/g, '').slice(0, 6);
    if (cleanPin.length !== 6) {
      throw new TheaterAvrError('invalid_request', 'Enter the 6-digit pairing PIN from spatialfin-avr.');
    }

    const res = await fetch(`${baseUrl}/api/v1/pair/claim`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        pin: cleanPin,
        client_id: this.state.clientId,
        client_name: clientName,
      }),
    });
    if (!res.ok) {
      const errBody = await res.json().catch(() => ({})) as { error_code?: string; message?: string };
      throw new TheaterAvrError(
        errBody.error_code ?? 'pair_failed',
        errBody.message ?? `Pairing failed (HTTP ${res.status})`,
        res.status,
      );
    }
    const data = (await res.json()) as { avr_token: string; server_id: string };
    this.emitState(
      {
        baseUrl,
        token: data.avr_token,
        serverId: data.server_id,
        enabled: true,
        lastError: null,
      },
      true,
    );
    await this.refreshRooms();
    return this.getState();
  }

  async connectWithToken(
    rawHost: string,
    token: string,
    pageProtocol?: string,
  ): Promise<TheaterAvrState> {
    const baseUrl = normalizeAvrBaseUrl(rawHost, pageProtocol);
    const cleanToken = token.trim();
    if (!cleanToken) {
      throw new TheaterAvrError('auth_invalid', 'Enter a valid Bearer token.');
    }
    const infoRes = await fetch(`${baseUrl}/api/v1/info`, {
      headers: { Authorization: `Bearer ${cleanToken}` },
    });
    if (!infoRes.ok) {
      throw new TheaterAvrError('auth_invalid', `Server rejected token (HTTP ${infoRes.status})`, infoRes.status);
    }
    const info = (await infoRes.json()) as { server_id?: string };
    this.emitState(
      {
        baseUrl,
        token: cleanToken,
        serverId: info.server_id ?? this.state.serverId,
        enabled: true,
        lastError: null,
      },
      true,
    );
    await this.refreshRooms();
    return this.getState();
  }

  async refreshRooms(): Promise<TheaterRoomSummary[]> {
    if (!this.state.baseUrl || !this.state.token) return [];
    const res = await fetch(`${this.state.baseUrl}/api/v1/rooms`, {
      headers: { Authorization: `Bearer ${this.state.token}` },
    });
    if (!res.ok) {
      throw new TheaterAvrError('http_error', `Failed to list rooms (HTTP ${res.status})`, res.status);
    }
    const data = (await res.json()) as { rooms?: TheaterRoomSummary[] };
    const rooms = Array.isArray(data.rooms) ? data.rooms : [];
    const currentStillExists = rooms.some((r) => r.room_id === this.state.selectedRoomId);
    const nextRoomId = currentStillExists
      ? this.state.selectedRoomId
      : (rooms.find((r) => r.is_default)?.room_id ?? rooms[0]?.room_id ?? null);
    this.emitState({ rooms, selectedRoomId: nextRoomId, lastError: null }, true);
    return rooms;
  }

  selectRoom(roomId: string | null) {
    if (roomId === null) {
      void this.detachPlayback('local_audio');
      this.emitState({ enabled: false, selectedRoomId: null }, true);
    } else {
      this.emitState({ enabled: true, selectedRoomId: roomId, lastError: null }, true);
    }
  }

  disconnectAndForget() {
    void this.detachPlayback('user_disconnect');
    if (typeof globalThis.localStorage !== 'undefined') {
      globalThis.localStorage.removeItem(THEATER_STORAGE_KEY);
    }
    this.emitState(
      {
        baseUrl: '',
        token: '',
        serverId: '',
        rooms: [],
        selectedRoomId: null,
        enabled: false,
        connected: false,
        activeSessionId: null,
        rendererMode: null,
        activeOamdObjects: null,
        fallbackActive: false,
        activeDecodeChain: null,
        lastError: null,
      },
      false,
    );
  }

  async updateRoomControls(patch: {
    master_volume?: number;
    muted?: boolean;
    eq_bypass?: boolean;
    upmix_mode?: string;
  }): Promise<void> {
    if (!this.state.baseUrl || !this.state.token) return;
    const res = await fetch(`${this.state.baseUrl}/api/v1/room/controls`, {
      method: 'POST',
      headers: {
        ...this.authHeaders(),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(patch),
    });
    if (!res.ok) {
      throw new TheaterAvrError('controls_failed', `Room control update failed (HTTP ${res.status})`, res.status);
    }
    const status = (await res.json()) as {
      master_volume?: number;
      muted?: boolean;
      eq_bypass?: boolean;
      upmix_mode?: string;
      renderer_mode?: string | null;
      active_oamd_objects?: number | null;
      fallback_active?: boolean | null;
    };
    this.emitState({
      masterVolume: typeof status.master_volume === 'number' ? status.master_volume : this.state.masterVolume,
      muted: typeof status.muted === 'boolean' ? status.muted : this.state.muted,
      eqBypass: typeof status.eq_bypass === 'boolean' ? status.eq_bypass : this.state.eqBypass,
      upmixMode: typeof status.upmix_mode === 'string' ? status.upmix_mode : this.state.upmixMode,
      rendererMode: status.renderer_mode ?? this.state.rendererMode,
      activeOamdObjects: status.active_oamd_objects ?? this.state.activeOamdObjects,
      fallbackActive: Boolean(status.fallback_active ?? this.state.fallbackActive),
      lastError: null,
    });
  }

  /**
   * Attaches Spec 0 Theater Audio synchronization to an active `<video>` element (used by both
   * 2D `BrowserApp` and 3D WebXR `PlayerSpace`).
   */
  async attachPlayback(
    video: HTMLVideoElement,
    params: TheaterPlaybackAttachParams,
  ): Promise<boolean> {
    if (!this.state.enabled || !this.state.baseUrl || !this.state.token) {
      return false;
    }

    await this.detachPlayback('reattach');

    try {
      // 1. Mint a 15 s single-use browser WS ticket (no Bearer token in WS query string).
      const ticketRes = await fetch(`${this.state.baseUrl}/api/v1/ws/ticket`, {
        method: 'POST',
        headers: this.authHeaders(),
      });
      if (!ticketRes.ok) {
        throw new TheaterAvrError(
          ticketRes.status === 401 ? 'auth_invalid' : 'ticket_failed',
          `Failed to mint WebSocket ticket (HTTP ${ticketRes.status})`,
          ticketRes.status,
        );
      }
      const { ticket } = (await ticketRes.json()) as { ticket: string; expires_in_s: number };
      const wsBase = this.state.baseUrl.replace(/^http/i, 'ws');
      const roomQuery = this.state.selectedRoomId
        ? `?room_id=${encodeURIComponent(this.state.selectedRoomId)}`
        : '';
      const wsUrl = `${wsBase}/ws/v1/control${roomQuery}`;

      await this.openWebSocket(wsUrl, buildWsTicketSubprotocols(ticket));

      // 2. Spec 0 hello + 3x clock.ping sync.
      await this.sendRpc('hello', {
        client_id: this.state.clientId,
        client_name: params.formFactor === 'webxr' ? 'SpatialFin WebXR' : 'SpatialFin Browser',
        form_factor: params.formFactor,
        app_version: '2.7.74',
        min_protocol_version: 1,
        max_protocol_version: 1,
      });
      await this.syncClock(3);

      // 3. Build credential-free direct Jellyfin stream URL and start AVR session.
      const sessionId = generateId('webxr-sess');
      const streamUrl = buildDirectJellyfinStreamUrl(
        params.jellyfinServerUrl,
        params.itemId,
        params.mediaSourceId,
        params.audioStreamIndex,
      );
      const startPtsUs = Math.max(0, Math.round((video.currentTime || 0) * 1_000_000));
      const startOk = await this.sendRpc(
        'session.start',
        {
          source_type: 'jellyfin_stream',
          stream_url: streamUrl,
          stream_layout: 'multi_stream_container',
          media_source_id: params.mediaSourceId,
          audio_stream_index: params.audioStreamIndex,
          source_codec: params.sourceCodec ?? 'aac',
          target_pts_us: startPtsUs,
          jellyfin_token: params.jellyfinToken,
          force_takeover: true,
        },
        sessionId,
      );
      this.barrierSeq = Number(startOk.barrier_seq ?? 1);
      this.fsmState = 'awaiting_go';

      // Mute local browser <video> audio so only spatialfin-avr outputs sound.
      this.attachedVideo = video;
      this.previousVideoMuted = video.muted;
      video.muted = true;

      this.emitState({
        connected: true,
        activeSessionId: sessionId,
        lastError: null,
      });

      // 4. Signal playback.ready and start 2 s heartbeat + 2 Hz video_timing loop.
      const readyOk = await this.sendRpc(
        'playback.ready',
        {
          barrier_seq: this.barrierSeq,
          actual_pts_us: Math.max(0, Math.round((video.currentTime || 0) * 1_000_000)),
        },
        sessionId,
      );
      this.fsmState = video.paused ? 'paused' : 'playing';
      this.emitState({
        rendererMode: typeof readyOk.renderer_mode === 'string' ? readyOk.renderer_mode : this.state.rendererMode,
        activeDecodeChain:
          typeof readyOk.active_decode_chain === 'string'
            ? readyOk.active_decode_chain
            : this.state.activeDecodeChain,
      });

      this.startHeartbeatLoop(sessionId);
      this.startVideoTimingLoop(video, sessionId);
      this.bindVideoTransportListeners(video, sessionId);
      return true;
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.emitState({ lastError: msg, connected: false, activeSessionId: null });
      await this.detachPlayback('attach_failed');
      return false;
    }
  }

  async detachPlayback(reason = 'user_exit'): Promise<void> {
    this.stopHeartbeat?.();
    this.stopHeartbeat = null;
    this.stopTimingLoop?.();
    this.stopTimingLoop = null;
    this.detachVideoListeners?.();
    this.detachVideoListeners = null;

    if (this.attachedVideo) {
      this.attachedVideo.muted = this.previousVideoMuted;
      this.attachedVideo = null;
    }

    const sid = this.state.activeSessionId;
    if (this.ws && this.ws.readyState === WebSocket.OPEN && sid) {
      try {
        await this.sendRpc('session.stop', { reason }, sid, 1_500);
      } catch {
        // Ignore teardown RPC errors.
      }
    }
    this.closeWebSocket();
    this.fsmState = 'idle';
    if (this.state.connected || this.state.activeSessionId) {
      this.emitState({ connected: false, activeSessionId: null });
    }
  }

  private openWebSocket(wsUrl: string, protocols: [string, string]): Promise<void> {
    this.closeWebSocket();
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(wsUrl, protocols);
      this.ws = ws;
      let settled = false;
      const timer = globalThis.setTimeout(() => {
        if (!settled) {
          settled = true;
          ws.close();
          reject(new TheaterAvrError('timeout', 'Timed out connecting to spatialfin-avr WebSocket.'));
        }
      }, 5_000);

      ws.onopen = () => {
        if (!settled) {
          settled = true;
          globalThis.clearTimeout(timer);
          resolve();
        }
      };
      ws.onerror = () => {
        if (!settled) {
          settled = true;
          globalThis.clearTimeout(timer);
          reject(new TheaterAvrError('ws_error', 'WebSocket connection to spatialfin-avr failed.'));
        }
      };
      ws.onclose = () => {
        this.rejectAllPending(new TheaterAvrError('disconnected', 'Theater AVR WebSocket closed.'));
        if (this.ws === ws) {
          this.ws = null;
          this.emitState({ connected: false, activeSessionId: null });
        }
      };
      ws.onmessage = (event) => {
        if (typeof event.data === 'string') {
          this.handleIncomingFrame(event.data);
        }
      };
    });
  }

  private closeWebSocket() {
    this.rejectAllPending(new TheaterAvrError('closed', 'WebSocket closed.'));
    if (this.ws) {
      try {
        this.ws.close(1000, 'done');
      } catch {
        // Ignore close errors.
      }
      this.ws = null;
    }
  }

  private rejectAllPending(err: Error) {
    this.pendingRpcs.forEach((entry) => {
      globalThis.clearTimeout(entry.timer);
      entry.reject(err);
    });
    this.pendingRpcs.clear();
  }

  private sendEnvelope(env: WsEnvelope) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    this.ws.send(JSON.stringify(env));
  }

  private sendRpc(
    msgType: string,
    payload: Record<string, unknown>,
    sessionId?: string,
    timeoutMs = 5_000,
  ): Promise<Record<string, unknown>> {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new TheaterAvrError('disconnected', 'Theater AVR WebSocket is not open.'));
    }
    const id = `webxr-${++this.rpcSeq}`;
    const env = buildWsEnvelope(msgType, payload, { id, sessionId });
    return new Promise((resolve, reject) => {
      const timer = globalThis.setTimeout(() => {
        this.pendingRpcs.delete(id);
        reject(new TheaterAvrError('timeout', `RPC ${msgType} timed out.`));
      }, timeoutMs);
      this.pendingRpcs.set(id, { resolve, reject, timer });
      this.sendEnvelope(env);
    });
  }

  private async syncClock(samples: number): Promise<void> {
    let bestRtt = Number.POSITIVE_INFINITY;
    let bestTheta = 0;
    for (let i = 0; i < samples; i++) {
      const t1 = nowUs();
      const ok = await this.sendRpc('clock.ping', { t1_client_us: t1 });
      const t4 = nowUs();
      const t2 = Number(ok.t2_avr_rx_us ?? t1);
      const t3 = Number(ok.t3_avr_tx_us ?? t2);
      const rtt = Math.max(0, t4 - t1 - (t3 - t2));
      const theta = Math.round((t2 - t1 + (t3 - t4)) / 2);
      if (rtt < bestRtt) {
        bestRtt = rtt;
        bestTheta = theta;
      }
    }
    this.clockOffsetUs = bestTheta;
    this.clockUncertaintyUs = Math.max(500, Math.round(bestRtt / 2));
  }

  private startHeartbeatLoop(sessionId: string) {
    this.stopHeartbeat?.();
    this.stopHeartbeat = createWorkerInterval(2_000, () => {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
      this.sendEnvelope(
        buildWsEnvelope(
          'session.heartbeat',
          { barrier_seq: this.barrierSeq, state: this.fsmState },
          { sessionId },
        ),
      );
    });
  }

  private startVideoTimingLoop(video: HTMLVideoElement, sessionId: string) {
    this.stopTimingLoop?.();
    let cancelled = false;
    let lastEmitMs = 0;

    const emitBeacon = (mediaTimeSec: number, visibleClientUs: number) => {
      if (cancelled || video.paused || !this.ws || this.ws.readyState !== WebSocket.OPEN) return;
      const nowMs = typeof performance !== 'undefined' ? performance.now() : Date.now();
      if (nowMs - lastEmitMs < 480) return; // 2 Hz cadence
      lastEmitMs = nowMs;
      this.sendEnvelope(
        buildWsEnvelope(
          'playback.video_timing',
          {
            barrier_seq: this.barrierSeq,
            media_pts_us: Math.max(0, Math.round(mediaTimeSec * 1_000_000)),
            visible_avr_us: visibleClientUs + this.clockOffsetUs,
            clock_uncertainty_us: this.clockUncertaintyUs,
          },
          { sessionId },
        ),
      );
    };

    const rVfcVideo = video as HTMLVideoElement & {
      requestVideoFrameCallback?: (cb: (now: number, meta: { mediaTime: number; presentationTime: number }) => void) => number;
      cancelVideoFrameCallback?: (id: number) => void;
    };

    if (typeof rVfcVideo.requestVideoFrameCallback === 'function') {
      let handleId = 0;
      const onFrame = (_now: number, meta: { mediaTime: number; presentationTime: number }) => {
        if (cancelled) return;
        emitBeacon(meta.mediaTime, Math.round(meta.presentationTime * 1_000));
        handleId = rVfcVideo.requestVideoFrameCallback!(onFrame);
      };
      handleId = rVfcVideo.requestVideoFrameCallback(onFrame);
      this.stopTimingLoop = () => {
        cancelled = true;
        if (handleId && typeof rVfcVideo.cancelVideoFrameCallback === 'function') {
          rVfcVideo.cancelVideoFrameCallback(handleId);
        }
      };
    } else {
      const stopFallback = createWorkerInterval(500, () => {
        emitBeacon(video.currentTime || 0, nowUs());
      });
      this.stopTimingLoop = () => {
        cancelled = true;
        stopFallback();
      };
    }
  }

  private bindVideoTransportListeners(video: HTMLVideoElement, sessionId: string) {
    const onPause = () => {
      this.fsmState = 'paused';
      this.barrierSeq += 1;
      void this.sendRpc('playback.pause', { barrier_seq: this.barrierSeq, flush: true }, sessionId).catch(() => undefined);
    };
    const onPlay = () => {
      this.fsmState = 'playing';
      this.barrierSeq += 1;
      void this.sendRpc(
        'playback.ready',
        {
          barrier_seq: this.barrierSeq,
          actual_pts_us: Math.max(0, Math.round((video.currentTime || 0) * 1_000_000)),
        },
        sessionId,
      ).catch(() => undefined);
    };
    const onSeeked = () => {
      if (video.paused) return;
      this.barrierSeq += 1;
      void this.sendRpc('playback.pause', { barrier_seq: this.barrierSeq, flush: true }, sessionId)
        .then(() =>
          this.sendRpc(
            'playback.ready',
            {
              barrier_seq: this.barrierSeq,
              actual_pts_us: Math.max(0, Math.round((video.currentTime || 0) * 1_000_000)),
            },
            sessionId,
          ),
        )
        .catch(() => undefined);
    };
    video.addEventListener('pause', onPause);
    video.addEventListener('play', onPlay);
    video.addEventListener('seeked', onSeeked);
    this.detachVideoListeners = () => {
      video.removeEventListener('pause', onPause);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('seeked', onSeeked);
    };
  }

  private handleIncomingFrame(raw: string) {
    let env: WsEnvelope;
    try {
      env = JSON.parse(raw) as WsEnvelope;
    } catch {
      return;
    }
    if (env.type === 'rpc.ok' && env.id) {
      const pending = this.pendingRpcs.get(env.id);
      if (pending) {
        this.pendingRpcs.delete(env.id);
        globalThis.clearTimeout(pending.timer);
        pending.resolve(env.payload ?? {});
      }
      return;
    }
    if (env.type === 'rpc.error' && env.id) {
      const pending = this.pendingRpcs.get(env.id);
      if (pending) {
        this.pendingRpcs.delete(env.id);
        globalThis.clearTimeout(pending.timer);
        const code = String(env.payload?.code ?? 'rpc_error');
        const message = String(env.payload?.message ?? 'Theater AVR RPC failed');
        pending.reject(new TheaterAvrError(code, message));
      }
      return;
    }
    if (env.type === 'telemetry.session' && env.payload) {
      this.emitState({
        rendererMode: typeof env.payload.renderer_mode === 'string' ? env.payload.renderer_mode : this.state.rendererMode,
        activeDecodeChain:
          typeof env.payload.active_decode_chain === 'string'
            ? env.payload.active_decode_chain
            : this.state.activeDecodeChain,
        activeOamdObjects:
          typeof env.payload.active_oamd_objects === 'number'
            ? env.payload.active_oamd_objects
            : null,
        fallbackActive: Boolean(env.payload.fallback_active),
        masterVolume:
          typeof env.payload.master_volume === 'number'
            ? env.payload.master_volume
            : this.state.masterVolume,
        muted: typeof env.payload.muted === 'boolean' ? env.payload.muted : this.state.muted,
        eqBypass: typeof env.payload.eq_bypass === 'boolean' ? env.payload.eq_bypass : this.state.eqBypass,
        upmixMode: typeof env.payload.upmix_mode === 'string' ? env.payload.upmix_mode : this.state.upmixMode,
      });
    } else if (env.type === 'room.state_changed' && env.payload) {
      this.emitState({
        masterVolume:
          typeof env.payload.master_volume === 'number'
            ? env.payload.master_volume
            : this.state.masterVolume,
        muted: typeof env.payload.muted === 'boolean' ? env.payload.muted : this.state.muted,
        eqBypass: typeof env.payload.eq_bypass === 'boolean' ? env.payload.eq_bypass : this.state.eqBypass,
        upmixMode: typeof env.payload.upmix_mode === 'string' ? env.payload.upmix_mode : this.state.upmixMode,
      });
    } else if (env.type === 'decoder.fallback' && env.payload) {
      this.emitState({
        rendererMode: typeof env.payload.renderer_mode === 'string' ? env.payload.renderer_mode : 'bed',
        activeDecodeChain:
          typeof env.payload.to_chain === 'string' ? env.payload.to_chain : this.state.activeDecodeChain,
        activeOamdObjects: null,
        fallbackActive: true,
      });
    }
  }
}

export const theaterAvrClient = new TheaterAvrClient();
