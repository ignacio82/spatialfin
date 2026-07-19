import * as THREE from 'three';
import * as xb from 'xrblocks';
import Hls, {FetchLoader} from 'hls.js';
import {
  fetchItem,
  fetchPlaybackInfo,
  resolveJellyfinRequestUrl,
  type JellyfinItem,
  type JellyfinMediaStream,
  type JellyfinPlaybackInfo,
  type JellyfinSubtitleTrack,
} from './api';
import {CanvasView, fillRoundedRect, formatClock, type CanvasPointer} from './CanvasView';
import {getAuthHeaders, getServerUrl, mediaUrlWithAccessToken} from './auth';
import {HomeSpace} from './HomeSpace';
import {createJellyfinRequest, fetchJellyfin, streamingFetchSupported} from './network';
import {
  AnimeSubtitleRenderer,
  chooseInitialAudioStreamIndex,
  chooseInitialSubtitleTrack,
  rememberAudioSelection,
  rememberSubtitleSelection,
} from './AnimeSubtitleRenderer';
import { syncPlayCoordinator } from './SyncPlayCoordinator';
import { offlineMediaRepository } from './OfflineMediaRepository';


const VIDEO_DEPTH_METERS = 6;
const UI_DEPTH_METERS = 2;
const VIDEO_WIDTH_METERS = 8;
const VIDEO_HEIGHT_METERS = 4.5;
const DEFAULT_VIDEO_SCALE = 1.39;
const VIDEO_GRAB_MARGIN_METERS = 0.4;
const MIN_VIDEO_SCALE = 0.75;
const MAX_VIDEO_SCALE = 2.5;
const CONTROLS_AUTO_HIDE_MS = 5_000;
const ACCENT = '#4fc3f7';
const ON_SURFACE = '#ffffff';
const MUTED = 'rgba(255,255,255,0.68)';
const GLASS = '#000000e6';

const LS_KEY_PLAYER_SCALE = 'spatialfin_xr_player_scale';
const LS_KEY_PLAYER_DEPTH = 'spatialfin_xr_player_depth';

function loadSavedPlayerScale(): number {
  try {
    const saved = localStorage.getItem(LS_KEY_PLAYER_SCALE);
    if (saved) {
      const val = parseFloat(saved);
      if (Number.isFinite(val) && val >= MIN_VIDEO_SCALE && val <= MAX_VIDEO_SCALE) {
        return val;
      }
    }
  } catch {}
  return DEFAULT_VIDEO_SCALE;
}

function savePlayerScale(scale: number): void {
  try {
    localStorage.setItem(LS_KEY_PLAYER_SCALE, scale.toString());
  } catch {}
}

function loadSavedPlayerDepth(): number {
  try {
    const saved = localStorage.getItem(LS_KEY_PLAYER_DEPTH);
    if (saved) {
      const val = parseFloat(saved);
      if (Number.isFinite(val) && val >= 0.75 && val <= 15) {
        return val;
      }
    }
  } catch {}
  return VIDEO_DEPTH_METERS;
}

function savePlayerDepth(depth: number): void {
  try {
    localStorage.setItem(LS_KEY_PLAYER_DEPTH, depth.toString());
  } catch {}
}

type ProjectionMode = 'flat' | '180' | '360';

interface TransportState {
  title: string;
  status: string;
  isPlaying: boolean;
  isLocked: boolean;
  position: number;
  duration: number;
}

interface TransportActions {
  back: () => void;
  togglePlayback: () => void;
  seekBy: (seconds: number) => void;
  seekFraction: (fraction: number) => void;
  chapters: () => void;
  interact: () => void;
}

interface OrbiterButton {
  id: string;
  icon: string;
  label: string;
  active?: boolean;
  disabled?: boolean;
  action: () => void;
}

interface PlaybackRestoreState {
  position: number;
  paused: boolean;
  playbackRate: number;
  nativeSubtitleTrack: number;
  nativeSubtitleDisplay: boolean;
  subtitlesVisible: boolean;
  selectedSubtitleIndex: number;
}

class TransportView extends CanvasView {
  private readonly getState: () => TransportState;
  private readonly actions: TransportActions;
  private seeking = false;
  private seekPreview: number | null = null;
  private readonly seekBounds = {x: 215, y: 354, width: 1370, height: 72};

  constructor(getState: () => TransportState, actions: TransportActions) {
    super(1800, 800, {name: 'Android XR transport controls'});
    this.getState = getState;
    this.actions = actions;
    this.userData.layout = 'android-xr-bottom-transport';
    this.redraw();
  }

  refresh() {
    this.redraw();
  }

  protected override draw() {
    const ctx = this.context;
    const state = this.getState();
    ctx.textAlign = 'left';
    this.drawCircleButton('back', '←', 60, 58, 100, this.actions.back);
    ctx.fillStyle = ON_SURFACE;
    ctx.font = '650 58px system-ui, sans-serif';
    ctx.fillText(this.ellipsize(state.title, 1420), 196, 112);
    ctx.fillStyle = state.isLocked ? '#ef5350' : ACCENT;
    ctx.font = '500 31px system-ui, sans-serif';
    ctx.fillText(
      state.isLocked ? 'Controls & Screen Locked' : 'Spatial Playback',
      196,
      157,
    );
    ctx.fillStyle = MUTED;
    ctx.font = '500 23px system-ui, sans-serif';
    ctx.fillText(state.status, 196, 196);

    if (!state.isLocked) this.drawTimeline(state);
    this.drawTransport(state);
    this.userData.uiLabels = [
      state.title,
      state.status,
      state.isPlaying ? 'Pause' : 'Play',
      'Rewind',
      'Forward',
      'Chapters',
    ];
  }

  private drawTimeline(state: TransportState) {
    const ctx = this.context;
    const bounds = this.seekBounds;
    const rawFraction = state.duration > 0 ? state.position / state.duration : 0;
    const fraction = THREE.MathUtils.clamp(this.seekPreview ?? rawFraction, 0, 1);
    ctx.textAlign = 'right';
    ctx.fillStyle = MUTED;
    ctx.font = '500 29px system-ui, sans-serif';
    ctx.fillText(formatClock(this.seekPreview === null ? state.position : fraction * state.duration), bounds.x - 25, 401);
    ctx.textAlign = 'left';
    ctx.fillText(formatClock(state.duration), bounds.x + bounds.width + 25, 401);

    fillRoundedRect(ctx, 'rgba(255,255,255,0.22)', bounds.x, 381, bounds.width, 16, 8);
    fillRoundedRect(ctx, '#a4c9fe', bounds.x, 381, bounds.width * fraction, 16, 8);
    ctx.fillStyle = '#d3e3ff';
    ctx.beginPath();
    ctx.arc(bounds.x + bounds.width * fraction, 389, this.seeking ? 27 : 21, 0, Math.PI * 2);
    ctx.fill();
  }

  private drawTransport(state: TransportState) {
    const ctx = this.context;
    const centerY = 620;
    if (!state.isLocked) {
      this.drawCircleButton('rewind', '↶', 522, centerY - 70, 140, () => this.actions.seekBy(-10));
    }
    this.drawCircleButton(
      'play',
      state.isPlaying ? 'Ⅱ' : '▶',
      742,
      centerY - 80,
      160,
      this.actions.togglePlayback,
      true,
    );
    if (!state.isLocked) {
      const chapterHovered = this.isHovered('chapters');
      fillRoundedRect(
        ctx,
        chapterHovered ? 'rgba(255,255,255,0.16)' : 'rgba(255,255,255,0.05)',
        970,
        centerY - 56,
        220,
        112,
        56,
      );
      ctx.textAlign = 'center';
      ctx.fillStyle = ON_SURFACE;
      ctx.font = '600 31px system-ui, sans-serif';
      ctx.fillText('Chapters', 1080, centerY + 11);
      this.addHitZone({id: 'chapters', x: 970, y: centerY - 56, width: 220, height: 112, action: this.actions.chapters});
      this.drawCircleButton('forward', '↷', 1258, centerY - 70, 140, () => this.actions.seekBy(10));
    }
  }

  private drawCircleButton(
    id: string,
    label: string,
    x: number,
    y: number,
    size: number,
    action: () => void,
    primary = false,
  ) {
    const ctx = this.context;
    const hovered = this.isHovered(id);
    ctx.fillStyle = primary
      ? (hovered ? '#d3e3ff' : '#a4c9fe')
      : (hovered ? 'rgba(255,255,255,0.17)' : 'rgba(255,255,255,0.07)');
    ctx.beginPath();
    ctx.arc(x + size / 2, y + size / 2, size / 2, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = primary ? '#00315c' : ON_SURFACE;
    ctx.textAlign = 'center';
    ctx.font = `650 ${Math.round(size * 0.44)}px system-ui, sans-serif`;
    ctx.fillText(label, x + size / 2, y + size * 0.68);
    this.addHitZone({id, x, y, width: size, height: size, action});
  }

  private ellipsize(value: string, width: number): string {
    if (this.context.measureText(value).width <= width) return value;
    let result = value;
    while (result.length > 1 && this.context.measureText(`${result}…`).width > width) {
      result = result.slice(0, -1);
    }
    return `${result}…`;
  }

  private fractionForPointer(pointer: CanvasPointer): number {
    return THREE.MathUtils.clamp(
      (pointer.x - this.seekBounds.x) / this.seekBounds.width,
      0,
      1,
    );
  }

  protected override onCanvasPointerDown(pointer: CanvasPointer): boolean {
    const bounds = this.seekBounds;
    if (
      pointer.x >= bounds.x &&
      pointer.x <= bounds.x + bounds.width &&
      pointer.y >= bounds.y &&
      pointer.y <= bounds.y + bounds.height &&
      !this.getState().isLocked
    ) {
      this.seeking = true;
      this.seekPreview = this.fractionForPointer(pointer);
      this.actions.interact();
      this.redraw();
      return true;
    }
    this.actions.interact();
    return false;
  }

  protected override onCanvasPointerMove(pointer: CanvasPointer): boolean {
    if (!this.seeking) return false;
    this.seekPreview = this.fractionForPointer(pointer);
    this.actions.seekFraction(this.seekPreview);
    this.actions.interact();
    return true;
  }

  protected override onCanvasPointerUp(pointer: CanvasPointer | null): boolean {
    if (!this.seeking) return false;
    const fraction = pointer ? this.fractionForPointer(pointer) : this.seekPreview;
    if (fraction !== null) this.actions.seekFraction(fraction);
    this.seeking = false;
    this.seekPreview = null;
    this.actions.interact();
    this.redraw();
    return true;
  }
}

class OrbiterView extends CanvasView {
  private readonly getButtons: () => OrbiterButton[];
  private readonly horizontal: boolean;

  constructor(
    width: number,
    height: number,
    name: string,
    horizontal: boolean,
    getButtons: () => OrbiterButton[],
  ) {
    super(width, height, {name});
    this.getButtons = getButtons;
    this.horizontal = horizontal;
    this.userData.layout = name;
    this.redraw();
  }

  refresh() {
    this.redraw();
  }

  protected override draw() {
    const ctx = this.context;
    const buttons = this.getButtons();
    const padding = 12;
    const gap = 8;
    const majorSize = this.horizontal
      ? (this.logicalWidth - padding * 2 - gap * Math.max(0, buttons.length - 1)) / Math.max(1, buttons.length)
      : (this.logicalHeight - padding * 2 - gap * Math.max(0, buttons.length - 1)) / Math.max(1, buttons.length);
    buttons.forEach((button, index) => {
      const x = this.horizontal ? padding + index * (majorSize + gap) : padding;
      const y = this.horizontal ? padding : padding + index * (majorSize + gap);
      const width = this.horizontal ? majorSize : this.logicalWidth - padding * 2;
      const height = this.horizontal ? this.logicalHeight - padding * 2 : majorSize;
      const hovered = this.isHovered(button.id);
      const background = button.active
        ? 'rgba(79,195,247,0.23)'
        : hovered
          ? 'rgba(255,255,255,0.15)'
          : 'rgba(255,255,255,0.035)';
      fillRoundedRect(ctx, background, x, y, width, height, Math.min(36, height / 2));
      ctx.textAlign = 'center';
      ctx.fillStyle = button.disabled ? 'rgba(255,255,255,0.35)' : button.active ? ACCENT : ON_SURFACE;
      ctx.font = `650 ${Math.min(42, Math.max(24, height * 0.38))}px system-ui, sans-serif`;
      ctx.fillText(button.icon, x + width / 2, y + height * (button.label ? 0.48 : 0.62));
      if (button.label) {
        ctx.fillStyle = button.disabled ? 'rgba(255,255,255,0.30)' : MUTED;
        ctx.font = '600 14px system-ui, sans-serif';
        ctx.fillText(button.label, x + width / 2, y + height * 0.79);
      }
      this.addHitZone({
        id: button.id,
        x,
        y,
        width,
        height,
        disabled: button.disabled,
        action: button.action,
      });
    });
    this.userData.uiLabels = buttons.map((button) => button.label || button.icon);
  }
}

interface ScreenMovementDelegate {
  canMoveScreen: () => boolean;
  beginScreenMove: () => void;
  moveScreenAlongRay: (direction: THREE.Vector3) => void;
  endScreenMove: (didMove: boolean) => void;
  revealControls: (reason: string) => void;
}

/** Fixed-depth, translation-only cinema affordance matching Android XR. */
export class ScreenView extends xb.View {
  readonly movementPolicy = 'fixed-depth-translation';
  readonly grabMarginMeters = VIDEO_GRAB_MARGIN_METERS;
  private readonly delegate: ScreenMovementDelegate;
  private controller: THREE.Object3D | null = null;
  private startDirection = new THREE.Vector3();
  private didMove = false;

  constructor(delegate: ScreenMovementDelegate) {
    super({selectable: true, draggingMode: xb.DragMode.DO_NOT_DRAG});
    this.delegate = delegate;
    this.name = 'Cinema screen';
    this.userData.movementPolicy = this.movementPolicy;
    this.userData.grabMarginMeters = this.grabMarginMeters;
    this.userData.rotationLocked = true;
  }

  private rayDirection(controller: THREE.Object3D): THREE.Vector3 {
    const quaternion = controller.getWorldQuaternion(new THREE.Quaternion());
    return new THREE.Vector3(0, 0, -1).applyQuaternion(quaternion).normalize();
  }

  override onObjectSelectStart(event: xb.SelectEvent): boolean {
    if (!this.delegate.canMoveScreen()) {
      this.delegate.revealControls('screen-select');
      return true;
    }
    this.controller = event.target;
    this.startDirection.copy(this.rayDirection(event.target));
    this.didMove = false;
    this.delegate.beginScreenMove();
    return true;
  }

  override onObjectSelectEnd(_event: xb.SelectEvent): boolean {
    if (!this.controller) return true;
    this.delegate.endScreenMove(this.didMove);
    this.controller = null;
    this.didMove = false;
    return true;
  }

  override onTriggered() {
    // Select-end is handled above; do not interpret a completed screen move as
    // a second click.
  }

  override update() {
    if (!this.controller) return;
    const direction = this.rayDirection(this.controller);
    if (!this.didMove && direction.angleTo(this.startDirection) < 0.004) return;
    this.didMove = true;
    this.delegate.moveScreenAlongRay(direction);
  }

  /** Test/debug seam that exercises the same constrained placement policy. */
  moveAlongRayComponents(x: number, y: number, z: number) {
    this.delegate.moveScreenAlongRay(new THREE.Vector3(x, y, z).normalize());
  }
}

export class PlayerSpace extends xb.Script {
  private item: JellyfinItem;
  private readonly screenGroup: ScreenView;
  private videoElement: HTMLVideoElement | null = null;
  private subtitleCanvas: HTMLCanvasElement | null = null;
  private subtitleContainer: HTMLDivElement | null = null;
  private hls: Hls | null = null;
  private videoTexture: THREE.VideoTexture | null = null;
  private subtitleTexture: THREE.CanvasTexture | null = null;
  private transportPanel: xb.SpatialPanel | null = null;
  private stagePanel: xb.SpatialPanel | null = null;
  private trackPanel: xb.SpatialPanel | null = null;
  private sessionPanel: xb.SpatialPanel | null = null;
  private transportView: TransportView | null = null;
  private stageView: OrbiterView | null = null;
  private trackView: OrbiterView | null = null;
  private sessionView: OrbiterView | null = null;
  private theaterDome: THREE.Mesh | null = null;
  private mode: ProjectionMode = 'flat';
  private is3D = false;
  private isPlaying = false;
  private isLocked = false;
  private controlsVisible = true;
  private moveInProgress = false;
  private subtitlesVisible = true;
  private subtitleTracks: JellyfinSubtitleTrack[] = [];
  private subtitleFontUrls: string[] = [];
  private subtitleAudioStreams: JellyfinMediaStream[] = [];
  private defaultAudioStreamIndex: number | undefined;
  private selectedAudioStreamIndex: number | undefined;
  private selectedSubtitleIndex = -1;
  private subtitleRenderer: AnimeSubtitleRenderer | null = null;
  private subtitleAbortController: AbortController | null = null;
  private subtitleGeneration = 0;
  private forcedSubtitleTime: number | null = null;
  private readonly trackedTextTracks = new Set<TextTrack>();
  private theaterMode = false;
  private screenDepth = loadSavedPlayerDepth();
  private screenScale = loadSavedPlayerScale();
  private playbackSpeed = 1;
  private status = 'Preparing playback…';
  private controlsHideAt = performance.now() + CONTROLS_AUTO_HIDE_MS;
  private lastUiSecond = -1;
  private progressInterval: number | null = null;
  private aiResetTimeout: number | null = null;
  private playbackAbortController: AbortController | null = null;
  private audioSwitchAbortController: AbortController | null = null;
  private progressAbortController: AbortController | null = null;
  private audioSwitchGeneration = 0;
  private audioSwitchInProgress = false;
  private pendingPlaybackRestore: PlaybackRestoreState | null = null;
  private lifecycleGeneration = 0;
  private disposed = false;
  private mediaSourceId: string | undefined;
  private playSessionId: string | undefined;
  private audioContext: AudioContext | null = null;
  private audioSourceNode: MediaElementAudioSourceNode | null = null;
  private spatialPannerNodes: PannerNode[] = [];
  private playbackStartedReported = false;

  constructor(item: JellyfinItem) {
    super();
    this.item = item;
    this.name = `Player: ${item.Name}`;
    this.screenGroup = new ScreenView({
      canMoveScreen: () => this.canMoveScreen(),
      beginScreenMove: () => this.beginScreenMove(),
      moveScreenAlongRay: (direction) => this.moveScreenAlongRay(direction),
      endScreenMove: (didMove) => this.endScreenMove(didMove),
      revealControls: (reason) => this.revealControls(reason),
    });
  }

  override init(): void {
    this.disposed = false;
    const generation = ++this.lifecycleGeneration;
    try {
      this.createMediaElements();
      this.add(this.screenGroup);
      this.add(new THREE.HemisphereLight(0xffffff, 0x24303d, 1.8));
      this.createTexturesAndSubtitles();
      this.buildControls();
      this.rebuildScreen();
      this.bindVideoEvents();
      this.installAutomationHooks();
      this.progressInterval = window.setInterval(() => void this.reportProgress(), 10_000);

      syncPlayCoordinator.setHost({
        player: this.videoElement,
        currentItemId: this.item.Id,
        currentItemTitle: this.item.Name,
        replaceItems: () => {}, // WebXR single item context for now
        initializePlayer: (itemId, _startFromBeginning, autoPlay, startPositionTicks) => {
          this.showTransientStatus(`SyncPlay requested switch to item: ${itemId}`);
          void this.switchItem(itemId, autoPlay, startPositionTicks);
        }
      });
      syncPlayCoordinator.addStateListener(() => {
        this.sessionView?.refresh();
      });

      const ticks = this.item.UserData?.PlaybackPositionTicks ?? 0;
      this.pendingPlaybackRestore = {
        position: ticks / 10_000_000,
        paused: false,
        playbackRate: 1,
        nativeSubtitleTrack: -1,
        nativeSubtitleDisplay: false,
        subtitlesVisible: false,
        selectedSubtitleIndex: -1,
      };

      const playbackAbortController = new AbortController();
      this.playbackAbortController = playbackAbortController;
      void this.startPlayback(generation, playbackAbortController.signal)
        .catch((error: unknown) => {
          if (!this.isCurrentGeneration(generation)) return;
          console.error('Playback setup failed:', error);
          this.setStatus(error instanceof Error ? error.message : 'The stream could not be prepared');
          this.revealControls('playback-error');
        })
        .finally(() => {
          if (this.playbackAbortController === playbackAbortController) {
            this.playbackAbortController = null;
          }
        });
    } catch (error) {
      this.dispose();
      throw error;
    }
  }

  private async switchItem(itemId: string, autoPlay: boolean, startPositionTicks?: number) {
    try {
      this.setStatus('Loading stream…');
      this.refreshControls();

      const newItem = await fetchItem(itemId);
      this.item = newItem;
      this.name = `Player: ${newItem.Name}`;

      // Cleanup previous playback
      this.playbackAbortController?.abort();
      if (this.hls) {
        this.hls.destroy();
        this.hls = null;
      }
      this.mediaSourceId = undefined;
      this.playSessionId = undefined;
      this.trackedTextTracks.clear();
      if (this.videoElement) {
        this.videoElement.src = '';
        this.videoElement.removeAttribute('src');
      }

      const generation = ++this.lifecycleGeneration;

      syncPlayCoordinator.setHost({
        player: this.videoElement,
        currentItemId: this.item.Id,
        currentItemTitle: this.item.Name,
        replaceItems: () => {},
        initializePlayer: (itemId, _startFromBeginning, autoPlay, startPositionTicks) => {
          this.showTransientStatus(`SyncPlay requested switch to item: ${itemId}`);
          void this.switchItem(itemId, autoPlay, startPositionTicks);
        }
      });

      const ticks = startPositionTicks ?? this.item.UserData?.PlaybackPositionTicks ?? 0;
      this.pendingPlaybackRestore = {
        position: ticks / 10_000_000,
        paused: !autoPlay,
        playbackRate: 1,
        nativeSubtitleTrack: -1,
        nativeSubtitleDisplay: false,
        subtitlesVisible: this.subtitlesVisible,
        selectedSubtitleIndex: -1,
      };

      this.playbackAbortController = new AbortController();
      void this.startPlayback(generation, this.playbackAbortController.signal).catch(e => {
        if (!this.isCurrentGeneration(generation)) return;
        this.setStatus(e instanceof Error ? e.message : 'The stream could not be prepared');
        this.revealControls('playback-error');
      });
    } catch (e) {
      this.setStatus(`Error switching item: ${e instanceof Error ? e.message : String(e)}`);
      this.refreshControls();
    }
  }

  private createMediaElements() {
    const video = document.createElement('video');
    video.crossOrigin = 'anonymous';
    video.playsInline = true;
    video.preload = 'auto';
    video.hidden = true;
    document.body.appendChild(video);
    this.videoElement = video;

    const subtitleCanvas = document.createElement('canvas');
    subtitleCanvas.width = 2048;
    // Keep the subtitle raster at the same 16:9 aspect as the video surface.
    // The old 2:1 canvas stretched every ASS position and glyph vertically.
    subtitleCanvas.height = 1152;
    subtitleCanvas.hidden = true;
    const subtitleContainer = document.createElement('div');
    subtitleContainer.hidden = true;
    subtitleContainer.appendChild(subtitleCanvas);
    document.body.appendChild(subtitleContainer);
    this.subtitleCanvas = subtitleCanvas;
    this.subtitleContainer = subtitleContainer;
  }

  private installAutomationHooks() {
    if (!new URLSearchParams(window.location.search).has('xrAutomation')) return;
    // This hook is intentionally test-only: an empty mocked HLS playlist never
    // reaches `play`, so the browser harness needs a way to exercise the real
    // controller select-start/update/end movement path.
    this.userData.forcePlayingForAutomation = () => {
      this.isPlaying = true;
      this.setControlsVisible(false);
    };
    this.userData.selectSubtitleForAutomation = (index: number) => {
      void this.selectExternalSubtitle(index, false);
    };
    this.userData.setSubtitleTimeForAutomation = (seconds: number) => {
      this.forcedSubtitleTime = seconds;
      this.subtitleRenderer?.setCurrentTime(seconds);
      if (this.subtitleTexture) this.subtitleTexture.needsUpdate = true;
    };
    this.userData.subtitleRendererStateForAutomation = () => ({
      selectedIndex: this.selectedSubtitleIndex,
      trackCount: this.subtitleTracks.length,
      ready: this.subtitleRenderer?.ready ?? false,
      canvas: this.subtitleCanvas
        ? {width: this.subtitleCanvas.width, height: this.subtitleCanvas.height}
        : null,
    });
  }

  private createTexturesAndSubtitles() {
    if (!this.videoElement || !this.subtitleCanvas || !this.subtitleContainer) return;
    this.videoTexture = new THREE.VideoTexture(this.videoElement);
    this.videoTexture.colorSpace = THREE.SRGBColorSpace;
    this.subtitleTexture = new THREE.CanvasTexture(this.subtitleCanvas);
    this.subtitleTexture.colorSpace = THREE.SRGBColorSpace;
    // The overlay texture is kept alive even when no track is selected. A
    // libass worker is created only after Jellyfin returns a concrete raw
    // sidecar; native HLS text remains a compatibility fallback.
  }

  private setupWebAudioSpatialization() {
    if (this.audioContext || !this.videoElement) return;
    try {
      const AudioCtx = window.AudioContext || (window as unknown as {webkitAudioContext: typeof AudioContext}).webkitAudioContext;
      if (!AudioCtx) return;
      const ctx = new AudioCtx();
      const sourceNode = ctx.createMediaElementSource(this.videoElement);
      const channelCount = sourceNode.channelCount || 2;
      if (channelCount > 2 && 'createChannelSplitter' in ctx && 'createPanner' in ctx) {
        const splitter = ctx.createChannelSplitter(channelCount);
        sourceNode.connect(splitter);
        const speakerAngles = channelCount === 6
          ? [-30, 30, 0, 0, -110, 110]
          : [-30, 30, 0, 0, -90, 90, -150, 150];
        const distance = 3.0;
        for (let i = 0; i < channelCount; i++) {
          if (i === 3) {
            const lfeFilter = ctx.createBiquadFilter();
            lfeFilter.type = 'lowpass';
            lfeFilter.frequency.value = 120;
            splitter.connect(lfeFilter, i);
            lfeFilter.connect(ctx.destination);
            continue;
          }
          const angleRad = (speakerAngles[i] * Math.PI) / 180;
          const panner = ctx.createPanner();
          panner.panningModel = 'HRTF';
          panner.distanceModel = 'inverse';
          panner.positionX.value = Math.sin(angleRad) * distance;
          panner.positionY.value = 0;
          panner.positionZ.value = -Math.cos(angleRad) * distance;
          splitter.connect(panner, i);
          panner.connect(ctx.destination);
          this.spatialPannerNodes.push(panner);
        }
      } else {
        sourceNode.connect(ctx.destination);
      }
      this.audioContext = ctx;
      this.audioSourceNode = sourceNode;
    } catch {
      // AudioContext creation or media element routing deferred
    }
  }

  private readonly handleVideoPlay = () => {
    if (this.disposed) return;
    this.isPlaying = true;
    this.setupWebAudioSpatialization();
    if (this.audioContext && this.audioContext.state === 'suspended') {
      void this.audioContext.resume();
    }
    this.setStatus(`Spatial Playback · ${this.playbackSpeed.toFixed(2).replace(/\.00$/, '')}×`);
    this.controlsHideAt = performance.now() + CONTROLS_AUTO_HIDE_MS;
    this.subtitleRenderer?.setPaused(false, this.videoElement?.currentTime ?? 0);
    if (!this.playbackStartedReported) {
      this.playbackStartedReported = true;
      void this.reportPlaybackLifecycle('/Sessions/Playing');
    }
    syncPlayCoordinator.reportPlaybackEvent('Play', (this.videoElement?.currentTime ?? 0) * 10_000_000);
  };

  private readonly handleVideoPause = () => {
    if (this.disposed) return;
    this.isPlaying = false;
    this.setStatus('Paused');
    this.subtitleRenderer?.setPaused(true, this.videoElement?.currentTime ?? 0);
    this.revealControls('paused');
    void this.reportProgress(true);
    syncPlayCoordinator.reportPlaybackEvent('Pause', (this.videoElement?.currentTime ?? 0) * 10_000_000);
  };

  private readonly handleVideoError = () => {
    if (this.disposed) return;
    this.setStatus('Playback error');
    this.revealControls('error');
    console.error('Video element error:', this.videoElement?.error);
  };

  private readonly handleVideoSeeked = () => {
    if (this.disposed) return;
    syncPlayCoordinator.reportPlaybackEvent('Seek', (this.videoElement?.currentTime ?? 0) * 10_000_000);
  };

  private readonly handleLoadedMetadata = () => {
    this.setStatus('Ready');
    this.refreshControls();
    if (!this.restorePlaybackState()) void this.tryPlay();
  };

  private readonly handleTextTracksChanged = () => {
    this.syncNativeSubtitleTracks();
  };

  private readonly handleSubtitleCueChange = () => {
    this.renderNativeSubtitleCues();
  };

  private isCurrentGeneration(generation: number): boolean {
    return !this.disposed && generation === this.lifecycleGeneration;
  }

  private bindVideoEvents() {
    const video = this.videoElement;
    if (!video) return;
    video.addEventListener('play', this.handleVideoPlay);
    video.addEventListener('pause', this.handleVideoPause);
    video.addEventListener('seeked', this.handleVideoSeeked);
    video.addEventListener('error', this.handleVideoError);
    video.addEventListener('loadedmetadata', this.handleLoadedMetadata);
    video.textTracks.addEventListener('addtrack', this.handleTextTracksChanged);
    video.textTracks.addEventListener('change', this.handleTextTracksChanged);
  }

  private async startPlayback(generation: number, signal: AbortSignal): Promise<void> {
    this.setStatus('Loading stream…');
    
    // Check if local blob file or downloaded offline
    if (this.item.MediaSources?.[0]?.DirectStreamUrl?.startsWith('blob:')) {
      if (this.videoElement) {
        this.videoElement.src = this.item.MediaSources[0].DirectStreamUrl;
        this.setStatus('Ready (Local File)');
        this.refreshControls();
        void this.tryPlay();
        return;
      }
    }

    if (await offlineMediaRepository.isItemDownloaded(this.item.Id)) {
      const offlineUrl = await offlineMediaRepository.getMediaFileUrl(this.item.Id);
      if (offlineUrl && this.videoElement) {
        this.videoElement.src = offlineUrl;
        this.setStatus('Ready (Offline)');
        this.refreshControls();
        void this.tryPlay();
        return;
      }
    }

    const initialPlayback = await fetchPlaybackInfo(this.item.Id, signal);
    if (!this.isCurrentGeneration(generation)) return;
    const initialAudioStreamIndex = chooseInitialAudioStreamIndex(
      this.item,
      initialPlayback.subtitleTracks,
      initialPlayback.audioStreams,
      initialPlayback.defaultAudioStreamIndex,
    );
    let playback = initialPlayback;
    if (
      Number.isInteger(initialAudioStreamIndex) &&
      initialAudioStreamIndex !== initialPlayback.defaultAudioStreamIndex
    ) {
      playback = await fetchPlaybackInfo(this.item.Id, signal, {
        mediaSourceId: initialPlayback.mediaSourceId,
        audioStreamIndex: initialAudioStreamIndex,
      });
      if (!this.isCurrentGeneration(generation)) return;
    }
    const video = this.videoElement;
    if (!video) return;
    // A selected transcoding URL can expose only its active rendition. Keep
    // the complete source metadata from the first negotiation for XR cycling.
    this.subtitleTracks = initialPlayback.subtitleTracks.length > 0
      ? initialPlayback.subtitleTracks
      : playback.subtitleTracks;
    this.subtitleFontUrls = initialPlayback.fontUrls.length > 0
      ? initialPlayback.fontUrls
      : playback.fontUrls;
    this.subtitleAudioStreams = initialPlayback.audioStreams.length > 0
      ? initialPlayback.audioStreams
      : playback.audioStreams;
    this.defaultAudioStreamIndex = initialAudioStreamIndex
      ?? playback.defaultAudioStreamIndex;
    this.selectedAudioStreamIndex = this.defaultAudioStreamIndex
      ?? this.subtitleAudioStreams.find((stream) => stream.IsDefault)?.Index
      ?? this.subtitleAudioStreams[0]?.Index;
    if (this.subtitleTracks.length > 0) {
      const initial = chooseInitialSubtitleTrack(
        this.item,
        this.subtitleTracks,
        this.subtitleAudioStreams,
        this.defaultAudioStreamIndex,
      );
      this.subtitlesVisible = initial.index >= 0;
      this.updateSubtitleMeshVisibility();
      if (initial.index >= 0) void this.selectExternalSubtitle(initial.index, false);
    }

    this.attachPlaybackStream(playback, generation);
  }

  private attachPlaybackStream(
    playback: JellyfinPlaybackInfo,
    generation: number,
    restore: PlaybackRestoreState | null = null,
  ) {
    if (!this.isCurrentGeneration(generation)) return;
    const video = this.videoElement;
    if (!video) return;
    this.mediaSourceId = playback.mediaSourceId;
    this.playSessionId = playback.playSessionId;
    this.pendingPlaybackRestore = restore;
    if (restore) video.pause();
    this.hls?.destroy();
    this.hls = null;

    if (Hls.isSupported()) {
      const headers = new Headers(getAuthHeaders());
      headers.set('Accept', 'application/vnd.apple.mpegurl, application/x-mpegURL, video/mp2t, */*');
      new Headers(playback.requiredHeaders).forEach((value, name) => headers.set(name, value));
      const hls = streamingFetchSupported()
        ? new Hls({
            loader: FetchLoader,
            fetchSetup: (context, init) => {
              const requestHeaders = new Headers(init.headers);
              headers.forEach((value, name) => requestHeaders.set(name, value));
              return createJellyfinRequest(
                resolveJellyfinRequestUrl(context.url),
                {...init, headers: requestHeaders},
              );
            },
          })
        : new Hls({
            xhrSetup: (request, requestUrl) => {
              request.open('GET', resolveJellyfinRequestUrl(requestUrl), true);
              headers.forEach((value, name) => request.setRequestHeader(name, value));
            },
          });
      this.hls = hls;
      if (this.subtitleTracks.length > 0) {
        hls.subtitleTrack = -1;
        hls.subtitleDisplay = false;
      }
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        if (this.isCurrentGeneration(generation) && this.hls === hls) {
          if (restore && this.subtitleTracks.length === 0) {
            hls.subtitleTrack = restore.nativeSubtitleTrack;
            hls.subtitleDisplay = restore.nativeSubtitleDisplay;
            this.subtitlesVisible = restore.subtitlesVisible;
            this.updateSubtitleMeshVisibility();
          }
          this.syncNativeSubtitleTracks();
          this.refreshControls();
          if (!restore) void this.tryPlay();
        }
      });
      hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, () => {
        if (this.isCurrentGeneration(generation) && this.hls === hls) {
          this.syncNativeSubtitleTracks();
          this.refreshControls();
        }
      });
      hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, () => {
        if (this.isCurrentGeneration(generation) && this.hls === hls) this.refreshControls();
      });
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (data.fatal && this.isCurrentGeneration(generation) && this.hls === hls) {
          this.pendingPlaybackRestore = null;
          this.audioSwitchInProgress = false;
          this.setStatus('Stream unavailable');
          this.revealControls('hls-error');
        }
      });
      hls.loadSource(playback.streamUrl);
      hls.attachMedia(video);
      return;
    }
    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = mediaUrlWithAccessToken(playback.streamUrl);
      return;
    }
    this.pendingPlaybackRestore = null;
    this.audioSwitchInProgress = false;
    this.setStatus('HLS is not supported by this browser');
  }

  private restorePlaybackState(): boolean {
    const video = this.videoElement;
    const restore = this.pendingPlaybackRestore;
    if (!video || !restore || video.readyState < HTMLMediaElement.HAVE_METADATA) return false;
    this.pendingPlaybackRestore = null;
    video.playbackRate = restore.playbackRate;
    this.playbackSpeed = restore.playbackRate;
    if (Number.isFinite(restore.position)) {
      try {
        video.currentTime = restore.position;
      } catch {
        // Some native HLS implementations defer seeking until data is loaded.
      }
    }
    const position = video.currentTime;
    if (this.selectedSubtitleIndex !== restore.selectedSubtitleIndex) {
      void this.selectExternalSubtitle(restore.selectedSubtitleIndex, false);
    }
    this.subtitleRenderer?.setRate(restore.playbackRate);
    this.subtitleRenderer?.setCurrentTime(position);
    if (restore.paused) {
      video.pause();
      this.subtitleRenderer?.setPaused(true, position);
      this.isPlaying = false;
      this.setStatus('Paused');
    } else {
      this.subtitleRenderer?.setPaused(false, position);
      void this.tryPlay();
    }
    this.audioSwitchInProgress = false;
    this.refreshControls();
    return true;
  }

  private async tryPlay() {
    const video = this.videoElement;
    if (!video || this.disposed) return;
    try {
      await video.play();
    } catch {
      if (this.disposed) return;
      this.setStatus('Select Play to start');
      this.revealControls('autoplay-blocked');
    }
  }

  private rebuildScreen() {
    if (!this.videoTexture || !this.subtitleTexture || this.disposed) return;
    this.clearScreen();
    const baseGeometry = this.createProjectionGeometry();
    const leftScreen = new THREE.Mesh(
      baseGeometry.clone(),
      new THREE.MeshBasicMaterial({map: this.videoTexture, side: THREE.DoubleSide, toneMapped: false}),
    );
    const rightScreen = new THREE.Mesh(
      baseGeometry.clone(),
      new THREE.MeshBasicMaterial({map: this.videoTexture, side: THREE.DoubleSide, toneMapped: false}),
    );
    leftScreen.name = 'Left-eye video surface';
    rightScreen.name = 'Right-eye video surface';
    if (this.is3D) this.applySideBySideUVs(leftScreen, rightScreen);
    xb.showOnlyInLeftEye(leftScreen);
    xb.showOnlyInRightEye(rightScreen);
    this.screenGroup.add(leftScreen, rightScreen);

    const subtitleGeometry = baseGeometry.clone();
    subtitleGeometry.scale(0.999, 0.999, 0.999);
    const subtitleMaterial = () => new THREE.MeshBasicMaterial({
      map: this.subtitleTexture!,
      transparent: true,
      side: THREE.DoubleSide,
      depthWrite: false,
      toneMapped: false,
    });
    const leftSubtitles = new THREE.Mesh(subtitleGeometry.clone(), subtitleMaterial());
    const rightSubtitles = new THREE.Mesh(subtitleGeometry.clone(), subtitleMaterial());
    leftSubtitles.name = 'Left-eye subtitles';
    rightSubtitles.name = 'Right-eye subtitles';
    leftSubtitles.userData.subtitle = true;
    rightSubtitles.userData.subtitle = true;
    // The subtitle canvas is one full-frame overlay, not a packed SBS frame.
    // Cropping its UVs like the video made each eye see half of every caption.
    xb.showOnlyInLeftEye(leftSubtitles);
    xb.showOnlyInRightEye(rightSubtitles);
    leftSubtitles.visible = this.subtitlesVisible;
    rightSubtitles.visible = this.subtitlesVisible;
    this.screenGroup.add(leftSubtitles, rightSubtitles);

    if (this.mode === 'flat') {
      const affordance = new THREE.Mesh(
        new THREE.PlaneGeometry(
          VIDEO_WIDTH_METERS + VIDEO_GRAB_MARGIN_METERS * 2,
          VIDEO_HEIGHT_METERS + VIDEO_GRAB_MARGIN_METERS * 2,
        ),
        new THREE.MeshBasicMaterial({transparent: true, opacity: 0, depthWrite: false}),
      );
      affordance.name = 'Video move affordance';
      affordance.position.z = -0.015;
      affordance.userData.grabMarginMeters = VIDEO_GRAB_MARGIN_METERS;
      this.screenGroup.add(affordance);
    }
    baseGeometry.dispose();
    subtitleGeometry.dispose();
    this.applyProjectionPlacement();
    this.updateProjectedControls();
  }

  private clearScreen() {
    while (this.screenGroup.children.length > 0) {
      const child = this.screenGroup.children[0];
      this.screenGroup.remove(child);
      if (!(child instanceof THREE.Mesh)) continue;
      child.geometry.dispose();
      const materials = Array.isArray(child.material) ? child.material : [child.material];
      materials.forEach((material) => material.dispose());
    }
  }

  private createProjectionGeometry(): THREE.BufferGeometry {
    if (this.mode === 'flat') return new THREE.PlaneGeometry(VIDEO_WIDTH_METERS, VIDEO_HEIGHT_METERS);
    const radius = 50;
    if (this.mode === '180') {
      const geometry = new THREE.SphereGeometry(radius, 96, 64, Math.PI, Math.PI, 0, Math.PI);
      geometry.scale(-1, 1, 1);
      return geometry;
    }
    const geometry = new THREE.SphereGeometry(radius, 96, 64);
    geometry.scale(-1, 1, 1);
    return geometry;
  }

  private applyProjectionPlacement() {
    if (this.mode === 'flat') {
      this.screenGroup.position.set(0, xb.user.height, -this.screenDepth);
      this.screenGroup.quaternion.identity();
      this.screenGroup.scale.setScalar(this.screenScale);
    } else {
      this.screenGroup.position.set(0, xb.user.height, 0);
      this.screenGroup.quaternion.identity();
      this.screenGroup.scale.setScalar(1);
      this.moveInProgress = false;
    }
    this.screenGroup.userData.projection = this.mode;
    this.screenGroup.userData.movable = this.mode === 'flat';
    this.screenGroup.userData.scale = this.screenScale;
  }

  private applySideBySideUVs(left: THREE.Mesh, right: THREE.Mesh) {
    const leftUVs = left.geometry.attributes.uv as THREE.BufferAttribute;
    for (let index = 0; index < leftUVs.count; index++) leftUVs.setX(index, leftUVs.getX(index) * 0.5);
    leftUVs.needsUpdate = true;
    const rightUVs = right.geometry.attributes.uv as THREE.BufferAttribute;
    for (let index = 0; index < rightUVs.count; index++) rightUVs.setX(index, rightUVs.getX(index) * 0.5 + 0.5);
    rightUVs.needsUpdate = true;
  }

  private buildControls() {
    this.transportView = new TransportView(
      () => this.transportState(),
      {
        back: () => this.exitPlayer(),
        togglePlayback: () => this.togglePlayback(),
        seekBy: (seconds) => this.seekBy(seconds),
        seekFraction: (fraction) => this.seekFraction(fraction),
        chapters: () => this.showTransientStatus('No chapter markers were returned for this stream.'),
        interact: () => this.revealControls('transport-input'),
      },
    );
    this.transportPanel = this.createGlassPanel(
      'Playback controls',
      1.8,
      0.8,
      this.transportView,
    );

    this.stageView = new OrbiterView(720, 128, 'Stage controls orbiter', true, () => this.stageButtons());
    this.stagePanel = this.createGlassPanel('Stage controls', 0.72, 0.128, this.stageView);

    this.trackView = new OrbiterView(144, 620, 'Track options orbiter', false, () => this.trackButtons());
    this.trackPanel = this.createGlassPanel('Track options', 0.144, 0.62, this.trackView);

    this.sessionView = new OrbiterView(144, 510, 'Session orbiter', false, () => this.sessionButtons());
    this.sessionPanel = this.createGlassPanel('Session controls', 0.144, 0.51, this.sessionView);
    this.updateProjectedControls();
    this.setControlsVisible(true);
  }

  private createGlassPanel(name: string, width: number, height: number, view: CanvasView): xb.SpatialPanel {
    const panel = new xb.SpatialPanel({
      width,
      height,
      backgroundColor: GLASS,
      draggable: false,
      useBorderlessShader: true,
      showHighlights: false,
      borderWidth: 0,
    });
    panel.name = name;
    panel.userData.androidXrChrome = true;
    panel.add(view);
    panel.updateLayouts();
    this.add(panel);
    return panel;
  }

  private transportState(): TransportState {
    const video = this.videoElement;
    return {
      title: this.item.Name,
      status: this.status,
      isPlaying: this.isPlaying,
      isLocked: this.isLocked,
      position: video?.currentTime ?? 0,
      duration: Number.isFinite(video?.duration) ? video!.duration : 0,
    };
  }

  private stageButtons(): OrbiterButton[] {
    if (this.isLocked) {
      return [{
        id: 'unlock', icon: '🔒', label: 'Unlock', active: true,
        action: () => { this.isLocked = false; this.revealControls('unlock'); this.refreshControls(); },
      }];
    }
    if (this.mode !== 'flat') {
      return [
        {
          id: 'recenter', icon: '↺', label: 'Recenter',
          action: () => {
            this.applyProjectionPlacement();
            this.updateProjectedControls();
            this.showTransientStatus('Immersive view recentered');
            this.refreshControls();
          },
        },
        {id: 'theater', icon: this.theaterMode ? '◉' : '◎', label: this.theaterMode ? 'Theater' : 'Passthrough', active: this.theaterMode, action: () => this.toggleTheater()},
        {id: 'lock', icon: '🔓', label: 'Lock', action: () => { this.isLocked = true; this.revealControls('lock'); this.refreshControls(); }},
      ];
    }
    const percentage = Math.round(this.screenScale / DEFAULT_VIDEO_SCALE * 100);
    return [
      {id: 'smaller', icon: '−', label: 'Smaller', disabled: this.screenScale <= MIN_VIDEO_SCALE, action: () => this.changeScreenScale(-0.08)},
      {id: 'reset-size', icon: `${percentage}%`, label: 'Reset', action: () => this.resetScreenPlacement()},
      {id: 'bigger', icon: '+', label: 'Bigger', disabled: this.screenScale >= MAX_VIDEO_SCALE, action: () => this.changeScreenScale(0.08)},
      {id: 'theater', icon: this.theaterMode ? '◉' : '◎', label: this.theaterMode ? 'Theater' : 'Passthrough', active: this.theaterMode, action: () => this.toggleTheater()},
      {id: 'lock', icon: '🔓', label: 'Lock', action: () => { this.isLocked = true; this.revealControls('lock'); this.refreshControls(); }},
    ];
  }

  private trackButtons(): OrbiterButton[] {
    const nativeSubtitleTracks = this.hls?.subtitleTracks ?? [];
    const currentLevel = this.hls?.currentLevel ?? -1;
    const levels = this.hls?.levels ?? [];
    const currentAudioStream = this.currentAudioStream();
    const audioName = currentAudioStream
      ? this.audioStreamLabel(currentAudioStream)
      : this.hls?.audioTracks[this.hls.audioTrack]?.name
        || this.hls?.audioTracks[this.hls.audioTrack]?.lang
        || 'Audio';
    const audioLabel = this.videoElement?.muted ? `Muted · ${audioName}` : audioName;
    const selectedStyledTrack = this.subtitleTracks[this.selectedSubtitleIndex];
    const subtitleLabel = this.subtitleTracks.length > 0
      ? selectedStyledTrack
        ? `${selectedStyledTrack.label} · ${selectedStyledTrack.codec.toUpperCase()}`
        : 'Subtitles off'
      : nativeSubtitleTracks.length > 0
        ? this.hls?.subtitleTrack === -1
          ? 'Subtitles off'
          : `Sub ${Math.max(1, (this.hls?.subtitleTrack ?? 0) + 1)}/${nativeSubtitleTracks.length}`
        : 'Subtitles';
    const qualityLabel = currentLevel < 0
      ? 'Auto'
      : `${levels[currentLevel]?.height ?? 'HD'}p`;
    return [
      {id: 'subtitles', icon: 'CC', label: subtitleLabel, active: this.subtitlesVisible, action: () => this.cycleSubtitleTrack()},
      {id: 'audio', icon: this.videoElement?.muted ? '×♪' : '♪', label: audioLabel, active: !this.videoElement?.muted, disabled: this.audioSwitchInProgress, action: () => this.cycleAudioTrack()},
      {id: 'quality', icon: 'HD', label: qualityLabel, action: () => this.cycleQuality()},
      {id: 'speed', icon: `${this.playbackSpeed.toFixed(2).replace(/\.00$/, '')}×`, label: 'Speed', active: this.playbackSpeed !== 1, action: () => this.cycleSpeed()},
      {id: 'projection', icon: this.mode === 'flat' ? '▭' : this.mode === '180' ? '◒' : '◉', label: this.mode === 'flat' ? 'Flat' : `${this.mode}°`, active: this.mode !== 'flat', action: () => this.cycleProjection()},
      {id: 'stereo', icon: this.is3D ? '3D' : '2D', label: 'Format', active: this.is3D, action: () => this.toggleStereo()},
    ];
  }

  private sessionButtons(): OrbiterButton[] {
    const isSyncActive = syncPlayCoordinator.isActive();
    return [
      {id: 'cast', icon: '▧', label: 'Cast', action: () => this.showTransientStatus('Cast controls are available in native SpatialFin.')},
      {
        id: 'syncplay',
        icon: isSyncActive ? '⌁ (Active)' : '⌁',
        label: isSyncActive ? 'Leave Group' : 'Create Group',
        active: isSyncActive,
        action: () => {
          if (isSyncActive) void syncPlayCoordinator.leaveGroup();
          else void syncPlayCoordinator.createGroup();
        }
      },
      {id: 'info', icon: 'ⓘ', label: 'Info', action: () => this.showTransientStatus(this.item.Overview?.slice(0, 120) || this.item.Name)},
      {id: 'voice', icon: '●', label: xb.ai.isAvailable() ? 'Ask AI' : 'Voice', disabled: !xb.ai.isAvailable(), action: () => void this.askAi()},
    ];
  }

  private refreshControls() {
    this.transportView?.refresh();
    this.stageView?.refresh();
    this.trackView?.refresh();
    this.sessionView?.refresh();
    this.updateControlsVisibility();
  }

  private updateProjectedControls() {
    const cameraOrigin = new THREE.Vector3(0, xb.user.height, 0);
    let direction = new THREE.Vector3(0, 0, -1);
    if (this.mode === 'flat') {
      direction.copy(this.screenGroup.position).sub(cameraOrigin);
      if (direction.lengthSq() < 1e-5) direction.set(0, 0, -1);
      direction.normalize();
    }
    const anchor = cameraOrigin.clone().addScaledVector(direction, UI_DEPTH_METERS);
    const mainY = anchor.y - 0.93;
    this.transportPanel?.position.set(anchor.x, mainY, anchor.z + 0.03);
    this.transportPanel?.quaternion.identity();
    this.stagePanel?.position.set(anchor.x, mainY + 0.51, anchor.z + 0.01);
    this.stagePanel?.quaternion.identity();
    this.trackPanel?.position.set(anchor.x - 1.06, mainY, anchor.z + 0.015);
    this.trackPanel?.quaternion.identity();
    this.sessionPanel?.position.set(anchor.x + 1.06, mainY, anchor.z + 0.015);
    this.sessionPanel?.quaternion.identity();
    for (const panel of [this.transportPanel, this.stagePanel, this.trackPanel, this.sessionPanel]) {
      if (panel) panel.userData.projectedFromVideo = {x: this.screenGroup.position.x, y: this.screenGroup.position.y, z: this.screenGroup.position.z};
    }
  }

  private canMoveScreen(): boolean {
    return this.mode === 'flat' && !this.isLocked && (!this.controlsVisible || this.moveInProgress) && this.isPlaying;
  }

  private beginScreenMove() {
    if (!this.canMoveScreen()) return;
    const origin = new THREE.Vector3(0, xb.user.height, 0);
    this.screenDepth = THREE.MathUtils.clamp(this.screenGroup.position.distanceTo(origin), 0.75, 15);
    this.moveInProgress = true;
    this.screenGroup.userData.moveInProgress = true;
  }

  private moveScreenAlongRay(direction: THREE.Vector3) {
    if (this.mode !== 'flat') return;
    const normalized = direction.clone().normalize();
    const origin = new THREE.Vector3(0, xb.user.height, 0);
    this.screenDepth = THREE.MathUtils.clamp(this.screenGroup.position.distanceTo(origin), 0.75, 15);
    savePlayerDepth(this.screenDepth);
    this.screenGroup.position.copy(origin.addScaledVector(normalized, this.screenDepth));
    this.screenGroup.quaternion.identity();
    this.screenGroup.scale.setScalar(this.screenScale);
    this.screenGroup.userData.lastMoveDirection = normalized.toArray();
    this.screenGroup.userData.depthMeters = this.screenDepth;
    if (this.moveInProgress && !this.controlsVisible) this.setControlsVisible(true);
    this.updateProjectedControls();
  }

  private endScreenMove(didMove: boolean) {
    this.moveInProgress = false;
    this.screenGroup.userData.moveInProgress = false;
    if (!didMove) this.revealControls('screen-tap');
    else {
      this.revealControls('screen-move-end');
      this.updateProjectedControls();
    }
  }

  private resetScreenPlacement() {
    this.screenScale = DEFAULT_VIDEO_SCALE;
    this.screenDepth = VIDEO_DEPTH_METERS;
    savePlayerScale(this.screenScale);
    savePlayerDepth(this.screenDepth);
    if (this.mode === 'flat') {
      this.screenGroup.position.set(0, xb.user.height, -VIDEO_DEPTH_METERS);
      this.screenGroup.quaternion.identity();
      this.screenGroup.scale.setScalar(this.screenScale);
    }
    this.updateProjectedControls();
    this.revealControls('reset-screen');
    this.refreshControls();
  }

  private changeScreenScale(delta: number) {
    this.screenScale = THREE.MathUtils.clamp(this.screenScale + delta, MIN_VIDEO_SCALE, MAX_VIDEO_SCALE);
    savePlayerScale(this.screenScale);
    if (this.mode === 'flat') this.screenGroup.scale.setScalar(this.screenScale);
    this.screenGroup.userData.scale = this.screenScale;
    this.revealControls('resize-screen');
    this.refreshControls();
  }

  private cycleProjection() {
    const modes: ProjectionMode[] = ['flat', '180', '360'];
    this.mode = modes[(modes.indexOf(this.mode) + 1) % modes.length];
    this.rebuildScreen();
    this.showTransientStatus(this.mode === 'flat' ? 'Projection · Flat screen' : `Projection · ${this.mode}° immersive`);
    this.refreshControls();
  }

  private toggleStereo() {
    this.is3D = !this.is3D;
    this.rebuildScreen();
    this.showTransientStatus(this.is3D ? 'Video format · 3D side-by-side' : 'Video format · 2D');
    this.refreshControls();
  }

  private toggleSubtitles() {
    this.subtitlesVisible = !this.subtitlesVisible;
    this.updateSubtitleMeshVisibility();
    if (!this.subtitlesVisible) this.clearSubtitleCanvas();
    this.showTransientStatus(this.subtitlesVisible ? 'Subtitles on' : 'Subtitles off');
    this.refreshControls();
  }

  private cycleSubtitleTrack() {
    if (this.subtitleTracks.length > 0) {
      const next = this.selectedSubtitleIndex >= this.subtitleTracks.length - 1
        ? -1
        : this.selectedSubtitleIndex + 1;
      void this.selectExternalSubtitle(next, true);
      return;
    }
    const hls = this.hls;
    const tracks = hls?.subtitleTracks ?? [];
    if (!hls || tracks.length === 0) {
      this.toggleSubtitles();
      return;
    }
    const next = hls.subtitleTrack >= tracks.length - 1 ? -1 : hls.subtitleTrack + 1;
    hls.subtitleTrack = next;
    hls.subtitleDisplay = next >= 0;
    this.subtitlesVisible = next >= 0;
    this.updateSubtitleMeshVisibility();
    this.clearSubtitleCanvas();
    this.showTransientStatus(next >= 0 ? `Subtitle track · ${tracks[next].name || tracks[next].lang || next + 1}` : 'Subtitles off');
    this.refreshControls();
  }

  private async selectExternalSubtitle(index: number, remember: boolean) {
    const track = this.subtitleTracks[index] ?? null;
    const generation = ++this.subtitleGeneration;
    this.subtitleAbortController?.abort();
    this.subtitleAbortController = null;
    this.subtitleRenderer?.dispose();
    this.subtitleRenderer = null;
    this.selectedSubtitleIndex = track ? index : -1;
    this.subtitlesVisible = Boolean(track);
    this.updateSubtitleMeshVisibility();
    this.clearSubtitleCanvas();
    if (!track) {
      if (remember) rememberSubtitleSelection(this.item, null);
      this.showTransientStatus('Subtitles off');
      this.refreshControls();
      window.requestAnimationFrame(() => {
        if (generation === this.subtitleGeneration && this.selectedSubtitleIndex === -1) {
          this.clearSubtitleCanvas();
        }
      });
      return;
    }

    this.showTransientStatus(`Loading subtitles · ${track.label}`);
    this.refreshControls();
    const controller = new AbortController();
    this.subtitleAbortController = controller;
    try {
      const renderer = await AnimeSubtitleRenderer.create({
        track,
        fontUrls: this.subtitleFontUrls,
        canvas: this.subtitleCanvas ?? undefined,
        signal: controller.signal,
        onReady: () => {
          if (generation !== this.subtitleGeneration) return;
          this.showTransientStatus(`Subtitles · ${track.label}`);
          this.refreshControls();
        },
        onError: (error) => {
          if (generation !== this.subtitleGeneration) return;
          console.error('Styled subtitle renderer failed:', error);
          this.subtitleRenderer = null;
          this.selectedSubtitleIndex = -1;
          this.subtitlesVisible = false;
          this.updateSubtitleMeshVisibility();
          this.setStatus('Styled subtitle renderer unavailable');
          this.clearSubtitleCanvas();
          this.revealControls('subtitle-error');
        },
      });
      if (controller.signal.aborted || generation !== this.subtitleGeneration) {
        renderer.dispose();
        return;
      }
      this.subtitleRenderer = renderer;
      if (remember) rememberSubtitleSelection(this.item, track);
      renderer.resize(this.subtitleCanvas?.width ?? 2048, this.subtitleCanvas?.height ?? 1152);
      renderer.setRate(this.videoElement?.playbackRate ?? 1);
      renderer.setPaused(this.videoElement?.paused ?? true, this.videoElement?.currentTime ?? 0);
    } catch (error) {
      if (controller.signal.aborted || generation !== this.subtitleGeneration) return;
      console.error('Could not load styled subtitles:', error);
      this.selectedSubtitleIndex = -1;
      this.subtitlesVisible = false;
      this.updateSubtitleMeshVisibility();
      this.clearSubtitleCanvas();
      this.setStatus(error instanceof Error ? error.message : 'Could not load subtitles');
      this.revealControls('subtitle-load-error');
      this.refreshControls();
    } finally {
      if (this.subtitleAbortController === controller) this.subtitleAbortController = null;
    }
  }

  private cycleAudioTrack() {
    if (this.audioSwitchInProgress) return;
    const streams = this.subtitleAudioStreams.filter((stream) =>
      Number.isInteger(stream.Index));
    if (streams.length <= 1) {
      const current = this.currentAudioStream() ?? streams[0];
      this.showTransientStatus(current
        ? `Audio track · ${this.audioStreamLabel(current)}`
        : 'No alternate audio tracks');
      return;
    }
    const current = streams.findIndex((stream) =>
      stream.Index === this.selectedAudioStreamIndex);
    const next = streams[(current + 1) % streams.length];
    void this.switchAudioStream(next);
  }

  private currentAudioStream(): JellyfinMediaStream | undefined {
    return this.subtitleAudioStreams.find((stream) =>
      stream.Index === this.selectedAudioStreamIndex)
      ?? this.subtitleAudioStreams.find((stream) =>
        stream.Index === this.defaultAudioStreamIndex)
      ?? this.subtitleAudioStreams.find((stream) => stream.IsDefault)
      ?? this.subtitleAudioStreams[0];
  }

  private audioStreamLabel(stream: JellyfinMediaStream): string {
    const title = stream.DisplayTitle?.trim() || stream.Title?.trim();
    const language = stream.Language?.trim();
    if (title && language) return `${title} · ${language}`;
    return title || language || stream.Codec?.toUpperCase() || 'Audio';
  }

  private async switchAudioStream(stream: JellyfinMediaStream) {
    if (!Number.isInteger(stream.Index)) return;
    const video = this.videoElement;
    if (!video) return;
    const lifecycleGeneration = this.lifecycleGeneration;
    const switchGeneration = ++this.audioSwitchGeneration;
    this.audioSwitchAbortController?.abort();
    const controller = new AbortController();
    this.audioSwitchAbortController = controller;
    this.audioSwitchInProgress = true;
    const restore: PlaybackRestoreState = {
      position: video.currentTime,
      paused: video.paused,
      playbackRate: video.playbackRate,
      nativeSubtitleTrack: this.hls?.subtitleTrack ?? -1,
      nativeSubtitleDisplay: this.hls?.subtitleDisplay ?? false,
      subtitlesVisible: this.subtitlesVisible,
      selectedSubtitleIndex: this.selectedSubtitleIndex,
    };
    this.showTransientStatus(`Switching audio · ${this.audioStreamLabel(stream)}`);
    this.refreshControls();
    let attached = false;
    try {
      const playback = await fetchPlaybackInfo(this.item.Id, controller.signal, {
        mediaSourceId: this.mediaSourceId,
        audioStreamIndex: stream.Index,
      });
      if (
        controller.signal.aborted ||
        switchGeneration !== this.audioSwitchGeneration ||
        !this.isCurrentGeneration(lifecycleGeneration)
      ) return;
      this.selectedAudioStreamIndex = stream.Index;
      this.defaultAudioStreamIndex = stream.Index;
      this.attachPlaybackStream(playback, lifecycleGeneration, restore);
      attached = true;
      rememberAudioSelection(this.item, stream);
      this.showTransientStatus(`Audio track · ${this.audioStreamLabel(stream)}`);
    } catch (error) {
      if (
        controller.signal.aborted ||
        switchGeneration !== this.audioSwitchGeneration ||
        !this.isCurrentGeneration(lifecycleGeneration)
      ) return;
      console.error('Could not switch audio track:', error);
      this.setStatus(error instanceof Error ? error.message : 'Could not switch audio track');
      this.revealControls('audio-switch-error');
    } finally {
      if (this.audioSwitchAbortController === controller) {
        this.audioSwitchAbortController = null;
        if (!attached) this.audioSwitchInProgress = false;
        this.refreshControls();
      }
    }
  }

  private cycleQuality() {
    const hls = this.hls;
    const levels = hls?.levels ?? [];
    if (!hls || levels.length === 0) {
      this.showTransientStatus('Streaming quality · Auto');
      return;
    }
    const next = hls.currentLevel >= levels.length - 1 ? -1 : hls.currentLevel + 1;
    hls.currentLevel = next;
    this.showTransientStatus(next < 0 ? 'Streaming quality · Auto' : `Streaming quality · ${levels[next].height ?? 'HD'}p`);
    this.refreshControls();
  }

  private updateSubtitleMeshVisibility() {
    this.screenGroup.traverse((child) => {
      if (child.userData.subtitle) child.visible = this.subtitlesVisible;
    });
  }

  private syncNativeSubtitleTracks() {
    const video = this.videoElement;
    if (!video) return;
    for (const track of Array.from(video.textTracks)) {
      if (this.trackedTextTracks.has(track)) continue;
      this.trackedTextTracks.add(track);
      track.mode = 'hidden';
      track.addEventListener('cuechange', this.handleSubtitleCueChange);
    }
    this.renderNativeSubtitleCues();
  }

  private clearSubtitleCanvas() {
    const canvas = this.subtitleCanvas;
    const context = canvas?.getContext('2d');
    if (!canvas || !context) return;
    context.clearRect(0, 0, canvas.width, canvas.height);
    if (this.subtitleTexture) this.subtitleTexture.needsUpdate = true;
  }

  private renderNativeSubtitleCues() {
    const canvas = this.subtitleCanvas;
    const context = canvas?.getContext('2d');
    if (!canvas || !context) return;
    if (this.selectedSubtitleIndex >= 0) return;
    context.clearRect(0, 0, canvas.width, canvas.height);
    if (!this.subtitlesVisible) {
      if (this.subtitleTexture) this.subtitleTexture.needsUpdate = true;
      return;
    }
    const activeCues = Array.from(this.videoElement?.textTracks ?? [])
      .flatMap((track) => Array.from(track.activeCues ?? []))
      .map((cue) => (cue as TextTrackCue & {text?: string}).text)
      .filter((text): text is string => Boolean(text?.trim()));
    if (activeCues.length === 0) {
      if (this.subtitleTexture) this.subtitleTexture.needsUpdate = true;
      return;
    }
    context.textAlign = 'center';
    context.textBaseline = 'bottom';
    context.font = '600 64px system-ui, sans-serif';
    context.lineJoin = 'round';
    const lines = activeCues.join('\n').split(/\r?\n/).slice(-3);
    lines.reverse().forEach((line, index) => {
      const y = canvas.height - 72 - index * 84;
      context.lineWidth = 12;
      context.strokeStyle = 'rgba(0,0,0,0.82)';
      context.strokeText(line, canvas.width / 2, y);
      context.fillStyle = '#ffffff';
      context.fillText(line, canvas.width / 2, y);
    });
    if (this.subtitleTexture) this.subtitleTexture.needsUpdate = true;
  }

  private cycleSpeed() {
    const speeds = [0.75, 1, 1.25, 1.5, 2];
    this.playbackSpeed = speeds[(speeds.indexOf(this.playbackSpeed) + 1) % speeds.length];
    if (this.videoElement) this.videoElement.playbackRate = this.playbackSpeed;
    this.subtitleRenderer?.setRate(this.playbackSpeed);
    this.showTransientStatus(`Playback speed · ${this.playbackSpeed}×`);
    this.refreshControls();
  }

  private toggleTheater() {
    this.theaterMode = !this.theaterMode;
    if (this.theaterMode && !this.theaterDome) {
      this.theaterDome = new THREE.Mesh(
        new THREE.SphereGeometry(80, 32, 16),
        new THREE.MeshBasicMaterial({color: 0x000000, side: THREE.BackSide}),
      );
      this.theaterDome.name = 'Theater blackout environment';
      this.theaterDome.position.set(0, xb.user.height, 0);
      this.theaterDome.renderOrder = -10;
      this.add(this.theaterDome);
    }
    if (this.theaterDome) this.theaterDome.visible = this.theaterMode;
    this.showTransientStatus(this.theaterMode ? 'Theater mode' : 'Passthrough environment');
    this.refreshControls();
  }

  private togglePlayback() {
    const video = this.videoElement;
    if (!video) return;
    if (video.paused) void this.tryPlay();
    else video.pause();
    this.revealControls('playback-toggle');
  }

  private seekBy(seconds: number) {
    const video = this.videoElement;
    if (!video || !Number.isFinite(video.duration)) return;
    video.currentTime = THREE.MathUtils.clamp(video.currentTime + seconds, 0, video.duration);
    this.revealControls('seek');
    this.refreshControls();
  }

  private seekFraction(fraction: number) {
    const video = this.videoElement;
    if (!video || !Number.isFinite(video.duration)) return;
    video.currentTime = THREE.MathUtils.clamp(fraction, 0, 1) * video.duration;
    this.controlsHideAt = performance.now() + CONTROLS_AUTO_HIDE_MS;
  }

  private revealControls(_reason: string) {
    this.controlsHideAt = performance.now() + CONTROLS_AUTO_HIDE_MS;
    this.setControlsVisible(true);
  }

  private setControlsVisible(visible: boolean) {
    this.controlsVisible = visible;
    this.updateControlsVisibility();
  }

  private updateControlsVisibility() {
    const mainVisible = this.controlsVisible || !this.isPlaying;
    if (this.transportPanel) this.transportPanel.visible = mainVisible;
    if (this.stagePanel) this.stagePanel.visible = mainVisible;
    const secondaryVisible = mainVisible && !this.isLocked;
    if (this.trackPanel) this.trackPanel.visible = secondaryVisible;
    if (this.sessionPanel) this.sessionPanel.visible = secondaryVisible;
    this.screenGroup.userData.movementEnabled = this.canMoveScreen();
  }

  private setStatus(status: string) {
    this.status = status;
    this.refreshControls();
  }

  private showTransientStatus(status: string) {
    this.setStatus(status);
    this.revealControls('status');
    if (this.aiResetTimeout !== null) window.clearTimeout(this.aiResetTimeout);
    this.aiResetTimeout = window.setTimeout(() => {
      if (this.disposed) return;
      this.status = this.isPlaying ? 'Spatial Playback' : 'Paused';
      this.aiResetTimeout = null;
      this.refreshControls();
    }, 4_000);
  }

  private exitPlayer() {
    this.videoElement?.pause();
    this.removeFromParent();
    xb.add(new HomeSpace());
  }

  private async askAi() {
    if (!xb.ai.isAvailable()) return;
    const generation = this.lifecycleGeneration;
    this.setStatus('AI · Thinking…');
    try {
      const response = await xb.ai.query({
        type: 'multiPart',
        parts: [
          {inlineData: this.captureCurrentVideoFrame()},
          {text: 'Describe what is happening in this movie frame in two short sentences.'},
        ],
      });
      if (!this.isCurrentGeneration(generation)) return;
      this.showTransientStatus(typeof response === 'string' ? response : response?.text || 'No answer');
    } catch (error) {
      if (!this.isCurrentGeneration(generation)) return;
      console.error('AI request failed:', error);
      this.showTransientStatus('AI request failed');
    }
  }

  private captureCurrentVideoFrame(): {data: string; mimeType: string} {
    const video = this.videoElement;
    if (!video || video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA || video.videoWidth === 0 || video.videoHeight === 0) {
      throw new Error('No movie frame is available yet.');
    }
    const maxWidth = 1280;
    const scale = Math.min(1, maxWidth / video.videoWidth);
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(video.videoWidth * scale));
    canvas.height = Math.max(1, Math.round(video.videoHeight * scale));
    const context = canvas.getContext('2d');
    if (!context) throw new Error('The movie frame could not be captured.');
    context.drawImage(video, 0, 0, canvas.width, canvas.height);
    const mimeType = 'image/jpeg';
    const dataUrl = canvas.toDataURL(mimeType, 0.85);
    const separator = dataUrl.indexOf(',');
    if (separator < 0) throw new Error('The movie frame could not be encoded.');
    return {data: dataUrl.slice(separator + 1), mimeType};
  }

  private playbackBody(isPaused = this.videoElement?.paused ?? true) {
    const video = this.videoElement;
    return {
      ItemId: this.item.Id,
      MediaSourceId: this.mediaSourceId,
      PlaySessionId: this.playSessionId,
      PositionTicks: Math.floor((video?.currentTime ?? 0) * 10_000_000),
      IsPaused: isPaused,
      IsMuted: video?.muted ?? false,
      VolumeLevel: Math.round((video?.volume ?? 1) * 100),
      CanSeek: (video?.seekable.length ?? 0) > 0,
      PlaybackRate: video?.playbackRate ?? 1,
    };
  }

  private async reportPlaybackLifecycle(path: string, keepalive = false) {
    const server = getServerUrl();
    if (!server) return;
    try {
      await fetchJellyfin(`${server}${path}`, {
        method: 'POST',
        headers: getAuthHeaders(true),
        body: JSON.stringify(this.playbackBody()),
        keepalive,
      });
    } catch (error) {
      if (!this.disposed) console.warn(`Playback lifecycle reporting failed for ${path}:`, error);
    }
  }

  private async reportProgress(isPaused = false) {
    const video = this.videoElement;
    if (!video || this.disposed || this.progressAbortController || (!isPaused && video.paused)) return;
    const server = getServerUrl();
    if (!server) return;
    const controller = new AbortController();
    this.progressAbortController = controller;
    try {
      await fetchJellyfin(`${server}/Sessions/Playing/Progress`, {
        method: 'POST',
        headers: getAuthHeaders(true),
        signal: controller.signal,
        body: JSON.stringify(this.playbackBody(isPaused)),
      });
    } catch (error) {
      if (!controller.signal.aborted) console.warn('Playback progress reporting failed:', error);
    } finally {
      if (this.progressAbortController === controller) this.progressAbortController = null;
    }
  }

  override update() {
    if (this.subtitleRenderer) {
      this.subtitleRenderer.setCurrentTime(
        this.forcedSubtitleTime ?? this.videoElement?.currentTime ?? 0,
      );
      // libass blends in its worker and posts the completed frame
      // asynchronously, so keep the CanvasTexture upload polling at display
      // cadence while a styled track is active.
      if (this.subtitleTexture) this.subtitleTexture.needsUpdate = true;
    } else if (this.isPlaying && this.subtitleTexture) {
      this.subtitleTexture.needsUpdate = true;
    }
    if (this.isPlaying && this.controlsVisible && !this.moveInProgress && performance.now() >= this.controlsHideAt) {
      this.setControlsVisible(false);
    }
    const second = Math.floor(this.videoElement?.currentTime ?? 0);
    if (second !== this.lastUiSecond) {
      this.lastUiSecond = second;
      this.transportView?.refresh();
    }
  }

  override dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.lifecycleGeneration++;
    syncPlayCoordinator.setHost(null);
    window.clearInterval(this.progressInterval ?? undefined);
    if (this.playbackStartedReported) void this.reportPlaybackLifecycle('/Sessions/Playing/Stopped', true);
    this.isPlaying = false;
    if (this.progressInterval !== null) window.clearInterval(this.progressInterval);
    if (this.aiResetTimeout !== null) window.clearTimeout(this.aiResetTimeout);
    this.progressInterval = null;
    this.aiResetTimeout = null;
    this.playbackAbortController?.abort();
    this.audioSwitchAbortController?.abort();
    this.progressAbortController?.abort();
    this.subtitleAbortController?.abort();
    this.playbackAbortController = null;
    this.audioSwitchAbortController = null;
    this.progressAbortController = null;
    this.audioSwitchGeneration++;
    this.audioSwitchInProgress = false;
    this.pendingPlaybackRestore = null;
    this.subtitleAbortController = null;
    this.subtitleGeneration++;
    this.subtitleRenderer?.dispose();
    this.subtitleRenderer = null;
    this.hls?.destroy();
    this.hls = null;
    if (this.audioContext) {
      if (this.audioSourceNode) {
        this.audioSourceNode.disconnect();
      }
      void this.audioContext.close();
      this.audioContext = null;
      this.audioSourceNode = null;
      this.spatialPannerNodes = [];
    }
    const video = this.videoElement;
    if (video) {
      video.removeEventListener('play', this.handleVideoPlay);
      video.removeEventListener('pause', this.handleVideoPause);
      video.removeEventListener('seeked', this.handleVideoSeeked);
      video.removeEventListener('error', this.handleVideoError);
      video.removeEventListener('loadedmetadata', this.handleLoadedMetadata);
      video.textTracks.removeEventListener('addtrack', this.handleTextTracksChanged);
      video.textTracks.removeEventListener('change', this.handleTextTracksChanged);
      for (const track of this.trackedTextTracks) {
        track.removeEventListener('cuechange', this.handleSubtitleCueChange);
      }
      this.trackedTextTracks.clear();
      video.pause();
      video.removeAttribute('src');
      video.load();
      video.remove();
      this.videoElement = null;
    }
    this.subtitleCanvas?.remove();
    this.subtitleContainer?.remove();
    this.subtitleCanvas = null;
    this.subtitleContainer = null;
    this.clearScreen();
    this.videoTexture?.dispose();
    this.subtitleTexture?.dispose();
    this.videoTexture = null;
    this.subtitleTexture = null;
    this.transportPanel = null;
    this.stagePanel = null;
    this.trackPanel = null;
    this.sessionPanel = null;
    this.transportView = null;
    this.stageView = null;
    this.trackView = null;
    this.sessionView = null;
    this.theaterDome = null;
    this.mediaSourceId = undefined;
    this.playSessionId = undefined;
    this.subtitleTracks = [];
    this.subtitleFontUrls = [];
    this.subtitleAudioStreams = [];
    this.defaultAudioStreamIndex = undefined;
    this.selectedAudioStreamIndex = undefined;
    this.selectedSubtitleIndex = -1;
    this.forcedSubtitleTime = null;
  }
}
