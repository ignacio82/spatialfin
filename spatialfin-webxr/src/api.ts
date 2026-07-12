import {
  getAuthHeaders,
  getDeviceId,
  getServerUrl,
  getUserId,
} from './auth';

const REQUEST_TIMEOUT_MS = 15_000;
const MAX_STREAMING_BITRATE = 120_000_000;
const HOME_ITEM_FIELDS = [
  'Overview',
  'Genres',
  'PrimaryImageAspectRatio',
  'CommunityRating',
  'CriticRating',
  'OfficialRating',
  'ProductionYear',
  'RunTimeTicks',
  'MediaSources',
  'People',
].join(',');
const HOME_IMAGE_TYPES = 'Primary,Backdrop,Logo';

type QueryParameters = Record<
  string,
  string | number | boolean | null | undefined
>;

interface RequestOptions {
  query?: QueryParameters;
  method?: 'GET' | 'POST';
  json?: unknown;
  accept?: string;
  signal?: AbortSignal;
}

export interface JellyfinView {
  Id: string;
  Name: string;
  CollectionType?: string;
}

export type JellyfinImageType = 'Primary' | 'Backdrop' | 'Logo';

export interface JellyfinUserData {
  Rating?: number;
  PlayedPercentage?: number;
  UnplayedItemCount?: number;
  PlaybackPositionTicks?: number;
  PlayCount?: number;
  IsFavorite?: boolean;
  Likes?: boolean;
  LastPlayedDate?: string;
  Played?: boolean;
}

export interface JellyfinPerson {
  Name?: string;
  Role?: string;
  Type?: string;
  PrimaryImageTag?: string;
  Id?: string;
}

export interface JellyfinMediaStream {
  Type?: 'Audio' | 'Video' | 'Subtitle';
  Codec?: string;
  DisplayTitle?: string;
  Language?: string;
  IsDefault?: boolean;
  Profile?: string;
  Width?: number;
  Height?: number;
  ChannelLayout?: string;
}

export interface JellyfinItem {
  Id: string;
  Name: string;
  Type?: string;
  Overview?: string;
  Genres?: string[];
  CommunityRating?: number;
  CriticRating?: number;
  OfficialRating?: string;
  ProductionYear?: number;
  RunTimeTicks?: number;
  PrimaryImageAspectRatio?: number;
  ParentIndexNumber?: number;
  IndexNumber?: number;
  SeriesName?: string;
  SeasonName?: string;
  UserData?: JellyfinUserData;
  ImageTags?: {
    Primary?: string;
    Logo?: string;
  };
  BackdropImageTags?: string[];
  People?: JellyfinPerson[];
  MediaSources?: JellyfinMediaSourceInfo[];
}

export interface JellyfinMediaSourceInfo {
  Id?: string | null;
  SupportsTranscoding?: boolean;
  SupportsDirectStream?: boolean;
  TranscodingUrl?: string | null;
  RequiredHttpHeaders?: Record<string, string | null> | null;
  DefaultAudioStreamIndex?: number | null;
  VideoCodec?: string;
  MediaStreams?: JellyfinMediaStream[];
}

export interface JellyfinPlaybackInfoResponse {
  MediaSources?: JellyfinMediaSourceInfo[];
  PlaySessionId?: string | null;
  ErrorCode?: 'NotAllowed' | 'NoCompatibleStream' | 'RateLimitExceeded' | null;
}

/** A browser-ready HLS stream negotiated through Jellyfin PlaybackInfo. */
export interface JellyfinPlaybackInfo {
  streamUrl: string;
  mediaSourceId?: string;
  playSessionId?: string;
  requiredHeaders: Record<string, string>;
}

interface QueryResult<T> {
  Items?: T[];
}

type ItemResponse = QueryResult<JellyfinItem> | JellyfinItem[];

function normalizeLimit(value: number, fallback: number, maximum = 100): number {
  if (!Number.isFinite(value)) return fallback;
  return Math.max(1, Math.min(Math.trunc(value), maximum));
}

function validItems(items: JellyfinItem[] | undefined): JellyfinItem[] {
  return (items ?? []).filter((item) => item.Id && item.Name);
}

function itemsFromResponse(response: ItemResponse): JellyfinItem[] {
  return validItems(Array.isArray(response) ? response : response.Items);
}

export class JellyfinApiError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = 'JellyfinApiError';
    this.status = status;
  }

  get isUnauthorized(): boolean {
    return this.status === 401;
  }
}

function requireUserId(): string {
  const userId = getUserId();
  if (!userId) throw new JellyfinApiError('No Jellyfin user is configured.', 401);
  return userId;
}

function buildUrl(path: string, parameters: QueryParameters = {}): URL {
  const server = getServerUrl();
  if (!server) throw new JellyfinApiError('No Jellyfin server is configured.', 401);

  // String concatenation intentionally preserves a configured Jellyfin base
  // path. `new URL('/Items', server)` would discard `/jellyfin`, for example.
  const url = new URL(`${server}${path.startsWith('/') ? path : `/${path}`}`);
  for (const [name, value] of Object.entries(parameters)) {
    if (value !== undefined && value !== null) {
      url.searchParams.set(name, String(value));
    }
  }
  return url;
}

function resolveServerPath(path: string): string {
  if (/^https?:\/\//i.test(path)) return path;

  const server = getServerUrl();
  if (!server) throw new JellyfinApiError('No Jellyfin server is configured.', 401);

  const serverUrl = new URL(server);
  const basePath = serverUrl.pathname.replace(/\/+$/, '');
  const relativePath = path.startsWith('/') ? path : `/${path}`;

  // Newer servers can return either an API-root-relative path or a path that
  // already contains the configured base path. Avoid duplicating that prefix.
  if (basePath && (relativePath === basePath || relativePath.startsWith(`${basePath}/`))) {
    return new URL(relativePath, serverUrl.origin).toString();
  }
  return new URL(`${basePath}${relativePath}`, serverUrl.origin).toString();
}

async function errorDetail(response: Response): Promise<string | null> {
  try {
    const contentType = response.headers.get('content-type') ?? '';
    if (contentType.includes('json')) {
      const value = (await response.json()) as {
        detail?: unknown;
        title?: unknown;
        Message?: unknown;
      };
      const detail = value.detail ?? value.Message ?? value.title;
      return typeof detail === 'string' && detail.trim()
        ? detail.trim().slice(0, 240)
        : null;
    }

    const text = (await response.text()).trim();
    return text && !text.startsWith('<') ? text.slice(0, 240) : null;
  } catch {
    return null;
  }
}

async function request(path: string, options: RequestOptions = {}): Promise<Response> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  options.signal?.addEventListener('abort', abort, {once: true});
  const timeout = window.setTimeout(abort, REQUEST_TIMEOUT_MS);
  const hasJsonBody = options.json !== undefined;

  try {
    const response = await fetch(buildUrl(path, options.query), {
      method: options.method ?? 'GET',
      headers: getAuthHeaders({
        accept: options.accept ?? 'application/json',
        contentType: hasJsonBody ? 'application/json' : null,
      }),
      body: hasJsonBody ? JSON.stringify(options.json) : undefined,
      signal: controller.signal,
    });
    if (!response.ok) {
      const detail = await errorDetail(response);
      const message =
        response.status === 401
          ? 'Your Jellyfin session has expired.'
          : response.status === 403
            ? 'This Jellyfin user is not allowed to access that content.'
            : `Jellyfin returned ${response.status} for ${path}${detail ? `: ${detail}` : '.'}`;
      throw new JellyfinApiError(message, response.status);
    }
    return response;
  } catch (error) {
    if (error instanceof JellyfinApiError) throw error;
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new JellyfinApiError(
        options.signal?.aborted
          ? 'The Jellyfin request was cancelled.'
          : 'The Jellyfin request timed out.',
      );
    }
    throw new JellyfinApiError(
      'The Jellyfin request failed. Check the server connection, TLS certificate, and CORS settings.',
    );
  } finally {
    options.signal?.removeEventListener('abort', abort);
    window.clearTimeout(timeout);
  }
}

async function requestJson<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await request(path, options);
  try {
    return (await response.json()) as T;
  } catch {
    throw new JellyfinApiError(`Jellyfin returned invalid JSON for ${path}.`);
  }
}

export async function fetchViews(): Promise<JellyfinView[]> {
  const data = await requestJson<QueryResult<JellyfinView>>('/UserViews', {
    query: {
      userId: requireUserId(),
      includeExternalContent: false,
    },
  });
  const supportedCollections = new Set([
    'movies',
    'tvshows',
    'mixed',
    'boxsets',
    'homevideos',
    'musicvideos',
    'playlists',
    'folders',
  ]);
  return (data.Items ?? []).filter(
    (view) =>
      view.Id &&
      view.Name &&
      (!view.CollectionType || supportedCollections.has(view.CollectionType.toLowerCase())),
  );
}

export async function fetchItems(parentId: string): Promise<JellyfinItem[]> {
  const data = await requestJson<QueryResult<JellyfinItem>>('/Items', {
    query: {
      userId: requireUserId(),
      parentId,
      recursive: true,
      includeItemTypes: 'Movie,Series,Video',
      fields: HOME_ITEM_FIELDS,
      enableImages: true,
      enableImageTypes: HOME_IMAGE_TYPES,
      imageTypeLimit: 1,
      enableUserData: true,
      sortBy: 'SortName',
      sortOrder: 'Ascending',
      limit: 24,
    },
  });
  return validItems(data.Items);
}

export async function fetchSuggestions(
  limit = 6,
  signal?: AbortSignal,
): Promise<JellyfinItem[]> {
  const data = await requestJson<QueryResult<JellyfinItem>>('/Items/Suggestions', {
    query: {
      userId: requireUserId(),
      type: 'Movie,Series',
      limit: normalizeLimit(limit, 6),
      fields: HOME_ITEM_FIELDS,
      enableImages: true,
      enableImageTypes: HOME_IMAGE_TYPES,
      imageTypeLimit: 1,
      enableUserData: true,
      enableTotalRecordCount: false,
    },
    signal,
  });
  return validItems(data.Items);
}

export async function fetchResumeItems(
  limit = 12,
  signal?: AbortSignal,
): Promise<JellyfinItem[]> {
  const data = await requestJson<QueryResult<JellyfinItem>>('/UserItems/Resume', {
    query: {
      userId: requireUserId(),
      limit: normalizeLimit(limit, 12),
      fields: HOME_ITEM_FIELDS,
      mediaTypes: 'Video',
      enableImages: true,
      enableImageTypes: HOME_IMAGE_TYPES,
      imageTypeLimit: 1,
      enableUserData: true,
      includeItemTypes: 'Movie,Episode',
      enableTotalRecordCount: false,
    },
    signal,
  });
  return validItems(data.Items);
}

export async function fetchNextUp(
  limit = 24,
  seriesId?: string,
  signal?: AbortSignal,
): Promise<JellyfinItem[]> {
  const data = await requestJson<QueryResult<JellyfinItem>>('/Shows/NextUp', {
    query: {
      userId: requireUserId(),
      limit: normalizeLimit(limit, 24),
      fields: HOME_ITEM_FIELDS,
      seriesId,
      enableImages: true,
      enableImageTypes: HOME_IMAGE_TYPES,
      imageTypeLimit: 1,
      enableUserData: true,
      enableTotalRecordCount: false,
      enableResumable: false,
    },
    signal,
  });
  return validItems(data.Items);
}

export async function fetchLatestMedia(
  parentId: string,
  limit = 16,
  signal?: AbortSignal,
): Promise<JellyfinItem[]> {
  const data = await requestJson<ItemResponse>('/Items/Latest', {
    query: {
      userId: requireUserId(),
      parentId,
      fields: HOME_ITEM_FIELDS,
      enableImages: true,
      enableImageTypes: HOME_IMAGE_TYPES,
      imageTypeLimit: 1,
      enableUserData: true,
      limit: normalizeLimit(limit, 16),
    },
    signal,
  });
  return itemsFromResponse(data);
}

export async function fetchEpisodes(
  seriesId: string,
  limit = 24,
): Promise<JellyfinItem[]> {
  const data = await requestJson<QueryResult<JellyfinItem>>(
    `/Shows/${encodeURIComponent(seriesId)}/Episodes`,
    {
      query: {
        userId: requireUserId(),
        fields: HOME_ITEM_FIELDS,
        enableImages: true,
        enableImageTypes: HOME_IMAGE_TYPES,
        imageTypeLimit: 1,
        enableUserData: true,
        sortBy: 'ParentIndexNumber,IndexNumber',
        limit: normalizeLimit(limit, 24),
      },
    },
  );
  return validItems(data.Items);
}

const IMAGE_DIMENSIONS: Record<
  JellyfinImageType,
  {maxWidth: number; maxHeight: number}
> = {
  Primary: {maxWidth: 640, maxHeight: 960},
  Backdrop: {maxWidth: 1280, maxHeight: 720},
  Logo: {maxWidth: 960, maxHeight: 480},
};

function imageTag(item: JellyfinItem, imageType: JellyfinImageType): string | undefined {
  if (imageType === 'Backdrop') return item.BackdropImageTags?.[0];
  return item.ImageTags?.[imageType];
}

export async function fetchItemImage(
  item: JellyfinItem,
  imageType: JellyfinImageType,
  signal?: AbortSignal,
): Promise<Blob | null> {
  const tag = imageTag(item, imageType);
  if (!tag) return null;

  const dimensions = IMAGE_DIMENSIONS[imageType];
  const indexedSuffix = imageType === 'Backdrop' ? '/0' : '';

  try {
    const response = await request(
      `/Items/${encodeURIComponent(item.Id)}/Images/${imageType}${indexedSuffix}`,
      {
        query: {
          tag,
          maxWidth: dimensions.maxWidth,
          maxHeight: dimensions.maxHeight,
          quality: 90,
        },
        accept: 'image/avif,image/webp,image/*,*/*;q=0.8',
        signal,
      },
    );
    return await response.blob();
  } catch (error) {
    if (error instanceof JellyfinApiError && error.status === 404) return null;
    throw error;
  }
}

export function fetchPrimaryImage(
  item: JellyfinItem,
  signal?: AbortSignal,
): Promise<Blob | null> {
  return fetchItemImage(item, 'Primary', signal);
}

/**
 * Resolve a media source's negotiated URL, with a Jellyfin-compatible HLS
 * fallback for older servers that omit `TranscodingUrl` from PlaybackInfo.
 */
export function buildPlaybackStreamUrl(
  itemId: string,
  source: JellyfinMediaSourceInfo,
  playSessionId?: string,
): string {
  if (source.TranscodingUrl?.trim()) {
    return resolveServerPath(source.TranscodingUrl.trim());
  }

  const mediaSourceId = source.Id?.trim();
  if (!mediaSourceId) {
    throw new JellyfinApiError(
      'Jellyfin did not provide a compatible HLS media source for this item.',
    );
  }

  return buildUrl(`/Videos/${encodeURIComponent(itemId)}/master.m3u8`, {
    UserId: requireUserId(),
    DeviceId: getDeviceId(),
    MediaSourceId: mediaSourceId,
    PlaySessionId: playSessionId,
    AudioStreamIndex: source.DefaultAudioStreamIndex,
    VideoCodec: 'h264',
    AudioCodec: 'aac',
    MaxAudioChannels: 2,
    TranscodingContainer: 'ts',
    SegmentContainer: 'ts',
    AllowVideoStreamCopy: true,
    AllowAudioStreamCopy: true,
    EnableSubtitlesInManifest: true,
  }).toString();
}

export async function fetchPlaybackInfo(
  itemId: string,
  signal?: AbortSignal,
): Promise<JellyfinPlaybackInfo> {
  const userId = requireUserId();
  const response = await requestJson<JellyfinPlaybackInfoResponse>(
    `/Items/${encodeURIComponent(itemId)}/PlaybackInfo`,
    {
      method: 'POST',
      signal,
      json: {
        UserId: userId,
        MaxStreamingBitrate: MAX_STREAMING_BITRATE,
        MaxAudioChannels: 2,
        EnableDirectPlay: false,
        EnableDirectStream: true,
        EnableTranscoding: true,
        AllowVideoStreamCopy: true,
        AllowAudioStreamCopy: true,
        DeviceProfile: {
          Name: 'SpatialFin WebXR HLS',
          MaxStreamingBitrate: MAX_STREAMING_BITRATE,
          MaxStaticBitrate: MAX_STREAMING_BITRATE,
          DirectPlayProfiles: [],
          TranscodingProfiles: [
            {
              Container: 'ts',
              Type: 'Video',
              VideoCodec: 'h264',
              AudioCodec: 'aac',
              Protocol: 'hls',
              EstimateContentLength: false,
              EnableMpegtsM2TsMode: false,
              TranscodeSeekInfo: 'Auto',
              CopyTimestamps: false,
              Context: 'Streaming',
              EnableSubtitlesInManifest: true,
              MaxAudioChannels: '2',
              MinSegments: 1,
              SegmentLength: 0,
              BreakOnNonKeyFrames: false,
              Conditions: [],
              EnableAudioVbrEncoding: true,
            },
          ],
          CodecProfiles: [],
          ContainerProfiles: [],
          SubtitleProfiles: [],
        },
      },
    },
  );

  if (response.ErrorCode) {
    const messages: Record<NonNullable<JellyfinPlaybackInfoResponse['ErrorCode']>, string> = {
      NotAllowed: 'This Jellyfin user is not allowed to play that item.',
      NoCompatibleStream: 'Jellyfin could not create a browser-compatible stream.',
      RateLimitExceeded: 'Jellyfin is currently handling too many playback requests.',
    };
    throw new JellyfinApiError(messages[response.ErrorCode]);
  }

  const sources = response.MediaSources ?? [];
  const source =
    sources.find((candidate) => Boolean(candidate.TranscodingUrl?.trim())) ??
    sources.find((candidate) => candidate.SupportsTranscoding && Boolean(candidate.Id)) ??
    sources.find((candidate) => Boolean(candidate.Id));
  if (!source) {
    throw new JellyfinApiError(
      'Jellyfin did not return a playable media source for this item.',
    );
  }

  const headers = new Headers(getAuthHeaders({accept: '*/*'}));
  for (const [name, value] of Object.entries(source.RequiredHttpHeaders ?? {})) {
    if (value) headers.set(name, value);
  }

  const playSessionId = response.PlaySessionId?.trim() || undefined;
  return {
    streamUrl: buildPlaybackStreamUrl(itemId, source, playSessionId),
    mediaSourceId: source.Id?.trim() || undefined,
    playSessionId,
    requiredHeaders: Object.fromEntries(headers.entries()),
  };
}
