import {fetchJellyfin} from './network.ts';
import {MusicAssistantManager} from './musicassistant/MusicAssistantManager.ts';
import {clearPreferences} from './api.ts';

const SESSION_KEY = 'spatialfin_session_v1';
const DEVICE_ID_KEY = 'spatialfin_device_id';

const LEGACY_TOKEN_KEY = 'spatialfin_token';
const LEGACY_SERVER_KEY = 'spatialfin_server';
const LEGACY_USER_ID_KEY = 'spatialfin_userid';
const LEGACY_GEMINI_KEY = 'spatialfin_gemini_key';

const CLIENT_NAME = 'SpatialFin WebXR';
const CLIENT_VERSION = '0.1.0';
const REQUEST_TIMEOUT_MS = 15_000;
const LOGOUT_TIMEOUT_MS = 1_000;

export interface AuthSession {
  accessToken: string;
  serverUrl: string;
  userId: string;
  userName: string;
  geminiKey?: string;
  companionUrl?: string;
  setupToken?: string;
  isDedicatedToken?: boolean;
  musicAssistant?: {
    url: string;
    token?: string;
    username?: string;
  };
}

interface AuthenticationResult {
  AccessToken?: string;
  User?: {
    Id?: string;
    Name?: string;
  };
}

export class JellyfinAuthError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = 'JellyfinAuthError';
    this.status = status;
  }

  get isTransient(): boolean {
    return (
      this.status === undefined ||
      this.status === 408 ||
      this.status === 429 ||
      this.status >= 500
    );
  }
}

export interface AuthHeaderOptions {
  accept?: string | null;
  contentType?: string | null;
  includeToken?: boolean;
}

function getStorage(remember: boolean): Storage {
  return remember ? localStorage : sessionStorage;
}

function parseSession(value: string | null): AuthSession | null {
  if (!value) return null;

  try {
    const session = JSON.parse(value) as Partial<AuthSession>;
    if (
      typeof session.accessToken === 'string' &&
      typeof session.serverUrl === 'string' &&
      typeof session.userId === 'string' &&
      typeof session.userName === 'string' &&
      session.accessToken.length > 0 &&
      session.serverUrl.length > 0 &&
      session.userId.length > 0
    ) {
      return session as AuthSession;
    }
  } catch {
    // Invalid storage is handled as a signed-out session.
  }

  return null;
}

function migrateLegacySession(): AuthSession | null {
  const accessToken = localStorage.getItem(LEGACY_TOKEN_KEY);
  const serverUrl = localStorage.getItem(LEGACY_SERVER_KEY);
  const userId = localStorage.getItem(LEGACY_USER_ID_KEY);

  if (!accessToken || !serverUrl || !userId) return null;

  const session: AuthSession = {
    accessToken,
    serverUrl,
    userId,
    userName: '',
    geminiKey: localStorage.getItem(LEGACY_GEMINI_KEY) || undefined,
  };
  localStorage.setItem(SESSION_KEY, JSON.stringify(session));
  clearLegacySession();
  return session;
}

function clearLegacySession() {
  localStorage.removeItem(LEGACY_TOKEN_KEY);
  localStorage.removeItem(LEGACY_SERVER_KEY);
  localStorage.removeItem(LEGACY_USER_ID_KEY);
  localStorage.removeItem(LEGACY_GEMINI_KEY);
}

export function getSession(): AuthSession | null {
  return (
    parseSession(sessionStorage.getItem(SESSION_KEY)) ??
    parseSession(localStorage.getItem(SESSION_KEY)) ??
    migrateLegacySession()
  );
}

export function isAuthenticated(): boolean {
  return getSession() !== null;
}

export function getServerUrl(): string | null {
  return getSession()?.serverUrl ?? null;
}

export function getUserId(): string | null {
  return getSession()?.userId ?? null;
}

export function getAccessToken(): string | null {
  return getSession()?.accessToken ?? null;
}

export function mediaUrlWithAccessToken(streamUrl: string): string {
  const base =
    typeof window !== 'undefined' && window.location?.href
      ? window.location.href
      : 'http://localhost/';
  const url = new URL(streamUrl, base);
  const token = getAccessToken();
  if (token && !url.searchParams.has('api_key') && !url.searchParams.has('ApiKey')) {
    url.searchParams.set('api_key', token);
  }
  return url.toString();
}

export function getGeminiKey(): string | undefined {
  const s = getSession();
  return s ? s.geminiKey : undefined;
}

export function getCompanionUrl(): string | undefined {
  const s = getSession();
  return s ? s.companionUrl : undefined;
}

export function getCompanionSetupToken(): string | undefined {
  const s = getSession();
  return s?.setupToken?.trim() || undefined;
}

function updateStoredSession(mutator: (session: AuthSession) => AuthSession): void {
  const fromSession = parseSession(sessionStorage.getItem(SESSION_KEY));
  if (fromSession) {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(mutator(fromSession)));
    return;
  }
  const fromLocal = parseSession(localStorage.getItem(SESSION_KEY));
  if (fromLocal) {
    localStorage.setItem(SESSION_KEY, JSON.stringify(mutator(fromLocal)));
  }
}

export function clearCompanionSetupToken(): void {
  updateStoredSession((session) => {
    const next: AuthSession = { ...session };
    delete next.setupToken;
    return next;
  });
}

export async function resolveCompanionSetupToken(
  promptForToken: () => Promise<string | null>,
): Promise<string | null> {
  const existing = getCompanionSetupToken();
  if (existing) return existing;

  const entered = await promptForToken();
  const trimmed = entered?.trim() || '';
  if (!trimmed) return null;

  updateStoredSession((session) => ({
    ...session,
    setupToken: trimmed,
  }));
  return trimmed;
}

export function getDeviceId(): string {
  const existing = localStorage.getItem(DEVICE_ID_KEY);
  if (existing) return existing;

  const id =
    typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `webxr-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  localStorage.setItem(DEVICE_ID_KEY, id);
  return id;
}

function quoteHeaderValue(value: string): string {
  return value.replaceAll('\\', '\\\\').replaceAll('"', '\\"');
}

export function getAuthorizationHeader(token = getAccessToken()): string {
  const fields = [
    `Client="${quoteHeaderValue(CLIENT_NAME)}"`,
    'Device="Browser"',
    `DeviceId="${quoteHeaderValue(getDeviceId())}"`,
    `Version="${CLIENT_VERSION}"`,
  ];
  if (token) fields.push(`Token="${quoteHeaderValue(token)}"`);
  return `MediaBrowser ${fields.join(', ')}`;
}

export function getAuthHeaders(
  options: boolean | AuthHeaderOptions = {},
): HeadersInit {
  const normalizedOptions: AuthHeaderOptions =
    typeof options === 'boolean'
      ? {contentType: options ? 'application/json' : null}
      : options;
  const headers: Record<string, string> = {
    Authorization: getAuthorizationHeader(
      normalizedOptions.includeToken === false ? null : undefined,
    ),
  };
  const accept = normalizedOptions.accept === undefined
    ? 'application/json'
    : normalizedOptions.accept;
  if (accept) headers.Accept = accept;
  if (normalizedOptions.contentType) {
    headers['Content-Type'] = normalizedOptions.contentType;
  }
  return headers;
}

interface PageLocation {
  origin: string;
  protocol: string;
}

function defaultPageLocation(): PageLocation {
  if (typeof window !== 'undefined' && window.location) {
    return window.location;
  }
  return { origin: 'http://localhost', protocol: 'http:' };
}

export function normalizeServerUrl(
  value: string,
  pageLocation: PageLocation = defaultPageLocation(),
): string {
  let input = value.trim();
  if (!input) throw new JellyfinAuthError('Enter your Jellyfin server address.');

  if (input.startsWith('/')) {
    input = new URL(input, pageLocation.origin).toString();
  } else if (!/^[a-z][a-z\d+.-]*:\/\//i.test(input)) {
    // Keep scheme-less input secure. Local HTTP is an explicit opt-in because
    // silently downgrading could expose credentials intended for local HTTPS.
    const inferredProtocol = pageLocation.protocol === 'http:' ? 'http:' : 'https:';
    input = `${inferredProtocol}//${input}`;
  }

  let url: URL;
  try {
    url = new URL(input);
  } catch {
    throw new JellyfinAuthError('The server address is not a valid URL.');
  }

  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new JellyfinAuthError('The Jellyfin server must use HTTP or HTTPS.');
  }
  if (url.username || url.password) {
    throw new JellyfinAuthError('Do not include credentials in the server URL.');
  }
  url.hash = '';
  url.search = '';
  url.pathname = url.pathname
    .replace(/\/+$/, '')
    .replace(/\/web(?:\/index\.html)?$/i, '');

  return url.toString().replace(/\/$/, '');
}

function serverEndpoint(serverUrl: string, path: string): string {
  return `${serverUrl}${path.startsWith('/') ? path : `/${path}`}`;
}

function assertServerCanBeRequested(serverUrl: string) {
  let url: URL;
  try {
    url = new URL(serverUrl);
  } catch {
    throw new JellyfinAuthError(
      'The saved Jellyfin server address is invalid. Sign in again with the server base URL.',
      400,
    );
  }

  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new JellyfinAuthError(
      'The saved Jellyfin server address must use HTTP or HTTPS.',
      400,
    );
  }
}

export function normalizeCompanionUrl(rawInput: string): string {
  let input = rawInput.trim();
  if (!input) {
    throw new JellyfinAuthError('Invalid Companion App address.');
  }

  if (!/^[a-z][a-z\d+.-]*:\/\//i.test(input)) {
    const suffixIdx = input.search(/[/?#]/);
    let authority = suffixIdx >= 0 ? input.slice(0, suffixIdx) : input;
    const suffix = suffixIdx >= 0 ? input.slice(suffixIdx) : '';

    if (authority.startsWith('[')) {
      if (authority.endsWith(']')) {
        authority = `${authority}:1982`;
      } else if (!/^\[[^\]]+\]:\d+$/.test(authority)) {
        throw new JellyfinAuthError('Invalid Companion App address.');
      }
    } else if ((authority.match(/:/g)?.length ?? 0) > 1) {
      authority = `[${authority}]:1982`;
    } else if (!authority.includes(':')) {
      authority = `${authority}:1982`;
    }

    input = `http://${authority}${suffix}`;
  }

  let url: URL;
  try {
    url = new URL(input);
  } catch {
    throw new JellyfinAuthError('Invalid Companion App address.');
  }

  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new JellyfinAuthError('The Companion App must use HTTP or HTTPS.');
  }
  if (url.username || url.password) {
    throw new JellyfinAuthError('Do not include credentials in the Companion App URL.');
  }

  const basePath = url.pathname.replace(/\/+$/, '');
  return `${url.origin}${basePath}`;
}

export async function fetchWithTimeout(
  input: RequestInfo | URL,
  init: RequestInit = {},
  timeoutMs = REQUEST_TIMEOUT_MS,
): Promise<Response> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  init.signal?.addEventListener('abort', abort, { once: true });
  const timeout = globalThis.setTimeout(abort, timeoutMs);

  try {
    return await fetchJellyfin(input, {...init, signal: controller.signal});
  } finally {
    init.signal?.removeEventListener('abort', abort);
    globalThis.clearTimeout(timeout);
  }
}

async function responseMessage(response: Response): Promise<string> {
  const text = (await response.text()).trim();
  if (!text || text.startsWith('<')) return response.statusText || 'Request failed';
  return text.slice(0, 240);
}

function networkErrorMessage(serverUrl: string, error: unknown): string {
  if (error instanceof DOMException && error.name === 'AbortError') {
    return `The Jellyfin server at ${serverUrl} did not respond in time.`;
  }
  if (typeof window !== 'undefined' && window.location?.protocol === 'https:' && serverUrl.startsWith('http:')) {
    return `Could not reach ${serverUrl}. Allow SpatialFin local-network access and check Jellyfin CORS. If this browser does not support direct local HTTP, use Jellyfin HTTPS or a same-origin reverse proxy.`;
  }
  return `Could not reach ${serverUrl}. Check the address, TLS certificate, and Jellyfin CORS settings.`;
}

export async function authenticateJellyfinUser(
  serverUrl: string,
  username: string,
  password: string
): Promise<{ accessToken: string, userId: string, userName: string }> {
  const normalizedUsername = username.trim();
  if (!normalizedUsername) {
    throw new JellyfinAuthError('Enter your Jellyfin username.');
  }

  let response: Response;
  try {
    response = await fetchWithTimeout(
      serverEndpoint(serverUrl, '/Users/AuthenticateByName'),
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          Authorization: getAuthorizationHeader(null),
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({Username: normalizedUsername, Pw: password}),
      },
    );
  } catch (error) {
    throw new JellyfinAuthError(networkErrorMessage(serverUrl, error));
  }

  if (!response.ok) {
    if (response.status === 401) {
      throw new JellyfinAuthError('The username or password was rejected.', 401);
    }
    if (response.status === 404) {
      throw new JellyfinAuthError(
        'No Jellyfin API was found at that address. Enter the server base URL, including any configured base path.',
        404,
      );
    }
    throw new JellyfinAuthError(
      `Jellyfin returned ${response.status}: ${await responseMessage(response)}`,
      response.status,
    );
  }

  let result: AuthenticationResult;
  try {
    result = (await response.json()) as AuthenticationResult;
  } catch {
    throw new JellyfinAuthError('Jellyfin returned an invalid login response.');
  }
  if (!result.AccessToken || !result.User?.Id) {
    throw new JellyfinAuthError('Jellyfin returned an incomplete login response.');
  }
  
  return {
    accessToken: result.AccessToken,
    userId: result.User.Id,
    userName: result.User.Name ?? normalizedUsername,
  };
}

export async function login(
  serverInput: string,
  username: string,
  password: string,
  geminiKey = '',
  remember = true,
): Promise<AuthSession> {
  const serverUrl = normalizeServerUrl(serverInput);
  const authInfo = await authenticateJellyfinUser(serverUrl, username, password);

  const session: AuthSession = {
    accessToken: authInfo.accessToken,
    serverUrl,
    userId: authInfo.userId,
    userName: authInfo.userName,
    geminiKey: geminiKey.trim() || undefined,
    isDedicatedToken: true,
  };

  sessionStorage.removeItem(SESSION_KEY);
  localStorage.removeItem(SESSION_KEY);
  getStorage(remember).setItem(SESSION_KEY, JSON.stringify(session));
  clearLegacySession();
  return session;
}

async function resolveJellyfinUser(serverUrl: string, token: string): Promise<{id: string, name: string}> {
  let response: Response;
  try {
    response = await fetchWithTimeout(serverEndpoint(serverUrl, '/Users/Me'), {
      headers: {
        Authorization: getAuthorizationHeader(token),
        Accept: 'application/json'
      }
    });
  } catch (error) {
    throw new JellyfinAuthError(networkErrorMessage(serverUrl, error));
  }

  if (!response.ok) {
    throw new JellyfinAuthError('The provided access token was rejected by Jellyfin.', response.status);
  }

  const data = await response.json();
  if (!data.Id) throw new JellyfinAuthError('Failed to resolve Jellyfin user ID.');
  return { id: data.Id, name: data.Name };
}

function sanitizeMusicAssistant(
  raw: { url?: unknown; token?: unknown; username?: unknown; password?: unknown } | null | undefined,
): AuthSession['musicAssistant'] | undefined {
  if (!raw || typeof raw.url !== 'string' || !raw.url.trim()) {
    return undefined;
  }
  const result: NonNullable<AuthSession['musicAssistant']> = {
    url: raw.url.trim(),
  };
  if (typeof raw.token === 'string' && raw.token) {
    result.token = raw.token;
  }
  if (typeof raw.username === 'string' && raw.username) {
    result.username = raw.username;
  }
  return result;
}

export function normalizeUuid(value: string | null | undefined): string {
  return String(value ?? '').trim().toLowerCase().replace(/-/g, '');
}

export interface CompanionUserCandidate {
  serverId: string;
  serverName: string;
  serverUrl: string;
  userId: string;
  username: string;
}

export class CompanionBrokerFallbackError extends Error {
  readonly code: 'qc_disabled' | 'no_verified_user' | 'qc_broker_rejected';
  readonly serverUrl: string;
  readonly username?: string;
  constructor(
    code: 'qc_disabled' | 'no_verified_user' | 'qc_broker_rejected',
    serverUrl: string,
    username?: string,
  ) {
    super(
      code === 'qc_disabled'
        ? 'Quick Connect is disabled on this Jellyfin server. Sign in with your username and password.'
        : code === 'no_verified_user'
          ? 'This user must be re-linked in Companion or signed in directly.'
          : 'Companion could not complete Quick Connect sign-in. Sign in with your username and password.',
    );
    this.name = 'CompanionBrokerFallbackError';
    this.code = code;
    this.serverUrl = serverUrl;
    this.username = username;
  }
}

export class CompanionUserSelectionCancelledError extends Error {
  constructor() {
    super('Companion user selection cancelled');
    this.name = 'CompanionUserSelectionCancelledError';
  }
}

export class CompanionUserSelectionRequiredError extends Error {
  readonly candidates: CompanionUserCandidate[];
  constructor(candidates: CompanionUserCandidate[]) {
    super('Companion user selection required');
    this.name = 'CompanionUserSelectionRequiredError';
    this.candidates = candidates;
  }
}

const BROKER_BUDGET_MS = 25_000;
const BROKER_POLL_CADENCE_MS = 1_000;

interface InternalCompanionCandidate extends CompanionUserCandidate {
  addresses: string[];
  accessToken: string;
  hasVerifiedToken: boolean;
  password?: string;
  musicAssistant?: AuthSession['musicAssistant'];
}

function sleepMs(ms: number): Promise<void> {
  return new Promise((resolve) => globalThis.setTimeout(resolve, ms));
}

async function authorizeAndMintViaBroker(
  normalizedCompanionUrl: string,
  setupToken: string,
  candidate: InternalCompanionCandidate,
  serverUrl: string,
): Promise<{ accessToken: string; userId: string; userName: string }> {
  const deadline = Date.now() + BROKER_BUDGET_MS;
  const remaining = () => Math.max(0, deadline - Date.now());

  // Step B1: POST /QuickConnect/Initiate on Jellyfin
  let initRes: Response;
  try {
    initRes = await fetchWithTimeout(
      serverEndpoint(serverUrl, '/QuickConnect/Initiate'),
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          Authorization: getAuthorizationHeader(null),
        },
      },
      Math.min(REQUEST_TIMEOUT_MS, Math.max(1000, remaining())),
    );
  } catch (err) {
    throw new JellyfinAuthError(networkErrorMessage(serverUrl, err));
  }

  if (initRes.status === 401 || initRes.status === 403) {
    throw new CompanionBrokerFallbackError('qc_disabled', serverUrl, candidate.username);
  }
  if (!initRes.ok) {
    throw new JellyfinAuthError(
      `Jellyfin Quick Connect initiation failed (${initRes.status}).`,
      initRes.status,
    );
  }
  const initJson = (await initRes.json()) as { Code?: string; Secret?: string };
  const code = String(initJson.Code ?? '').trim();
  const secret = String(initJson.Secret ?? '').trim();
  if (!code || !secret) {
    throw new CompanionBrokerFallbackError('qc_broker_rejected', serverUrl, candidate.username);
  }

  // Step B2: POST /api/v1/auth/authorize-quick-connect on Companion
  if (remaining() <= 0) {
    throw new JellyfinAuthError('Companion Quick Connect sign-in timed out.');
  }
  let authRes: Response;
  try {
    authRes = await fetchWithTimeout(
      `${normalizedCompanionUrl}/api/v1/auth/authorize-quick-connect`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Setup-Token': setupToken,
        },
        body: JSON.stringify({
          serverId: candidate.serverId,
          userId: candidate.userId,
          code,
        }),
      },
      Math.min(REQUEST_TIMEOUT_MS, Math.max(1000, remaining())),
    );
  } catch {
    throw new JellyfinAuthError(`Could not reach the Companion App at ${normalizedCompanionUrl}.`, 503);
  }

  if (!authRes.ok) {
    let errCode = '';
    try {
      const body = (await authRes.json()) as { error_code?: string };
      errCode = String(body.error_code ?? '');
    } catch {
      // ignore non-JSON body
    }
    if (authRes.status === 401) {
      throw new JellyfinAuthError('Companion App rejected the setup token.', 401);
    }
    if (authRes.status === 429) {
      throw new JellyfinAuthError('Companion App rate-limited the request. Try again shortly.', 429);
    }
    if (authRes.status === 503 || errCode === 'qc_broker_unreachable') {
      throw new JellyfinAuthError('Companion App could not reach Jellyfin to approve Quick Connect.', 503);
    }
    if (authRes.status === 409 && errCode === 'qc_disabled') {
      throw new CompanionBrokerFallbackError('qc_disabled', serverUrl, candidate.username);
    }
    if (authRes.status === 409 && errCode === 'no_verified_user') {
      throw new CompanionBrokerFallbackError('no_verified_user', serverUrl, candidate.username);
    }
    if (authRes.status === 409 || authRes.status === 404) {
      throw new CompanionBrokerFallbackError('qc_broker_rejected', serverUrl, candidate.username);
    }
    throw new JellyfinAuthError(
      `Companion Quick Connect broker returned HTTP ${authRes.status}.`,
      authRes.status,
    );
  }

  // Step B3: GET /QuickConnect/Connect?secret=... (0 ms initial poll, then 1 s cadence)
  let authenticated = false;
  let firstPoll = true;
  while (remaining() > 0) {
    if (!firstPoll) {
      await sleepMs(Math.min(BROKER_POLL_CADENCE_MS, remaining()));
      if (remaining() <= 0) break;
    }
    firstPoll = false;

    let pollRes: Response;
    try {
      const qs = new URLSearchParams({ secret }).toString();
      pollRes = await fetchWithTimeout(
        serverEndpoint(serverUrl, `/QuickConnect/Connect?${qs}`),
        {
          headers: {
            Accept: 'application/json',
            Authorization: getAuthorizationHeader(null),
          },
        },
        Math.min(REQUEST_TIMEOUT_MS, Math.max(1000, remaining())),
      );
    } catch {
      continue;
    }

    if (pollRes.status === 401 || pollRes.status === 403) {
      throw new CompanionBrokerFallbackError('qc_disabled', serverUrl, candidate.username);
    }
    if (pollRes.status === 404) {
      throw new CompanionBrokerFallbackError('qc_broker_rejected', serverUrl, candidate.username);
    }
    if (pollRes.ok) {
      const pollData = (await pollRes.json()) as { Authenticated?: boolean };
      if (pollData.Authenticated === true) {
        authenticated = true;
        break;
      }
    }
  }

  if (!authenticated) {
    throw new JellyfinAuthError('Quick Connect approval timed out.');
  }

  // Step B4: POST /Users/AuthenticateWithQuickConnect
  let exchangeRes: Response;
  try {
    exchangeRes = await fetchWithTimeout(
      serverEndpoint(serverUrl, '/Users/AuthenticateWithQuickConnect'),
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          Authorization: getAuthorizationHeader(null),
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ Secret: secret }),
      },
      Math.min(REQUEST_TIMEOUT_MS, Math.max(1500, remaining())),
    );
  } catch (err) {
    throw new JellyfinAuthError(networkErrorMessage(serverUrl, err));
  }

  if (!exchangeRes.ok) {
    throw new JellyfinAuthError(
      `Quick Connect sign-in failed (${exchangeRes.status}).`,
      exchangeRes.status,
    );
  }

  const authResult = (await exchangeRes.json()) as AuthenticationResult;
  if (!authResult.AccessToken || !authResult.User?.Id) {
    throw new JellyfinAuthError('Jellyfin returned an incomplete Quick Connect login response.');
  }
  if (normalizeUuid(authResult.User.Id) !== normalizeUuid(candidate.userId)) {
    throw new JellyfinAuthError('Authenticated Jellyfin user did not match the selected profile.', 401);
  }

  return {
    accessToken: authResult.AccessToken,
    userId: authResult.User.Id,
    userName: authResult.User.Name ?? candidate.username,
  };
}

export async function loginViaCompanionSetupToken(
  companionUrl: string,
  setupToken: string,
  selectCandidate?: (candidates: CompanionUserCandidate[]) => Promise<CompanionUserCandidate | null>,
): Promise<AuthSession> {
  const normalizedCompanionUrl = normalizeCompanionUrl(companionUrl);
  const trimmedToken = setupToken.trim();
  if (!trimmedToken) {
    throw new JellyfinAuthError('Enter your Companion App setup token.', 401);
  }

  let response: Response;
  try {
    response = await fetchWithTimeout(`${normalizedCompanionUrl}/api/v1/config`, {
      headers: {
        'X-Setup-Token': trimmedToken,
        'X-Companion-Device': getDeviceId(),
        'X-SpatialFin-Capabilities': 'qc_broker_v1, webxr_client_v1',
      },
    });
  } catch {
    throw new JellyfinAuthError(`Could not reach the Companion App at ${normalizedCompanionUrl}.`);
  }

  if (!response.ok) {
    if (response.status === 401 || response.status === 403) {
      throw new JellyfinAuthError('Companion App rejected the setup token.', response.status);
    }
    throw new JellyfinAuthError('Companion App failed to return configuration.', response.status);
  }

  const config = await response.json();
  const candidates: InternalCompanionCandidate[] = [];

  for (const s of config.servers || []) {
    const rawAddresses: string[] = Array.isArray(s.addresses) ? s.addresses : [];
    const normalizedAddresses: string[] = [];
    for (const addr of rawAddresses) {
      if (typeof addr !== 'string' || !addr.trim()) continue;
      try {
        normalizedAddresses.push(normalizeServerUrl(addr));
      } catch {
        // Ignore malformed server addresses and continue probing remaining entries
      }
    }
    if (normalizedAddresses.length === 0) continue;

    const serverId = String(s.id ?? '').trim();
    const serverName = String(s.name ?? serverId ?? normalizedAddresses[0]).trim();

    for (const u of s.users || []) {
      const accessToken = typeof u.access_token === 'string' ? u.access_token.trim() : '';
      const hasVerifiedToken = u.has_verified_token === true || Boolean(accessToken);
      const userId = String(u.id ?? '').trim();
      const username = String(u.username || u.name || '').trim();
      const password = typeof u.password === 'string' ? u.password : '';
      if (!hasVerifiedToken && !(username && password)) continue;
      candidates.push({
        serverId,
        serverName,
        serverUrl: normalizedAddresses[0],
        userId,
        username: username || userId,
        addresses: normalizedAddresses,
        accessToken,
        hasVerifiedToken,
        password,
        musicAssistant: sanitizeMusicAssistant(u.musicAssistant),
      });
    }
  }

  if (candidates.length === 0) {
    throw new JellyfinAuthError('Companion App returned no verified users.');
  }

  let selected: InternalCompanionCandidate;
  if (candidates.length === 1) {
    selected = candidates[0];
  } else {
    const publicCandidates: CompanionUserCandidate[] = candidates.map((c) => ({
      serverId: c.serverId,
      serverName: c.serverName,
      serverUrl: c.serverUrl,
      userId: c.userId,
      username: c.username,
    }));
    if (!selectCandidate) {
      throw new CompanionUserSelectionRequiredError(publicCandidates);
    }
    const chosen = await selectCandidate(publicCandidates);
    if (!chosen) {
      throw new CompanionUserSelectionCancelledError();
    }
    const match = candidates.find(
      (c) =>
        normalizeUuid(c.serverId) === normalizeUuid(chosen.serverId) &&
        normalizeUuid(c.userId) === normalizeUuid(chosen.userId),
    );
    if (!match) {
      throw new CompanionUserSelectionCancelledError();
    }
    selected = match;
  }

  let lastError: Error | null = null;
  for (const serverUrl of selected.addresses) {
    try {
      if (selected.accessToken) {
        // Stage 1 / pre-Stage-1 Companion fallback: adopt shared token with isDedicatedToken=false
        const { id: realUserId, name: realUserName } = await resolveJellyfinUser(
          serverUrl,
          selected.accessToken,
        );
        return {
          accessToken: selected.accessToken,
          serverUrl,
          userId: realUserId,
          userName: realUserName,
          companionUrl: normalizedCompanionUrl,
          setupToken: trimmedToken,
          isDedicatedToken: false,
          musicAssistant: selected.musicAssistant,
        };
      }

      if (selected.hasVerifiedToken && selected.userId) {
        // Stage 2 qc_broker_v1: mint per-browser token via Companion Quick Connect broker
        const authInfo = await authorizeAndMintViaBroker(
          normalizedCompanionUrl,
          trimmedToken,
          selected,
          serverUrl,
        );
        return {
          accessToken: authInfo.accessToken,
          serverUrl,
          userId: authInfo.userId,
          userName: authInfo.userName,
          companionUrl: normalizedCompanionUrl,
          setupToken: trimmedToken,
          isDedicatedToken: true,
          musicAssistant: selected.musicAssistant,
        };
      }

      if (selected.username && selected.password) {
        const authInfo = await authenticateJellyfinUser(serverUrl, selected.username, selected.password);
        return {
          accessToken: authInfo.accessToken,
          serverUrl,
          userId: authInfo.userId,
          userName: authInfo.userName,
          companionUrl: normalizedCompanionUrl,
          setupToken: trimmedToken,
          isDedicatedToken: true,
          musicAssistant: selected.musicAssistant,
        };
      }
    } catch (err) {
      if (
        err instanceof CompanionBrokerFallbackError ||
        (err instanceof JellyfinAuthError && (err.status === 401 || err.status === 429 || err.status === 503))
      ) {
        throw err;
      }
      lastError = err instanceof Error ? err : new Error(String(err));
    }
  }

  throw lastError || new JellyfinAuthError('Could not connect to the selected Jellyfin server.');
}

export async function switchCompanionUser(
  previousSession: AuthSession,
  companionUrl: string,
  setupToken: string,
  selectCandidate: (candidates: CompanionUserCandidate[]) => Promise<CompanionUserCandidate | null>,
  remember?: boolean,
): Promise<AuthSession> {
  const effectiveRemember = remember ?? (localStorage.getItem(SESSION_KEY) !== null);
  const nextSession = await loginViaCompanionSetupToken(companionUrl, setupToken, selectCandidate);

  // Only after nextSession is minted and validated, clear stale Seerr/MA state and commit
  clearPreferences();
  MusicAssistantManager.clearConfig();
  if (nextSession.musicAssistant?.url) {
    MusicAssistantManager.getInstance().saveConfig({
      serverUrl: nextSession.musicAssistant.url,
      token: nextSession.musicAssistant.token || '',
    });
  }
  saveSession(nextSession, effectiveRemember);

  const sameToken = previousSession.accessToken === nextSession.accessToken;
  const sameServerAndUser =
    normalizeServerUrl(previousSession.serverUrl) === normalizeServerUrl(nextSession.serverUrl) &&
    normalizeUuid(previousSession.userId) === normalizeUuid(nextSession.userId);

  if (previousSession.isDedicatedToken === true && !sameToken && !sameServerAndUser) {
    await fetchWithTimeout(
      serverEndpoint(previousSession.serverUrl, '/Sessions/Logout'),
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          Authorization: getAuthorizationHeader(previousSession.accessToken),
        },
        keepalive: true,
      },
      1_000,
    ).catch(() => undefined);
  }

  return nextSession;
}

export function saveSession(session: AuthSession, remember: boolean) {
  sessionStorage.removeItem(SESSION_KEY);
  localStorage.removeItem(SESSION_KEY);
  getStorage(remember).setItem(SESSION_KEY, JSON.stringify(session));
  clearLegacySession();
}

export async function validateSession(): Promise<void> {
  const session = getSession();
  if (!session) throw new JellyfinAuthError('No saved Jellyfin session was found.', 401);
  assertServerCanBeRequested(session.serverUrl);

  let response: Response;
  try {
    response = await fetchWithTimeout(serverEndpoint(session.serverUrl, '/Users/Me'), {
      headers: getAuthHeaders(),
    });
  } catch (error) {
    throw new JellyfinAuthError(networkErrorMessage(session.serverUrl, error));
  }

  if (response.status === 401) {
    clearSession();
    throw new JellyfinAuthError('Your saved Jellyfin session has expired. Sign in again.', 401);
  }
  if (!response.ok) {
    if (response.status === 403) {
      throw new JellyfinAuthError(
        'The saved Jellyfin session is not allowed to access this server.',
        403,
      );
    }
    if (response.status === 404) {
      throw new JellyfinAuthError(
        'The saved Jellyfin address no longer points to its API. Sign in again with the server base URL.',
        404,
      );
    }
    throw new JellyfinAuthError(
      `Jellyfin returned ${response.status} while restoring the session.`,
      response.status,
    );
  }
}

export function clearSession() {
  sessionStorage.removeItem(SESSION_KEY);
  localStorage.removeItem(SESSION_KEY);
  clearLegacySession();
}

export async function logout(reload = true): Promise<void> {
  const session = getSession();
  const headers = session ? getAuthHeaders() : undefined;
  clearSession();
  MusicAssistantManager.clearConfig();
  clearPreferences();

  let remoteLogout: Promise<void> | null = null;
  if (session && headers && session.isDedicatedToken === true) {
    remoteLogout = fetchWithTimeout(
      serverEndpoint(session.serverUrl, '/Sessions/Logout'),
      {method: 'POST', headers, keepalive: true},
      LOGOUT_TIMEOUT_MS,
    )
      .then(() => undefined)
      .catch(() => undefined);
  }

  if (reload && typeof window !== 'undefined') {
    // The keepalive request is best effort; local sign-out and navigation must
    // never wait for a slow or offline server.
    void remoteLogout;
    window.location.reload();
    return;
  }

  await remoteLogout;
}
