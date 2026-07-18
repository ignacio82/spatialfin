import { MusicAssistantClient } from './MusicAssistantClient';
import type {
  MAConfig,
  MAMediaItem,
  MAPlayer,
  MAQueue,
  MAQueueItem,
} from './MusicAssistantTypes';
import { SendspinReceiver } from '../sendspin/SendspinReceiver';

export interface MAManagerState {
  config: MAConfig;
  connected: boolean;
  players: MAPlayer[];
  activePlayerId?: string;
  activePlayer?: MAPlayer;
  activeQueue?: MAQueue;
  sendspinActive: boolean;
  sendspinStalled: boolean;
  currentTrackTitle?: string;
  currentTrackArtist?: string;
  currentTrackAlbum?: string;
  currentArtworkUrl?: string;
  audioFormatPill?: string; // e.g. "FLAC 24-bit 96kHz" or "SendSpin 48kHz"
  isPlaying: boolean;
  volume: number;
  isMuted: boolean;
}

export type MAManagerListener = (state: MAManagerState) => void;

export class MusicAssistantManager {
  private static instance: MusicAssistantManager | null = null;

  public readonly client: MusicAssistantClient;
  public readonly sendspin: SendspinReceiver;

  private config: MAConfig;
  private listeners: Set<MAManagerListener> = new Set();
  private queueItems: MAQueueItem[] = [];

  private constructor() {
    this.client = new MusicAssistantClient();
    this.sendspin = new SendspinReceiver('SpatialFin Web');

    this.config = this.loadConfig();

    // Listen to client events
    this.client.subscribeEvents((event, data) => {
      this.handleClientEvent(event, data);
    });

    // Listen to sendspin state
    this.sendspin.subscribe(() => {
      this.notifyState();
    });

    // Auto connect if server URL configured
    if (this.config.serverUrl) {
      void this.connect();
    }
  }

  public static getInstance(): MusicAssistantManager {
    if (!MusicAssistantManager.instance) {
      MusicAssistantManager.instance = new MusicAssistantManager();
    }
    return MusicAssistantManager.instance;
  }

  public subscribe(listener: MAManagerListener): () => void {
    this.listeners.add(listener);
    listener(this.getState());
    return () => this.listeners.delete(listener);
  }

  private notifyState() {
    const state = this.getState();
    for (const listener of this.listeners) {
      try {
        listener(state);
      } catch (e) {
        console.error('[MA Manager] Listener error:', e);
      }
    }
  }

  public getConfig(): MAConfig {
    return { ...this.config };
  }

  public saveConfig(newConfig: Partial<MAConfig>) {
    this.config = { ...this.config, ...newConfig };
    localStorage.setItem('spatialfin_ma_config', JSON.stringify(this.config));
    this.notifyState();
    void this.connect();
  }

  private loadConfig(): MAConfig {
    const raw = localStorage.getItem('spatialfin_ma_config');
    if (raw) {
      try {
        return JSON.parse(raw);
      } catch {}
    }
    return {
      serverUrl: '',
      token: '',
      sendspinEnabled: true,
      sendspinName: 'SpatialFin Web',
    };
  }

  public async connect(): Promise<boolean> {
    if (!this.config.serverUrl) return false;

    console.log('[MA Manager] Connecting with config:', this.config.serverUrl);

    // 1. Connect Sendspin if enabled
    if (this.config.sendspinEnabled) {
      void this.sendspin.start(this.config.serverUrl);
    } else {
      this.sendspin.stop();
    }

    // 2. Connect MA WebSocket client
    const success = await this.client.connect(this.config.serverUrl, this.config.token);

    if (success && !this.config.preferredPlayerId) {
      // Pick first available player or sendspin player
      const players = this.client.currentPlayers;
      if (players.length > 0) {
        this.config.preferredPlayerId = players[0].player_id;
      }
    }

    this.notifyState();
    return success;
  }

  public selectActivePlayer(playerId: string) {
    this.saveConfig({ preferredPlayerId: playerId });
  }

  public getState(): MAManagerState {
    const players = this.client.currentPlayers;
    const activePlayerId = this.config.preferredPlayerId || (players[0]?.player_id);
    const activePlayer = players.find((p) => p.player_id === activePlayerId) || players[0];

    let isPlaying = false;
    let title: string | undefined;
    let artist: string | undefined;
    let album: string | undefined;
    let artworkUrl: string | undefined;
    let audioFormatPill: string | undefined;
    let volume = 100;
    let isMuted = false;

    if (activePlayer) {
      isPlaying = activePlayer.state === 'playing';
      volume = activePlayer.volume_level ?? 100;
      isMuted = activePlayer.is_muted ?? false;

      const item = activePlayer.current_item || activePlayer.current_media;
      if (item) {
        title = item.name;
        artist = item.artists?.map((a) => a.name).join(', ') || item.metadata?.performers?.join(', ');
        album = item.album?.name;
        artworkUrl = this.client.getImageUrl(item) || undefined;
      }
    }

    // Check if sendspin receiver has active track
    if (this.config.sendspinEnabled) {
      // If sendspin is playing locally, reflect sendspin metadata if MA active player lacks it
      const currentTrack = (this.sendspin as any).currentTrack;
      const currentFormat = (this.sendspin as any).currentFormat;

      if (!title && currentTrack) {
        title = currentTrack.title;
        artist = currentTrack.artist;
        album = currentTrack.album;
        artworkUrl = currentTrack.artworkUrl || artworkUrl;
      }

      if (currentFormat) {
        audioFormatPill = `${currentFormat.codec.toUpperCase()} ${currentFormat.bitDepth}-bit ${currentFormat.sampleRate / 1000}kHz`;
      }
    }

    if (!audioFormatPill && isPlaying) {
      audioFormatPill = 'Music Assistant HD';
    }

    const activeQueue = this.client.currentQueues.find((q) => q.queue_id === activePlayerId);

    return {
      config: this.config,
      connected: this.client.connected,
      players,
      activePlayerId,
      activePlayer,
      activeQueue,
      sendspinActive: this.config.sendspinEnabled,
      sendspinStalled: (this.sendspin as any).isAudioStalled || false,
      currentTrackTitle: title,
      currentTrackArtist: artist,
      currentTrackAlbum: album,
      currentArtworkUrl: artworkUrl,
      audioFormatPill,
      isPlaying,
      volume,
      isMuted,
    };
  }

  public async playPause(): Promise<void> {
    const state = this.getState();
    if (state.activePlayerId) {
      await this.client.playPause(state.activePlayerId);
    }
  }

  public async next(): Promise<void> {
    const state = this.getState();
    if (state.activePlayerId) {
      await this.client.next(state.activePlayerId);
    }
  }

  public async previous(): Promise<void> {
    const state = this.getState();
    if (state.activePlayerId) {
      await this.client.previous(state.activePlayerId);
    }
  }

  public async setVolume(vol: number): Promise<void> {
    const state = this.getState();
    if (state.activePlayerId) {
      await this.client.setVolume(state.activePlayerId, vol);
    }
    if (state.sendspinActive) {
      this.sendspin.setVolume(vol);
    }
  }

  public async setMuted(muted: boolean): Promise<void> {
    const state = this.getState();
    if (state.activePlayerId) {
      await this.client.setMuted(state.activePlayerId, muted);
    }
    if (state.sendspinActive) {
      this.sendspin.setMuted(muted);
    }
  }

  public async playMedia(media: MAMediaItem | MAMediaItem[]): Promise<void> {
    const state = this.getState();
    if (state.activePlayerId) {
      await this.client.playMedia(state.activePlayerId, media, 'play');
    }
  }

  public async enqueueMedia(media: MAMediaItem | MAMediaItem[]): Promise<void> {
    const state = this.getState();
    if (state.activePlayerId) {
      await this.client.playMedia(state.activePlayerId, media, 'add');
    }
  }

  public async getQueueItems(): Promise<MAQueueItem[]> {
    const state = this.getState();
    if (state.activePlayerId) {
      this.queueItems = await this.client.getQueueItems(state.activePlayerId);
    }
    return this.queueItems;
  }

  public async clearQueue(): Promise<void> {
    const state = this.getState();
    if (state.activePlayerId) {
      await this.client.clearQueue(state.activePlayerId);
      this.queueItems = [];
      this.notifyState();
    }
  }

  private handleClientEvent(event: string, _data: any) {
    if (event === 'player_updated' || event === 'connected' || event === 'disconnected' || event === 'queue_updated') {
      this.notifyState();
    }
  }
}
