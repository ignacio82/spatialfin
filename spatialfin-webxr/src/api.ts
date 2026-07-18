import {
  getAuthHeaders,
  getDeviceId,
  getServerUrl,
  getUserId,
  getAccessToken,
} from './auth';
import {detectClientCapabilities} from './MediaCapabilities';
import {fetchJellyfin} from './network';

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
  'SeriesId',
  'Chapters',
  'MediaSources',
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

let companionUrl = '';
export let seerrEnabled = false;
export let seerrUrl = '';
export let seerrApiKey = '';

export interface JellyfinView {
  Id: string;
  Name: string;
  CollectionType?: string;
  ImageTags?: Record<string, string>;
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

export interface JellyfinChapter {
  StartPositionTicks: number;
  Name?: string;
  ImageTag?: string;
}

export interface JellyfinMediaStream {
  Type?: 'Audio' | 'Video' | 'Subtitle';
  Codec?: string;
  Title?: string;
  DisplayTitle?: string;
  Language?: string;
  Index?: number;
  IsDefault?: boolean;
  IsForced?: boolean;
  IsHearingImpaired?: boolean;
  IsExternal?: boolean;
  IsTextSubtitleStream?: boolean;
  SupportsExternalStream?: boolean;
  DeliveryMethod?: 'Embed' | 'External' | 'Encode' | 'Drop' | string;
  DeliveryUrl?: string | null;
  IsExternalUrl?: boolean | null;
  Profile?: string;
  Width?: number;
  Height?: number;
  ChannelLayout?: string;
}

export interface JellyfinMediaAttachment {
  Codec?: string;
  Index?: number;
  FileName?: string;
  MimeType?: string;
  DeliveryUrl?: string | null;
}

export interface JellyfinItem {
  Id: string;
  Name: string;
  OriginalTitle?: string;
  Type?: string;
  Overview?: string;
  Genres?: string[];
  CommunityRating?: number;
  CriticRating?: number;
  OfficialRating?: string;
  ProductionYear?: number;
  PremiereDate?: string;
  RunTimeTicks?: number;
  Video3DFormat?: string;
  PrimaryImageAspectRatio?: number;
  ParentIndexNumber?: number;
  IndexNumber?: number;
  SeriesName?: string;
  SeriesId?: string;
  SeasonName?: string;
  UserData?: JellyfinUserData;
  ImageTags?: {
    Primary?: string;
    Logo?: string;
  };
  BackdropImageTags?: string[];
  People?: JellyfinPerson[];
  Chapters?: JellyfinChapter[];
  MediaSources?: JellyfinMediaSourceInfo[];
}

export interface JellyfinMediaSourceInfo {
  Id?: string | null;
  Name?: string;
  Path?: string;
  SupportsTranscoding?: boolean;
  SupportsDirectStream?: boolean;
  TranscodingUrl?: string | null;
  RequiredHttpHeaders?: Record<string, string | null> | null;
  DefaultAudioStreamIndex?: number | null;
  VideoCodec?: string;
  MediaStreams?: JellyfinMediaStream[];
  MediaAttachments?: JellyfinMediaAttachment[];
}

export interface SeerrResult {
  id: number;
  mediaType: 'movie' | 'tv';
  title?: string;
  name?: string;
  overview?: string;
  posterPath?: string;
  backdropPath?: string;
  releaseDate?: string;
  firstAirDate?: string;
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
  subtitleTracks: JellyfinSubtitleTrack[];
  fontUrls: string[];
  audioStreams: JellyfinMediaStream[];
  defaultAudioStreamIndex?: number;
}

export interface JellyfinPlaybackOptions {
  mediaSourceId?: string;
  audioStreamIndex?: number;
  maxBitrate?: number;
}

/** A raw text subtitle sidecar returned for the selected Jellyfin media source. */
export interface JellyfinSubtitleTrack {
  index: number;
  codec: string;
  label: string;
  language: string;
  url: string;
  isDefault: boolean;
  isForced: boolean;
  isHearingImpaired: boolean;
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

export function setCompanionUrl(url: string) {
  companionUrl = url;
}

export async function fetchPreferences() {
  if (!companionUrl) return;
  try {
    const res = await fetch(`${companionUrl}/api/preferences`);
    if (res.ok) {
      const data = await res.json();
      seerrEnabled = data.seerr_enabled === true;
      seerrUrl = (data.seerr_url || '').replace(/\/+$/, '');
      seerrApiKey = data.seerr_api_key || '';
    }
  } catch (e) {
    console.error('Failed to fetch preferences', e);
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

const WEB_TEXT_SUBTITLE_CODECS = new Set([
  'ass',
  'ssa',
  'srt',
  'subrip',
  'vtt',
  'webvtt',
]);

function normalizedSubtitleCodec(codec: string | undefined): string {
  const normalized = codec?.trim().toLowerCase() ?? '';
  if (normalized === 'webvtt') return 'vtt';
  if (normalized === 'subrip') return 'srt';
  return normalized;
}

function isJapaneseLanguage(language: string | undefined): boolean {
  const normalized = language?.trim().toLowerCase().split(/[-_]/)[0] ?? '';
  return normalized === 'ja' || normalized === 'jpn';
}

function subtitleTracksForSource(source: JellyfinMediaSourceInfo): JellyfinSubtitleTrack[] {
  const tracks = (source.MediaStreams ?? [])
    .filter((stream) => {
      if (stream.Type !== 'Subtitle' || !stream.DeliveryUrl?.trim()) return false;
      if (stream.IsTextSubtitleStream === false) return false;
      return WEB_TEXT_SUBTITLE_CODECS.has(stream.Codec?.trim().toLowerCase() ?? '');
    })
    .map((stream, ordinal): JellyfinSubtitleTrack => {
      const language = stream.Language?.trim() ?? '';
      const codec = normalizedSubtitleCodec(stream.Codec);
      return {
        index: Number.isInteger(stream.Index) ? stream.Index! : ordinal,
        codec,
        label: stream.DisplayTitle?.trim() || stream.Title?.trim() || language || `Subtitle ${ordinal + 1}`,
        language,
        url: resolveServerPath(stream.DeliveryUrl!.trim()),
        isDefault: stream.IsDefault === true,
        isForced: stream.IsForced === true,
        isHearingImpaired: stream.IsHearingImpaired === true,
      };
    });

  // Some servers repeat a sidecar entry when both Embed and External profiles
  // match. The exact server-provided DeliveryUrl is authoritative; only remove
  // byte-for-byte URL duplicates.
  return tracks.filter((track, index) =>
    tracks.findIndex((candidate) => candidate.url === track.url) === index);
}

function isFontAttachment(attachment: JellyfinMediaAttachment): boolean {
  const name = attachment.FileName?.toLowerCase() ?? '';
  const mime = attachment.MimeType?.toLowerCase() ?? '';
  const codec = attachment.Codec?.toLowerCase() ?? '';
  return (
    mime.includes('font') ||
    mime.includes('truetype') ||
    mime.includes('opentype') ||
    /\.(?:ttf|otf|ttc|otc)$/.test(name) ||
    /(?:ttf|otf|ttc|otc)/.test(codec)
  );
}

function fontUrlsForSource(
  itemId: string,
  source: JellyfinMediaSourceInfo,
): string[] {
  const mediaSourceId = source.Id?.trim();
  if (!mediaSourceId) return [];
  return (source.MediaAttachments ?? [])
    .filter(isFontAttachment)
    .map((attachment) => {
      if (attachment.DeliveryUrl?.trim()) {
        return resolveServerPath(attachment.DeliveryUrl.trim());
      }
      if (!Number.isInteger(attachment.Index)) return null;
      return buildUrl(
        `/Videos/${encodeURIComponent(itemId)}/${encodeURIComponent(mediaSourceId)}/Attachments/${attachment.Index}`,
      ).toString();
    })
    .filter((url): url is string => Boolean(url))
    .filter((url, index, urls) => urls.indexOf(url) === index);
}

/** Preserve a configured Jellyfin base path on root-relative HLS child URLs. */
export function resolveJellyfinRequestUrl(requestUrl: string): string {
  const server = getServerUrl();
  if (!server) return requestUrl;
  const serverUrl = new URL(server);
  const request = new URL(requestUrl, serverUrl);
  const basePath = serverUrl.pathname.replace(/\/+$/, '');
  if (
    !basePath ||
    basePath === '/' ||
    request.origin !== serverUrl.origin ||
    request.pathname === basePath ||
    request.pathname.startsWith(`${basePath}/`)
  ) return request.toString();
  request.pathname = `${basePath}/${request.pathname.replace(/^\/+/, '')}`;
  return request.toString();
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
    const response = await fetchJellyfin(buildUrl(path, options.query), {
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



export async function searchSeerr(query: string): Promise<SeerrResult[]> {
  if (!seerrEnabled || !seerrUrl || !seerrApiKey) return [];
  try {
    const res = await fetch(`${seerrUrl}/api/v1/search?query=${encodeURIComponent(query)}`, {
      headers: { 'X-Api-Key': seerrApiKey }
    });
    if (!res.ok) return [];
    const data = await res.json();
    return (data.results || []).filter((r: any) => r.mediaType === 'movie' || r.mediaType === 'tv');
  } catch (e) {
    console.error('Seerr search failed:', e);
    return [];
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

export async function fetchItem(itemId: string, signal?: AbortSignal): Promise<JellyfinItem> {
  return await requestJson<JellyfinItem>(`/Users/${requireUserId()}/Items/${itemId}`, {
    signal,
  });
}

export function extractMediaPills(item: JellyfinItem): string[] {
  const pills: string[] = [];
  const source = item.MediaSources?.[0];
  if (source) {
    const videoStream = source.MediaStreams?.find(s => s.Type === 'Video');
    if (videoStream) {
      if (videoStream.Width && videoStream.Width >= 3800) pills.push('4K');
      else if (videoStream.Width && videoStream.Width >= 1900) pills.push('1080p');
      else if (videoStream.Width && videoStream.Width >= 1200) pills.push('720p');
      if (videoStream.Codec) pills.push(videoStream.Codec.toUpperCase());
    } else if (source.VideoCodec) {
      pills.push(source.VideoCodec.toUpperCase());
    }
    const audioStream = source.MediaStreams?.find(s => s.Type === 'Audio');
    if (audioStream && audioStream.ChannelLayout) {
      pills.push(audioStream.ChannelLayout.toUpperCase());
    }
  }
  return pills;
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

export function movieVersionGroupKey(item: JellyfinItem): string | null {
  if (item.Type !== 'Movie') return null;
  const title = (item.OriginalTitle && item.OriginalTitle.trim().length > 0) ? item.OriginalTitle : item.Name;
  if (!title) return null;
  
  const baseTitle = title.toLowerCase()
    .replace(/\([^)]*\)/g, " ")
    .replace(/\[[^]]*]/g, " ")
    .replace(/\{[^}]*}/g, " ")
    .replace(/\b(3d|sbs|hsbs|fsbs|tab|tb|top.?bottom|ou|over.?under|half.?sbs|full.?sbs|mv-hevc|mvhevc|spatial|spatial\.video|4k|uhd|2160p|1080p|720p|bluray|blu.?ray|remux|hevc|x264|x265|av1|hdr10|hdr|dv|dovi|dolby.?vision)\b/g, " ")
    .replace(/\b(3d|sbs|hsbs|fsbs|tab|tb|top.?bottom|half.?sbs|full.?sbs)\b/g, " ")
    .replace(/\.(mkv|mp4|avi|mov|m4v)$/g, " ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
    
  const year = item.ProductionYear || (item.PremiereDate ? new Date(item.PremiereDate).getFullYear() : 'unknown');
  return `${baseTitle}|${year}`;
}

export function deduplicateMovieVersions(items: JellyfinItem[]): JellyfinItem[] {
  const groups = new Map<string, JellyfinItem[]>();
  
  for (const item of items) {
    const key = movieVersionGroupKey(item) ?? item.Id;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(item);
  }
  
  const deduplicated: JellyfinItem[] = [];
  for (const group of groups.values()) {
    // Sort logic: prioritize resumable, then unplayed, then anything
    group.sort((a, b) => {
      const aTicks = a.UserData?.PlaybackPositionTicks || 0;
      const bTicks = b.UserData?.PlaybackPositionTicks || 0;
      if (aTicks > 0 && bTicks === 0) return -1;
      if (bTicks > 0 && aTicks === 0) return 1;
      
      const aPlayed = a.UserData?.Played ? 1 : 0;
      const bPlayed = b.UserData?.Played ? 1 : 0;
      if (aPlayed !== bPlayed) return aPlayed - bPlayed;
      
      return 0;
    });
    deduplicated.push(group[0]);
  }
  
  return deduplicated;
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
  return deduplicateMovieVersions(validItems(data.Items));
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
  return deduplicateMovieVersions(validItems(data.Items)).filter(item => !item.UserData?.Played);
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
  return deduplicateMovieVersions(validItems(data.Items));
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
  return deduplicateMovieVersions(itemsFromResponse(data));
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

export async function fetchSeasons(
  seriesId: string,
): Promise<JellyfinItem[]> {
  const data = await requestJson<QueryResult<JellyfinItem>>(
    `/Shows/${encodeURIComponent(seriesId)}/Seasons`,
    {
      query: {
        userId: requireUserId(),
        fields: HOME_ITEM_FIELDS,
        enableImages: true,
        enableImageTypes: HOME_IMAGE_TYPES,
        imageTypeLimit: 1,
        enableUserData: true,
      },
    },
  );
  return validItems(data.Items);
}

export async function fetchSeasonEpisodes(
  seriesId: string,
  seasonId: string,
): Promise<JellyfinItem[]> {
  const data = await requestJson<QueryResult<JellyfinItem>>(
    `/Shows/${encodeURIComponent(seriesId)}/Episodes`,
    {
      query: {
        userId: requireUserId(),
        seasonId,
        fields: HOME_ITEM_FIELDS,
        enableImages: true,
        enableImageTypes: HOME_IMAGE_TYPES,
        imageTypeLimit: 1,
        enableUserData: true,
        sortBy: 'ParentIndexNumber,IndexNumber',
      },
    },
  );
  return validItems(data.Items);
}

export async function searchItems(searchTerm: string, includeItemTypes: string = 'Movie,Series'): Promise<JellyfinItem[]> {
  const data = await requestJson<QueryResult<JellyfinItem>>('/Items', {
    query: {
      userId: requireUserId(),
      searchTerm,
      recursive: true,
      includeItemTypes,
      fields: HOME_ITEM_FIELDS,
      enableImages: true,
      enableImageTypes: HOME_IMAGE_TYPES,
      imageTypeLimit: 1,
      enableUserData: true,
    },
  });
  return validItems(data.Items);
}

export async function fetchItemsByPerson(personId: string, includeItemTypes: string = 'Movie,Series'): Promise<JellyfinItem[]> {
  const data = await requestJson<QueryResult<JellyfinItem>>('/Items', {
    query: {
      userId: requireUserId(),
      personIds: personId,
      recursive: true,
      includeItemTypes,
      fields: HOME_ITEM_FIELDS,
      enableImages: true,
      enableImageTypes: HOME_IMAGE_TYPES,
      imageTypeLimit: 1,
      enableUserData: true,
    },
  });
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

function imageTag(item: { Id: string, ImageTags?: Record<string, string>, BackdropImageTags?: string[] }, imageType: JellyfinImageType): string | undefined {
  if (imageType === 'Backdrop') return item.BackdropImageTags?.[0];
  return item.ImageTags?.[imageType];
}

export async function fetchItemImage(
  item: { Id: string, ImageTags?: Record<string, string>, BackdropImageTags?: string[] },
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
  audioStreamIndex = source.DefaultAudioStreamIndex ?? undefined,
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
    AudioStreamIndex: audioStreamIndex,
    VideoCodec: 'hevc,av1,vp9,h264',
    AudioCodec: 'eac3,ac3,aac,flac,opus',
    MaxAudioChannels: 8,
    TranscodingContainer: 'mp4',
    SegmentContainer: 'mp4',
    AllowVideoStreamCopy: true,
    AllowAudioStreamCopy: true,
    EnableSubtitlesInManifest: true,
  }).toString();
}

function rankPlaybackSources(sources: JellyfinMediaSourceInfo[]): JellyfinMediaSourceInfo | undefined {
  return sources
    .filter((candidate) =>
      Boolean(candidate.TranscodingUrl?.trim()) ||
      Boolean(candidate.Id && candidate.SupportsTranscoding !== false))
    .map((candidate, ordinal) => {
      const codecs = (candidate.MediaStreams ?? [])
        .filter((stream) => stream.Type === 'Subtitle')
        .map((stream) => normalizedSubtitleCodec(stream.Codec));
      const score =
        (codecs.some((codec) => codec === 'ass' || codec === 'ssa') ? 1_000 : 0) +
        (candidate.TranscodingUrl?.trim() ? 100 : 0) +
        (candidate.SupportsTranscoding ? 20 : 0) -
        ordinal;
      return {candidate, score};
    })
    .sort((left, right) => right.score - left.score)[0]?.candidate
    ?? sources.find((candidate) => Boolean(candidate.Id));
}

function selectPlaybackSource(
  sources: JellyfinMediaSourceInfo[],
  preferredMediaSourceId?: string,
): JellyfinMediaSourceInfo | undefined {
  const preferredId = preferredMediaSourceId?.trim();
  if (preferredId) {
    const preferred = rankPlaybackSources(sources.filter((source) => source.Id === preferredId));
    if (preferred) return preferred;
  }
  return rankPlaybackSources(sources);
}

export async function fetchPlaybackInfo(
  itemId: string,
  signal?: AbortSignal,
  options: JellyfinPlaybackOptions = {},
): Promise<JellyfinPlaybackInfo> {
  const caps = await detectClientCapabilities();
  const userId = requireUserId();
  const requestedMediaSourceId = options.mediaSourceId?.trim() || undefined;
  const requestedAudioStreamIndex = Number.isInteger(options.audioStreamIndex)
    ? options.audioStreamIndex
    : undefined;
  const targetBitrate = options.maxBitrate && options.maxBitrate > 0 ? options.maxBitrate : MAX_STREAMING_BITRATE;

  const videoCodecs = ['h264'];
  if (caps.supportsHevc) videoCodecs.push('hevc', 'h265');
  if (caps.supportsAv1) videoCodecs.push('av1');
  if (caps.supportsVp9Hdr) videoCodecs.push('vp9');

  const audioCodecs = ['aac', 'mp3', 'flac', 'opus'];
  if (caps.supportsEac3) audioCodecs.push('eac3');
  if (caps.supportsAc3) audioCodecs.push('ac3');

  const maxChannels = caps.maxAudioChannels;

  const playbackRequestBody = {
    UserId: userId,
    MediaSourceId: requestedMediaSourceId,
    AudioStreamIndex: requestedAudioStreamIndex,
    MaxStreamingBitrate: targetBitrate,
    MaxAudioChannels: maxChannels,
    EnableDirectPlay: true,
    EnableDirectStream: true,
    EnableTranscoding: true,
    AllowVideoStreamCopy: true,
    AllowAudioStreamCopy: true,
    DeviceProfile: {
      Name: 'SpatialFin WebXR HDR/Surround',
      MaxStreamingBitrate: targetBitrate,
      MaxStaticBitrate: targetBitrate,
      DirectPlayProfiles: [
        {
          Container: 'mp4,m4v',
          Type: 'Video',
          VideoCodec: videoCodecs.join(','),
          AudioCodec: audioCodecs.join(','),
        },
        {
          Container: 'webm',
          Type: 'Video',
          VideoCodec: 'vp9,av1',
          AudioCodec: 'opus,vorbis',
        },
        {
          Container: 'mkv',
          Type: 'Video',
          VideoCodec: videoCodecs.join(','),
          AudioCodec: audioCodecs.join(','),
        },
      ],
      TranscodingProfiles: [
        {
          Container: 'mp4',
          Type: 'Video',
          VideoCodec: videoCodecs.join(','),
          AudioCodec: audioCodecs.join(','),
          Protocol: 'hls',
          EstimateContentLength: false,
          EnableMpegtsM2TsMode: false,
          TranscodeSeekInfo: 'Auto',
          CopyTimestamps: false,
          Context: 'Streaming',
          EnableSubtitlesInManifest: true,
          MaxAudioChannels: String(maxChannels),
          MinSegments: 1,
          SegmentLength: 0,
          BreakOnNonKeyFrames: false,
          Conditions: [],
          EnableAudioVbrEncoding: true,
        },
        {
          Container: 'ts',
          Type: 'Video',
          VideoCodec: 'h264',
          AudioCodec: 'aac,ac3,eac3',
          Protocol: 'hls',
          EstimateContentLength: false,
          EnableMpegtsM2TsMode: false,
          TranscodeSeekInfo: 'Auto',
          CopyTimestamps: false,
          Context: 'Streaming',
          EnableSubtitlesInManifest: true,
          MaxAudioChannels: String(maxChannels),
          MinSegments: 1,
          SegmentLength: 0,
          BreakOnNonKeyFrames: false,
          Conditions: [],
          EnableAudioVbrEncoding: true,
        },
      ],
      CodecProfiles: [
        {
          Type: 'Video',
          Codec: videoCodecs.join(','),
          Conditions: [
            {Condition: 'EqualsAny', Property: 'VideoRangeType', Value: 'SDR,HDR10,HLG,DOVI'},
            {Condition: 'LessThanEqual', Property: 'VideoBitDepth', Value: '10'},
          ],
        },
      ],
      ContainerProfiles: [],
      SubtitleProfiles: [
        {Format: 'ass', Method: 'External'},
        {Format: 'ssa', Method: 'External'},
        {Format: 'srt', Method: 'External'},
        {Format: 'subrip', Method: 'External'},
        {Format: 'vtt', Method: 'External'},
        {Format: 'webvtt', Method: 'External'},
      ],
    },
  };
  let response = await requestJson<JellyfinPlaybackInfoResponse>(
    `/Items/${encodeURIComponent(itemId)}/PlaybackInfo`,
    {
      method: 'POST',
      signal,
      json: playbackRequestBody,
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

  let source = selectPlaybackSource(response.MediaSources ?? [], requestedMediaSourceId);
  if (!source) {
    throw new JellyfinApiError(
      'Jellyfin did not return a playable media source for this item.',
    );
  }
  // Keep the complete source inventory from the discovery response. Some
  // servers narrow MediaStreams to the active rendition after an audio-pinned
  // renegotiation; losing the other streams here would make manual switching
  // impossible precisely when hls.js exposes only one audio track.
  const metadataSource = source;
  let negotiatedAudioStreamIndex = requestedAudioStreamIndex
    ?? source.DefaultAudioStreamIndex
    ?? undefined;

  // Android's anime default is original/Japanese audio. A Jellyfin
  // TranscodingUrl is commonly pinned to one audio stream, so changing only
  // hls.js's audioTrack is insufficient. When a raw text-subtitle source exposes
  // Japanese audio, renegotiate once with its actual stream index.
  const hasRawTextSubtitles = (source.MediaStreams ?? []).some((stream) =>
    stream.Type === 'Subtitle' && WEB_TEXT_SUBTITLE_CODECS.has(
      normalizedSubtitleCodec(stream.Codec),
    ));
  const preferredJapaneseAudio = hasRawTextSubtitles
    ? (source.MediaStreams ?? []).find((stream) =>
        stream.Type === 'Audio' && isJapaneseLanguage(stream.Language))
    : undefined;
  if (
    requestedAudioStreamIndex === undefined &&
    source.Id &&
    Number.isInteger(preferredJapaneseAudio?.Index) &&
    preferredJapaneseAudio!.Index !== source.DefaultAudioStreamIndex
  ) {
    const renegotiated = await requestJson<JellyfinPlaybackInfoResponse>(
      `/Items/${encodeURIComponent(itemId)}/PlaybackInfo`,
      {
        method: 'POST',
        signal,
        json: {
          ...playbackRequestBody,
          MediaSourceId: source.Id,
          AudioStreamIndex: preferredJapaneseAudio!.Index,
        },
      },
    );
    if (!renegotiated.ErrorCode) {
      const renegotiatedSource = selectPlaybackSource(
        renegotiated.MediaSources ?? [],
        source.Id,
      );
      if (renegotiatedSource) {
        response = renegotiated;
        source = renegotiatedSource;
        negotiatedAudioStreamIndex = preferredJapaneseAudio!.Index;
      }
    }
  }

  const headers = new Headers(getAuthHeaders({accept: '*/*'}));
  for (const [name, value] of Object.entries(source.RequiredHttpHeaders ?? {})) {
    if (value) headers.set(name, value);
  }

  const playSessionId = response.PlaySessionId?.trim() || undefined;
  return {
    streamUrl: buildPlaybackStreamUrl(itemId, source, playSessionId, negotiatedAudioStreamIndex),
    mediaSourceId: source.Id?.trim() || undefined,
    playSessionId,
    requiredHeaders: Object.fromEntries(headers.entries()),
    subtitleTracks: subtitleTracksForSource(metadataSource),
    fontUrls: fontUrlsForSource(itemId, metadataSource),
    audioStreams: (metadataSource.MediaStreams ?? []).filter((stream) => stream.Type === 'Audio'),
    defaultAudioStreamIndex: negotiatedAudioStreamIndex,
  };
}
export async function toggleFavorite(itemId: string, isFavorite: boolean): Promise<JellyfinItem> {
  const url = resolveJellyfinRequestUrl(`/Users/${getUserId()}/FavoriteItems/${itemId}`);
  const response = await fetch(url, {
    method: isFavorite ? 'POST' : 'DELETE',
    headers: getAuthHeaders(),
  });
  if (!response.ok) throw new Error('Failed to toggle favorite');
  return response.json();
}

export function getDownloadUrl(itemId: string): string {
  const urlString = resolveJellyfinRequestUrl(`/Items/${itemId}/Download`);
  const url = new URL(urlString, window.location.href);
  url.searchParams.set('api_key', getAccessToken() || '');
  return url.toString();
}

export function getVersionChipLabel(item: JellyfinItem): string {
  const sources = item.MediaSources || [];
  const sourceNames = sources.flatMap(s => [s.Name || '', s.Path || '']);
  const haystacks = [item.Video3DFormat || '', item.Name || '', ...sourceNames].join(' ').toLowerCase();

  let stereoMode = 'MONO';
  if (/\b(mv-hevc|mvhevc|spatial|spatial[\s.-]?video)\b/.test(haystacks)) {
    stereoMode = 'MULTIVIEW';
  } else if (/\b(tab|tb|top[\s.-]?bottom|top[\s.-]?and[\s.-]?bottom|ou|over[\s.-]?under|3d[\s.-]?(tab|tb|ou))\b/.test(haystacks)) {
    stereoMode = 'TOP_BOTTOM';
  } else if (/\b(hsbs|half[\s.-]?sbs|fsbs|full[\s.-]?sbs|sbs|side[\s.-]?by[\s.-]?side|3d[\s.-]?h?sbs)\b/.test(haystacks)) {
    stereoMode = 'SIDE_BY_SIDE';
  } else if (/\b3d\b/.test(haystacks)) {
    stereoMode = 'SIDE_BY_SIDE';
  }

  let stereoLabel = '2D';
  if (stereoMode === 'SIDE_BY_SIDE') stereoLabel = '3D SBS';
  if (stereoMode === 'TOP_BOTTOM') stereoLabel = '3D T/B';
  if (stereoMode === 'MULTIVIEW') stereoLabel = 'Spatial';

  let qualityLabel: string | null = null;
  for (const name of sourceNames) {
    const match = name.toLowerCase().match(/(4k|2160p|1080p|720p)/);
    if (match) {
      const value = match[1].toUpperCase();
      if (value === '4K' || value === '2160P') {
        qualityLabel = value === '4K' ? '4K' : '2160P';
      } else if (value === '1080P') {
        qualityLabel = '1080P';
      } else if (value === '720P') {
        qualityLabel = '720P';
      }
      break;
    }
  }

  return qualityLabel ? `${stereoLabel} ${qualityLabel}` : stereoLabel;
}
