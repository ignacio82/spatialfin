const SESSION_KEY = 'spatialfin_session_v1';
const DEVICE_ID_KEY = 'spatialfin_device_id';

const LEGACY_TOKEN_KEY = 'spatialfin_token';
const LEGACY_SERVER_KEY = 'spatialfin_server';
const LEGACY_USER_ID_KEY = 'spatialfin_userid';
const LEGACY_GEMINI_KEY = 'spatialfin_gemini_key';

const CLIENT_NAME = 'SpatialFin WebXR';
const CLIENT_VERSION = '0.1.0';
const REQUEST_TIMEOUT_MS = 15_000;
const LOGOUT_TIMEOUT_MS = 2_000;

export interface AuthSession {
  accessToken: string;
  serverUrl: string;
  userId: string;
  userName: string;
  geminiKey?: string;
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

export function getGeminiKey(): string | null {
  return getSession()?.geminiKey ?? null;
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

export function normalizeServerUrl(value: string): string {
  let input = value.trim();
  if (!input) throw new JellyfinAuthError('Enter your Jellyfin server address.');

  if (input.startsWith('/')) {
    input = new URL(input, window.location.origin).toString();
  } else if (!/^[a-z][a-z\d+.-]*:\/\//i.test(input)) {
    // A scheme-less address should inherit the page's security level. Inferring
    // HTTP from an HTTPS app creates a request that browsers must block.
    const inferredProtocol = window.location.protocol === 'http:' ? 'http:' : 'https:';
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
  if (window.location.protocol === 'https:' && url.protocol === 'http:') {
    throw new JellyfinAuthError(
      'This WebXR page uses HTTPS, so the browser will block an HTTP Jellyfin server. Use an HTTPS Jellyfin URL or a same-origin reverse proxy.',
    );
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
  if (window.location.protocol === 'https:' && url.protocol === 'http:') {
    throw new JellyfinAuthError(
      'This WebXR page uses HTTPS, so the saved HTTP Jellyfin server is blocked. Sign in with an HTTPS URL or a same-origin reverse proxy.',
      400,
    );
  }
}

async function fetchWithTimeout(
  input: RequestInfo | URL,
  init: RequestInit = {},
  timeoutMs = REQUEST_TIMEOUT_MS,
): Promise<Response> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs);

  try {
    return await fetch(input, {...init, signal: controller.signal});
  } finally {
    window.clearTimeout(timeout);
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
  return `Could not reach ${serverUrl}. Check the address, TLS certificate, and Jellyfin CORS settings.`;
}

export async function login(
  serverInput: string,
  username: string,
  password: string,
  geminiKey = '',
  remember = true,
): Promise<AuthSession> {
  const serverUrl = normalizeServerUrl(serverInput);
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

  const session: AuthSession = {
    accessToken: result.AccessToken,
    serverUrl,
    userId: result.User.Id,
    userName: result.User.Name ?? normalizedUsername,
    geminiKey: geminiKey.trim() || undefined,
  };

  sessionStorage.removeItem(SESSION_KEY);
  localStorage.removeItem(SESSION_KEY);
  getStorage(remember).setItem(SESSION_KEY, JSON.stringify(session));
  clearLegacySession();
  return session;
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

  let remoteLogout: Promise<void> | null = null;
  if (session && headers) {
    remoteLogout = fetchWithTimeout(
      serverEndpoint(session.serverUrl, '/Sessions/Logout'),
      {method: 'POST', headers, keepalive: true},
      LOGOUT_TIMEOUT_MS,
    )
      .then(() => undefined)
      .catch(() => undefined);
  }

  if (reload) {
    // The keepalive request is best effort; local sign-out and navigation must
    // never wait for a slow or offline server.
    void remoteLogout;
    window.location.reload();
    return;
  }

  await remoteLogout;
}
