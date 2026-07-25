import SubtitlesOctopus from '@jellyfin/libass-wasm';
import type {
  JellyfinItem,
  JellyfinMediaStream,
  JellyfinSubtitleTrack,
} from './api';
import {getAuthHeaders, getServerUrl, mediaUrlWithAccessToken} from './auth';
import {fetchJellyfin} from './network';

const SUBTITLE_PREFERENCE_KEY = 'spatialfin_subtitle_preferences_v1';
const AUDIO_PREFERENCE_KEY = 'spatialfin_audio_preferences_v1';
const ASSET_FETCH_TIMEOUT_MS = 15_000;
const MAX_SUBTITLE_BYTES = 50 * 1024 * 1024;
const ASS_WORKER_URL = `${import.meta.env.BASE_URL}libass/spatialfin-subtitles-worker.js`;
const FALLBACK_FONT_URL = `${import.meta.env.BASE_URL}libass/default.woff2`;
const CJK_FONT_URL = `${import.meta.env.BASE_URL}libass/noto-sans-jp.woff2`;

interface AnimeSubtitleRendererOptions {
  track: JellyfinSubtitleTrack;
  fontUrls: string[];
  video?: HTMLVideoElement;
  canvas?: HTMLCanvasElement;
  signal?: AbortSignal;
  onReady?: () => void;
  onError?: (error: unknown) => void;
}

interface SubtitlePreferenceMap {
  [seriesKey: string]: string | null;
}

interface AudioPreferenceMap {
  [seriesKey: string]: string;
}

export interface InitialSubtitleSelection {
  index: number;
  reason: 'remembered' | 'dialogue' | 'forced' | 'default' | 'off';
}

function sameJellyfinOrigin(url: string): boolean {
  const server = getServerUrl();
  if (!server) return false;
  try {
    return new URL(url, window.location.href).origin === new URL(server).origin;
  } catch {
    return false;
  }
}

async function fetchSubtitleAsset(url: string, signal?: AbortSignal): Promise<Response> {
  const timeoutSignal = AbortSignal.timeout(ASSET_FETCH_TIMEOUT_MS);
  const response = await fetchJellyfin(url, {
    signal: signal ? AbortSignal.any([signal, timeoutSignal]) : timeoutSignal,
    headers: sameJellyfinOrigin(url)
      ? getAuthHeaders({accept: '*/*', contentType: null})
      : {Accept: '*/*'},
  });
  if (!response.ok) {
    throw new Error(`Jellyfin returned ${response.status} while loading subtitle assets.`);
  }
  return response;
}

async function readBytesWithLimit(response: Response, limit: number): Promise<Uint8Array> {
  const declaredLength = Number(response.headers.get('content-length'));
  if (Number.isFinite(declaredLength) && declaredLength > limit) {
    throw new Error(`Subtitle asset is too large (${Math.ceil(declaredLength / 1024 / 1024)} MiB).`);
  }
  if (!response.body) {
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.byteLength > limit) throw new Error('Subtitle asset exceeds the safe memory limit.');
    return bytes;
  }

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const {done, value} = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel();
      throw new Error('Subtitle asset exceeds the safe memory limit.');
    }
    chunks.push(value);
  }
  const result = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

async function readTextWithLimit(response: Response): Promise<string> {
  return new TextDecoder().decode(await readBytesWithLimit(response, MAX_SUBTITLE_BYTES));
}

function workerFontUrls(urls: string[]): string[] {
  return [...new Set(urls)].map((url) =>
    sameJellyfinOrigin(url) ? mediaUrlWithAccessToken(url) : url);
}

function decodeHtml(value: string): string {
  const textarea = document.createElement('textarea');
  textarea.innerHTML = value;
  return textarea.value;
}

function subtitleTextToAss(value: string): string {
  const convertedTags = value
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<i(?:\s+[^>]*)?>/gi, '{\\i1}')
    .replace(/<\/i>/gi, '{\\i0}')
    .replace(/<b(?:\s+[^>]*)?>/gi, '{\\b1}')
    .replace(/<\/b>/gi, '{\\b0}')
    .replace(/<u(?:\s+[^>]*)?>/gi, '{\\u1}')
    .replace(/<\/u>/gi, '{\\u0}')
    .replace(/<[^>]+>/g, '');
  const decoded = decodeHtml(convertedTags);
  // Protect literal ASS override delimiters and backslashes while retaining
  // the small set of overrides deliberately generated above.
  const tokens: string[] = [];
  const tokenized = decoded.replace(/\{\\[ibu][01]\}/g, (token) => {
    tokens.push(token);
    return `\u0000${tokens.length - 1}\u0000`;
  });
  return tokenized
    .replace(/\\/g, '\\\\')
    .replace(/\{/g, '\\{')
    .replace(/\}/g, '\\}')
    .replace(/\r?\n/g, '\\N')
    .replace(/\u0000(\d+)\u0000/g, (_match, index: string) => tokens[Number(index)]);
}

function parseTimestamp(value: string): number | null {
  const match = value.trim().match(/^(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})/);
  if (!match) return null;
  const hours = Number(match[1] ?? 0);
  const minutes = Number(match[2]);
  const seconds = Number(match[3]);
  const milliseconds = Number(match[4].padEnd(3, '0'));
  if (![hours, minutes, seconds, milliseconds].every(Number.isFinite)) return null;
  return hours * 3600 + minutes * 60 + seconds + milliseconds / 1000;
}

function assTimestamp(seconds: number): string {
  const centiseconds = Math.max(0, Math.round(seconds * 100));
  const hours = Math.floor(centiseconds / 360_000);
  const minutes = Math.floor(centiseconds / 6_000) % 60;
  const wholeSeconds = Math.floor(centiseconds / 100) % 60;
  const fraction = centiseconds % 100;
  return `${hours}:${minutes.toString().padStart(2, '0')}:${wholeSeconds
    .toString().padStart(2, '0')}.${fraction.toString().padStart(2, '0')}`;
}

function plainTextToAss(content: string): string {
  const normalized = content
    .replace(/^\uFEFF/, '')
    .replace(/^WEBVTT[^\r\n]*(?:\r?\n)+/i, '')
    .replace(/\r\n/g, '\n');
  const events: string[] = [];
  for (const block of normalized.split(/\n[\t ]*\n+/)) {
    const lines = block.split('\n').map((line) => line.trimEnd());
    const timingIndex = lines.findIndex((line) => line.includes('-->'));
    if (timingIndex < 0) continue;
    const timing = lines[timingIndex].split('-->');
    const start = parseTimestamp(timing[0]);
    const end = parseTimestamp(timing[1]);
    if (start === null || end === null || end <= start) continue;
    const text = subtitleTextToAss(lines.slice(timingIndex + 1).join('\n').trim());
    if (!text) continue;
    events.push(`Dialogue: 0,${assTimestamp(start)},${assTimestamp(end)},Default,,0,0,0,,${text}`);
  }

  return `[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,52,&H00FFFFFF,&H000000FF,&H00101010,&H80000000,0,0,0,0,100,100,0,0,1,3,0,2,60,60,42,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
${events.join('\n')}
`;
}

function trackSignature(track: JellyfinSubtitleTrack): string {
  return [
    track.language.toLowerCase(),
    track.label.toLowerCase(),
    track.isForced ? 'forced' : 'full',
    track.isHearingImpaired ? 'sdh' : 'standard',
  ].join('|');
}

function audioStreamSignature(stream: JellyfinMediaStream): string {
  return [
    normalizedLanguage(stream.Language),
    (stream.Title?.trim() || stream.DisplayTitle?.trim() || '').toLowerCase(),
    stream.Codec?.trim().toLowerCase() ?? '',
    stream.ChannelLayout?.trim().toLowerCase() ?? '',
  ].join('|');
}

function preferenceKey(item: JellyfinItem): string {
  return item.SeriesId?.trim() || item.SeriesName?.trim() || item.Id;
}

function readPreferences(): SubtitlePreferenceMap {
  try {
    const parsed = JSON.parse(localStorage.getItem(SUBTITLE_PREFERENCE_KEY) ?? '{}') as unknown;
    return parsed && typeof parsed === 'object' ? parsed as SubtitlePreferenceMap : {};
  } catch {
    return {};
  }
}

function readAudioPreferences(): AudioPreferenceMap {
  try {
    const parsed = JSON.parse(localStorage.getItem(AUDIO_PREFERENCE_KEY) ?? '{}') as unknown;
    return parsed && typeof parsed === 'object' ? parsed as AudioPreferenceMap : {};
  } catch {
    return {};
  }
}

export function rememberSubtitleSelection(
  item: JellyfinItem,
  track: JellyfinSubtitleTrack | null,
): void {
  const preferences = readPreferences();
  preferences[preferenceKey(item)] = track ? trackSignature(track) : null;
  try {
    localStorage.setItem(SUBTITLE_PREFERENCE_KEY, JSON.stringify(preferences));
  } catch {
    // Playback remains functional when storage is unavailable or full.
  }
}

/** Remember a manual audio choice by logical track identity, never by episode-local index. */
export function rememberAudioSelection(
  item: JellyfinItem,
  stream: JellyfinMediaStream,
): void {
  const preferences = readAudioPreferences();
  preferences[preferenceKey(item)] = audioStreamSignature(stream);
  try {
    localStorage.setItem(AUDIO_PREFERENCE_KEY, JSON.stringify(preferences));
  } catch {
    // Playback remains functional when storage is unavailable or full.
  }
}

function normalizedLanguage(language: string | undefined): string {
  const normalized = language?.trim().toLowerCase().split(/[-_]/)[0] ?? '';
  const iso639Aliases: Record<string, string> = {
    eng: 'en', jpn: 'ja', spa: 'es', fra: 'fr', fre: 'fr', deu: 'de', ger: 'de',
    ita: 'it', por: 'pt', rus: 'ru', zho: 'zh', chi: 'zh', kor: 'ko', ara: 'ar',
    hin: 'hi', nld: 'nl', dut: 'nl', pol: 'pl', tur: 'tr', swe: 'sv', nor: 'no',
    dan: 'da', fin: 'fi', ces: 'cs', cze: 'cs', hun: 'hu', ron: 'ro', rum: 'ro',
    ukr: 'uk', heb: 'he', ind: 'id', tha: 'th', vie: 'vi', ell: 'el', gre: 'el',
  };
  return iso639Aliases[normalized] ?? normalized;
}

export function subtitleLanguageMatches(
  left: string | undefined,
  right: string | undefined,
): boolean {
  const normalizedLeft = normalizedLanguage(left);
  return Boolean(normalizedLeft && normalizedLeft === normalizedLanguage(right));
}

function itemLooksLikeAnime(
  item: JellyfinItem,
  tracks: JellyfinSubtitleTrack[],
  audioStreams: JellyfinMediaStream[],
): boolean {
  const genres = item.Genres?.map((genre) => genre.trim().toLowerCase()) ?? [];
  const explicitlyAnime = genres.some((genre) => genre === 'anime' || genre.includes('japanese animation'));
  const animation = genres.some((genre) => genre === 'animation' || genre === 'animated');
  const japaneseAudio = audioStreams.some((stream) =>
    subtitleLanguageMatches(stream.Language, 'ja'));
  const rawTextSubtitles = tracks.some((track) =>
    ['ass', 'ssa', 'srt', 'subrip', 'vtt', 'webvtt'].includes(track.codec.toLowerCase()));
  const japaneseTitle = /[\u3040-\u30ff\u3400-\u9fff]/u.test(item.Name);
  return explicitlyAnime || japaneseTitle || (japaneseAudio && (animation || rawTextSubtitles));
}

function spokenLanguages(): string[] {
  const values = navigator.languages?.length ? navigator.languages : [navigator.language || 'en'];
  return [...new Set(values.map(normalizedLanguage).filter(Boolean))];
}

function roleIsForced(track: JellyfinSubtitleTrack): boolean {
  return track.isForced || /\b(?:forced?|foreign|narrative|non[-_\s]?english|signs?|songs?|short|partly)\b|s[&+/]s/i.test(track.label);
}

function selectedAudioLanguage(
  streams: JellyfinMediaStream[],
  defaultAudioStreamIndex?: number,
): string {
  const selected = streams.find((stream) => stream.Index === defaultAudioStreamIndex)
    ?? streams.find((stream) => stream.IsDefault)
    ?? streams[0];
  return normalizedLanguage(selected?.Language);
}

function validStreamIndex(stream: JellyfinMediaStream | undefined): number | undefined {
  return Number.isInteger(stream?.Index) ? stream!.Index : undefined;
}

/**
 * Pick the episode-local stream index for the shared web audio policy.
 *
 * A remembered per-series signature always wins. Otherwise anime prefers its
 * Japanese/original track, while other content retains an understood server
 * default before falling back to the viewer's first understood language.
 */
export function chooseInitialAudioStreamIndex(
  item: JellyfinItem,
  subtitleTracks: JellyfinSubtitleTrack[],
  audioStreams: JellyfinMediaStream[],
  defaultAudioStreamIndex?: number,
): number | undefined {
  if (audioStreams.length === 0) return undefined;

  const remembered = readAudioPreferences()[preferenceKey(item)];
  if (remembered) {
    const rememberedStream = audioStreams.find((stream) =>
      audioStreamSignature(stream) === remembered);
    const rememberedIndex = validStreamIndex(rememberedStream);
    if (rememberedIndex !== undefined) return rememberedIndex;
  }

  if (itemLooksLikeAnime(item, subtitleTracks, audioStreams)) {
    const japaneseStreams = audioStreams.filter((stream) =>
      subtitleLanguageMatches(stream.Language, 'ja'));
    const japaneseStream = japaneseStreams.find((stream) => stream.IsDefault)
      ?? japaneseStreams[0];
    const japaneseIndex = validStreamIndex(japaneseStream);
    if (japaneseIndex !== undefined) return japaneseIndex;
  }

  const defaultStream = audioStreams.find((stream) => stream.Index === defaultAudioStreamIndex)
    ?? audioStreams.find((stream) => stream.IsDefault)
    ?? audioStreams[0];
  const spoken = spokenLanguages();
  const defaultLanguage = normalizedLanguage(defaultStream.Language);
  const defaultIndex = validStreamIndex(defaultStream)
    ?? (Number.isInteger(defaultAudioStreamIndex) ? defaultAudioStreamIndex : undefined);
  if (!defaultLanguage || spoken.includes(defaultLanguage)) return defaultIndex;

  for (const language of spoken) {
    const understoodIndex = validStreamIndex(audioStreams.find((stream) =>
      normalizedLanguage(stream.Language) === language));
    if (understoodIndex !== undefined) return understoodIndex;
  }
  return defaultIndex ?? validStreamIndex(audioStreams.find((stream) =>
    Number.isInteger(stream.Index)));
}

export function preferredAudioLanguage(
  item: JellyfinItem,
  tracks: JellyfinSubtitleTrack[],
  audioStreams: JellyfinMediaStream[],
  defaultAudioStreamIndex?: number,
): string {
  const selectedIndex = chooseInitialAudioStreamIndex(
    item,
    tracks,
    audioStreams,
    defaultAudioStreamIndex,
  );
  const selected = audioStreams.find((stream) => stream.Index === selectedIndex);
  return selected
    ? normalizedLanguage(selected.Language)
    : selectedAudioLanguage(audioStreams, defaultAudioStreamIndex);
}

export function chooseInitialSubtitleTrack(
  item: JellyfinItem,
  tracks: JellyfinSubtitleTrack[],
  audioStreams: JellyfinMediaStream[],
  defaultAudioStreamIndex?: number,
): InitialSubtitleSelection {
  if (tracks.length === 0) return {index: -1, reason: 'off'};

  const preferences = readPreferences();
  const key = preferenceKey(item);
  if (Object.hasOwn(preferences, key)) {
    const remembered = preferences[key];
    if (remembered === null) return {index: -1, reason: 'remembered'};
    const index = tracks.findIndex((track) => trackSignature(track) === remembered);
    if (index >= 0) return {index, reason: 'remembered'};
  }

  const spoken = spokenLanguages();
  const audioLanguage = preferredAudioLanguage(
    item,
    tracks,
    audioStreams,
    defaultAudioStreamIndex,
  );
  // Unknown metadata is not evidence that the audio is foreign. Avoid
  // unexpectedly enabling full subtitles until a real language is known.
  const audioIsUnderstood = !audioLanguage || spoken.includes(audioLanguage);
  const desiredLanguage = spoken.find((language) =>
    tracks.some((track) => normalizedLanguage(track.language) === language));

  if (audioIsUnderstood) {
    const targetLanguages = (audioLanguage ? [audioLanguage, ...spoken] : spoken)
      .filter((lang, index, self) => Boolean(lang) && self.indexOf(lang) === index);
    let forcedIndex = targetLanguages.length > 0
      ? tracks.findIndex((track) =>
          roleIsForced(track) &&
          targetLanguages.some((lang) => subtitleLanguageMatches(track.language, lang)))
      : -1;
    if (forcedIndex < 0) {
      forcedIndex = tracks.findIndex((track) =>
        roleIsForced(track) &&
        (!track.language || normalizedLanguage(track.language) === 'und' || normalizedLanguage(track.language) === ''));
    }
    return forcedIndex >= 0
      ? {index: forcedIndex, reason: 'forced'}
      : {index: -1, reason: 'off'};
  }

  // Match Android's conservative fallback: with foreign audio but no subtitle
  // in a spoken language, use an explicit server default (or the only track)
  // instead of selecting an arbitrary language from a multi-track release.
  if (!desiredLanguage) {
    const defaultIndex = tracks.findIndex((track) => track.isDefault);
    if (defaultIndex >= 0) return {index: defaultIndex, reason: 'default'};
    return tracks.length === 1
      ? {index: 0, reason: 'dialogue'}
      : {index: -1, reason: 'off'};
  }

  const candidates = tracks
    .map((track, index) => {
      const language = normalizedLanguage(track.language);
      let score = 0;
      if (desiredLanguage && language === desiredLanguage) score += 100;
      else if (spoken.includes(language)) score += 80;
      if (!roleIsForced(track)) score += 35;
      if (track.isDefault) score += 12;
      if (track.codec === 'ass' || track.codec === 'ssa') score += 5;
      if (/\b(?:full|dialog(?:ue)?)\b/i.test(track.label)) score += 35;
      if (track.isHearingImpaired || /\b(?:sdh|cc|hearing.?impaired)\b/i.test(track.label)) score -= 15;
      return {index, score};
    })
    .sort((left, right) => right.score - left.score);
  const best = candidates[0];
  if (best && best.score > 0) return {index: best.index, reason: 'dialogue'};
  const defaultIndex = tracks.findIndex((track) => track.isDefault);
  return defaultIndex >= 0
    ? {index: defaultIndex, reason: 'default'}
    : {index: 0, reason: 'dialogue'};
}

/** Owns one libass-wasm worker and its subtitle rendering lifecycle. */
export class AnimeSubtitleRenderer {
  private readonly renderer: SubtitlesOctopus;
  private readonly worker: Worker;
  private readonly onRuntimeReady?: () => void;
  private readonly manualClock: boolean;
  private readonly handleWorkerMessage: (event: MessageEvent) => void;
  private disposed = false;
  private pendingTime = 0;
  private pendingPaused = true;
  private pendingRate = 1;
  private pendingSize: {width: number; height: number} | null = null;
  private lastSentTime: number | null = null;
  ready = false;

  private constructor(
    renderer: SubtitlesOctopus,
    manualClock: boolean,
    onRuntimeReady?: () => void,
  ) {
    this.renderer = renderer;
    this.manualClock = manualClock;
    this.onRuntimeReady = onRuntimeReady;
    this.worker = renderer.worker;
    this.handleWorkerMessage = (event) => {
      // `ready` is emitted before Emscripten has fully drained its startup
      // queue, so it is not proof that a frame can render yet. It is still the
      // correct point to enqueue exactly one coalesced clock state: the worker
      // has no active main loop and therefore emits no `tick` until that first
      // render command. Per-frame messages remain suppressed until here and
      // unchanged timestamps remain deduplicated afterward.
      if (event.data?.target !== 'ready' || this.ready || this.disposed) return;
      this.ready = true;
      if (this.manualClock) {
        if (this.pendingSize) {
          this.renderer.resize(this.pendingSize.width, this.pendingSize.height);
        }
        this.renderer.setRate(this.pendingRate);
        this.renderer.setIsPaused(this.pendingPaused, this.pendingTime);
        this.lastSentTime = this.pendingTime;
      }
      this.onRuntimeReady?.();
    };
    this.worker.addEventListener('message', this.handleWorkerMessage);
  }

  static async create(options: AnimeSubtitleRendererOptions): Promise<AnimeSubtitleRenderer> {
    if (!options.video && !options.canvas) {
      throw new Error('Anime subtitles require a video element or render canvas.');
    }
    const subtitleResponse = await fetchSubtitleAsset(options.track.url, options.signal);
    const fonts = workerFontUrls(options.fontUrls);
    if (options.signal?.aborted) throw new DOMException('Aborted', 'AbortError');

    const isAss = options.track.codec === 'ass' || options.track.codec === 'ssa';
    let subContent: string;
    if (isAss) {
      // Keep the complete script—header, styles, PlayRes, attachments names,
      // event layers, and override tags—unchanged. Supplying content also
      // avoids a browser-specific failure where synchronous XHR inside the
      // WASM worker could not read a page-created Blob URL.
      subContent = await readTextWithLimit(subtitleResponse);
    } else {
      subContent = plainTextToAss(await readTextWithLimit(subtitleResponse));
    }

    let instance: AnimeSubtitleRenderer | null = null;
    const renderer = new SubtitlesOctopus({
      video: options.video,
      canvas: options.canvas,
      subContent,
      // Anime releases normally carry their authored fonts. A bundled Noto
      // Sans JP subset prevents tofu when an attachment is missing or its
      // internal family metadata is malformed.
      fonts: [CJK_FONT_URL, ...fonts],
      workerUrl: ASS_WORKER_URL,
      fallbackFont: FALLBACK_FONT_URL,
      renderMode: 'wasm-blend',
      targetFps: 60,
      dropAllAnimations: false,
      onError: (error) => {
        instance?.handleFatalError();
        options.onError?.(error);
      },
    });
    instance = new AnimeSubtitleRenderer(
      renderer,
      !options.video,
      options.onReady,
    );
    return instance;
  }

  setCurrentTime(seconds: number): void {
    this.pendingTime = seconds;
    if (
      !this.disposed &&
      this.ready &&
      (this.lastSentTime === null || Math.abs(seconds - this.lastSentTime) >= 0.0005)
    ) {
      this.lastSentTime = seconds;
      this.renderer.setCurrentTime(seconds);
    }
  }

  setPaused(paused: boolean, seconds: number): void {
    this.pendingPaused = paused;
    this.pendingTime = seconds;
    if (!this.disposed && this.ready) {
      this.lastSentTime = seconds;
      this.renderer.setIsPaused(paused, seconds);
    }
  }

  setRate(rate: number): void {
    this.pendingRate = rate;
    if (!this.disposed && this.ready) this.renderer.setRate(rate);
  }

  resize(width: number, height: number): void {
    this.pendingSize = {width, height};
    if (!this.disposed && this.ready) this.renderer.resize(width, height);
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.worker.removeEventListener('message', this.handleWorkerMessage);
    try {
      this.renderer.dispose();
    } catch (error) {
      console.warn('Could not dispose the libass subtitle worker cleanly:', error);
    }
  }

  private handleFatalError(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.ready = false;
    this.worker.removeEventListener('message', this.handleWorkerMessage);
  }
}
