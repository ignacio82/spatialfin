export type LocalNetworkAddressSpace = 'local' | 'loopback';

type LocalNetworkRequestInit = RequestInit & {
  targetAddressSpace?: LocalNetworkAddressSpace;
};

function normalizedHostname(hostname: string): string {
  return hostname
    .trim()
    .toLowerCase()
    .replace(/^\[|\]$/g, '')
    .replace(/\.+$/, '');
}

/**
 * IP literals, localhost, and .local names are classified by the browser before
 * mixed-content checks. Avoid overriding that classification: the LNA address
 * table includes special-use ranges beyond RFC1918 and differs for loopback.
 */
export function browserClassifiesAddressSpace(hostname: string): boolean {
  const host = normalizedHostname(hostname);
  if (!host) return false;
  const ipv4 = host.split('.');
  const isIpv4 = ipv4.length === 4 && ipv4.every((part) => /^\d{1,3}$/.test(part));
  return (
    isIpv4 ||
    host.includes(':') ||
    host === 'localhost' ||
    host.endsWith('.localhost') ||
    host.endsWith('.local')
  );
}

function requestUrl(input: RequestInfo | URL): URL | null {
  try {
    const value = input instanceof Request ? input.url : input.toString();
    return new URL(value, window.location.href);
  } catch {
    return null;
  }
}

export function localNetworkTargetForRequest(
  input: string | URL,
  secureContext: boolean,
): LocalNetworkAddressSpace | null {
  let url: URL;
  try {
    url = input instanceof URL ? input : new URL(input);
  } catch {
    return null;
  }
  if (!secureContext || url.protocol !== 'http:') return null;
  // Chrome already handles IP literals, localhost, and .local. An explicit
  // target is needed for names such as jellyfin.home.arpa that resolve locally.
  return browserClassifiesAddressSpace(url.hostname) ? null : 'local';
}

/**
 * Ask supporting browsers to treat HTTPS-page -> HTTP-server requests as Local
 * Network Access. Unknown RequestInit members are ignored by older browsers,
 * which then retain their normal mixed-content behavior.
 */
export function createJellyfinRequest(
  input: RequestInfo | URL,
  init: RequestInit = {},
  secureContext = window.isSecureContext,
): Request {
  const url = requestUrl(input);
  const targetAddressSpace = url
    ? localNetworkTargetForRequest(url, secureContext)
    : null;
  if (!targetAddressSpace) {
    return new Request(input, init);
  }

  const localInit: LocalNetworkRequestInit = {...init, targetAddressSpace};
  return new Request(input, localInit);
}

export function fetchJellyfin(
  input: RequestInfo | URL,
  init: RequestInit = {},
): Promise<Response> {
  return fetch(createJellyfinRequest(input, init));
}

export function streamingFetchSupported(): boolean {
  if (
    typeof fetch !== 'function' ||
    typeof AbortController === 'undefined' ||
    typeof ReadableStream === 'undefined' ||
    typeof Request === 'undefined'
  ) return false;
  try {
    new ReadableStream({});
    return true;
  } catch {
    return false;
  }
}
