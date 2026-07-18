export interface SendspinAudioFormat {
  codec: string; // 'pcm' | 'opus' | 'flac'
  sampleRate: number;
  channels: number;
  bitDepth: number;
}

export interface SendspinTrackInfo {
  title?: string;
  artist?: string;
  album?: string;
  artworkUrl?: string;
  durationSeconds?: number;
}

export type SendspinStateListener = (state: {
  connected: boolean;
  playing: boolean;
  stalled: boolean;
  track?: SendspinTrackInfo;
  format?: SendspinAudioFormat;
  volume: number;
  muted: boolean;
}) => void;

export class SendspinReceiver {
  private ws: WebSocket | null = null;
  private audioCtx: AudioContext | null = null;
  private gainNode: GainNode | null = null;
  private clientId: string;
  private clientName: string;
  private serverUrl: string = '';
  
  public get connectedServerUrl(): string {
    return this.serverUrl;
  }
  
  private isConnected: boolean = false;
  private isPlaying: boolean = false;
  private isAudioStalled: boolean = false;
  private currentVolume: number = 100;
  private isMuted: boolean = false;

  private currentTrack?: SendspinTrackInfo;
  private currentFormat?: SendspinAudioFormat;

  private serverTimeOffsetMs: number = 0;
  private rttMs: number = 0;
  private clockSyncTimer: number | null = null;
  private stallWatchdogTimer: number | null = null;
  private lastAudioChunkReceivedTime: number = 0;

  private nextScheduledAudioTime: number = 0;
  private scheduledSources: Set<AudioBufferSourceNode> = new Set();

  private listeners: Set<SendspinStateListener> = new Set();

  constructor(clientName: string = 'SpatialFin Web') {
    this.clientName = clientName;
    const storedId = localStorage.getItem('spatialfin_sendspin_client_id');
    if (storedId) {
      this.clientId = storedId;
    } else {
      this.clientId = `spatialfin-web-${Math.random().toString(36).substring(2, 11)}`;
      localStorage.setItem('spatialfin_sendspin_client_id', this.clientId);
    }
  }

  public subscribe(listener: SendspinStateListener): () => void {
    this.listeners.add(listener);
    this.notifyState();
    return () => this.listeners.delete(listener);
  }

  private notifyState() {
    const state = {
      connected: this.isConnected,
      playing: this.isPlaying,
      stalled: this.isAudioStalled,
      track: this.currentTrack,
      format: this.currentFormat,
      volume: this.currentVolume,
      muted: this.isMuted,
    };
    for (const listener of this.listeners) {
      try {
        listener(state);
      } catch (e) {
        console.error('Sendspin listener error:', e);
      }
    }
  }

  public async start(serverUrl: string): Promise<void> {
    this.stop();
    this.serverUrl = serverUrl;
    
    // Ensure AudioContext exists and is initialized/resumed on user interaction
    this.initAudioContext();

    const wsUrl = this.resolveSendspinWsUrl(serverUrl);
    console.log('[Sendspin] Connecting to:', wsUrl);

    try {
      this.ws = new WebSocket(wsUrl);
      this.ws.binaryType = 'arraybuffer';

      this.ws.onopen = () => {
        console.log('[Sendspin] WebSocket connected');
        this.isConnected = true;
        this.isAudioStalled = false;
        this.sendHello();
        this.startClockSync();
        this.startStallWatchdog();
        this.notifyState();
      };

      this.ws.onmessage = (event) => {
        this.handleMessage(event.data);
      };

      this.ws.onerror = (err) => {
        console.warn('[Sendspin] WebSocket error:', err);
      };

      this.ws.onclose = () => {
        console.log('[Sendspin] WebSocket closed');
        this.isConnected = false;
        this.isPlaying = false;
        this.stopClockSync();
        this.stopStallWatchdog();
        this.notifyState();
      };
    } catch (e) {
      console.error('[Sendspin] Connection failed:', e);
      this.isConnected = false;
      this.notifyState();
    }
  }

  public stop(): void {
    if (this.ws) {
      try {
        this.ws.close();
      } catch {}
      this.ws = null;
    }
    this.stopClockSync();
    this.stopStallWatchdog();
    this.stopScheduledAudio();
    this.isConnected = false;
    this.isPlaying = false;
    this.isAudioStalled = false;
    this.notifyState();
  }

  public setVolume(volume: number): void {
    this.currentVolume = Math.max(0, Math.min(100, volume));
    if (this.gainNode) {
      const gain = this.isMuted ? 0 : (this.currentVolume / 100) ** 2;
      this.gainNode.gain.setValueAtTime(gain, this.audioCtx?.currentTime || 0);
    }
    this.sendClientState();
    this.notifyState();
  }

  public setMuted(muted: boolean): void {
    this.isMuted = muted;
    if (this.gainNode) {
      const gain = this.isMuted ? 0 : (this.currentVolume / 100) ** 2;
      this.gainNode.gain.setValueAtTime(gain, this.audioCtx?.currentTime || 0);
    }
    this.sendClientState();
    this.notifyState();
  }

  private initAudioContext() {
    if (!this.audioCtx) {
      const AudioCtxClass = window.AudioContext || (window as any).webkitAudioContext;
      this.audioCtx = new AudioCtxClass();
      this.gainNode = this.audioCtx.createGain();
      const gain = this.isMuted ? 0 : (this.currentVolume / 100) ** 2;
      this.gainNode.gain.setValueAtTime(gain, this.audioCtx.currentTime);
      this.gainNode.connect(this.audioCtx.destination);
    }
    if (this.audioCtx.state === 'suspended') {
      void this.audioCtx.resume();
    }
  }

  private resolveSendspinWsUrl(baseUrl: string): string {
    let urlStr = baseUrl;
    if (!urlStr.startsWith('ws://') && !urlStr.startsWith('wss://')) {
      if (urlStr.startsWith('https://')) {
        urlStr = urlStr.replace(/^https:\/\//, 'wss://');
      } else if (urlStr.startsWith('http://')) {
        urlStr = urlStr.replace(/^http:\/\//, 'ws://');
      } else {
        urlStr = (window.location.protocol === 'https:' ? 'wss://' : 'ws://') + urlStr;
      }
    }
    urlStr = urlStr.replace(/\/+$/, '');
    if (!urlStr.endsWith('/sendspin')) {
      urlStr += '/sendspin';
    }
    return urlStr;
  }

  private sendHello() {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    const helloMsg = {
      type: 'client/hello',
      client_id: this.clientId,
      name: this.clientName,
      version: '1.0.0',
      roles: ['player'],
      player_support: {
        supported_codecs: ['pcm', 'flac', 'opus'],
        max_channels: 2,
        sample_rates: [44100, 48000],
        bit_depths: [16, 24],
        buffer_capacity_ms: 2000,
      },
    };
    this.ws.send(JSON.stringify(helloMsg));
  }

  private startClockSync() {
    this.stopClockSync();
    this.sendClockSync();
    this.clockSyncTimer = window.setInterval(() => {
      this.sendClockSync();
    }, 4000);
  }

  private stopClockSync() {
    if (this.clockSyncTimer !== null) {
      clearInterval(this.clockSyncTimer);
      this.clockSyncTimer = null;
    }
  }

  private sendClockSync() {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    const msg = {
      type: 'client/time',
      client_time: Date.now(),
    };
    this.ws.send(JSON.stringify(msg));
  }

  private startStallWatchdog() {
    this.stopStallWatchdog();
    this.stallWatchdogTimer = window.setInterval(() => {
      if (this.isPlaying) {
        const timeSinceAudio = Date.now() - this.lastAudioChunkReceivedTime;
        if (timeSinceAudio > 6000) {
          if (!this.isAudioStalled) {
            console.warn('[Sendspin] Audio stall detected (no chunks in 6s)');
            this.isAudioStalled = true;
            this.notifyState();
          }
        }
      }
    }, 2000);
  }

  private stopStallWatchdog() {
    if (this.stallWatchdogTimer !== null) {
      clearInterval(this.stallWatchdogTimer);
      this.stallWatchdogTimer = null;
    }
  }

  private sendClientState() {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    const stateMsg = {
      type: 'client/state',
      state: this.isPlaying ? 'playing' : 'idle',
      volume: this.currentVolume,
      muted: this.isMuted,
    };
    this.ws.send(JSON.stringify(stateMsg));
  }

  private handleMessage(data: string | ArrayBuffer) {
    if (typeof data === 'string') {
      try {
        const msg = JSON.parse(data);
        this.handleJsonMessage(msg);
      } catch (e) {
        console.error('[Sendspin] JSON parse error:', e);
      }
    } else if (data instanceof ArrayBuffer) {
      this.handleBinaryAudioChunk(data);
    }
  }

  private handleJsonMessage(msg: any) {
    if (!msg || !msg.type) return;

    switch (msg.type) {
      case 'server/hello':
        console.log('[Sendspin] Server hello:', msg.server_name || msg.server_id);
        break;

      case 'server/time': {
        const clientTime = msg.client_time || 0;
        const serverTime = msg.server_time || 0;
        const now = Date.now();
        this.rttMs = now - clientTime;
        // Server time offset calculation
        const expectedServerTime = now + this.rttMs / 2;
        this.serverTimeOffsetMs = serverTime - expectedServerTime;
        break;
      }

      case 'server/state':
      case 'server/track': {
        if (msg.state) {
          this.isPlaying = msg.state === 'playing';
        }
        if (msg.track || msg.title || msg.artist) {
          const t = msg.track || msg;
          this.currentTrack = {
            title: t.title || t.name,
            artist: t.artist || t.artist_name,
            album: t.album || t.album_name,
            artworkUrl: t.artwork_url || t.image_url,
            durationSeconds: t.duration,
          };
        }
        if (msg.format || msg.codec || msg.sample_rate) {
          const f = msg.format || msg;
          this.currentFormat = {
            codec: f.codec || 'pcm',
            sampleRate: f.sample_rate || 44100,
            channels: f.channels || 2,
            bitDepth: f.bit_depth || 16,
          };
        }
        this.isAudioStalled = false;
        this.notifyState();
        break;
      }

      case 'server/audio_chunk': {
        this.lastAudioChunkReceivedTime = Date.now();
        if (this.isAudioStalled) {
          this.isAudioStalled = false;
          this.notifyState();
        }
        if (msg.data && msg.codec) {
          const pcmBytes = this.base64ToArrayBuffer(msg.data);
          this.processAndPlayPcm(
            pcmBytes,
            msg.sample_rate || this.currentFormat?.sampleRate || 44100,
            msg.channels || this.currentFormat?.channels || 2,
            msg.bit_depth || this.currentFormat?.bitDepth || 16,
            msg.server_time
          );
        }
        break;
      }

      default:
        break;
    }
  }

  private handleBinaryAudioChunk(buffer: ArrayBuffer) {
    this.lastAudioChunkReceivedTime = Date.now();
    if (this.isAudioStalled) {
      this.isAudioStalled = false;
      this.notifyState();
    }
    // Simple Binary Chunk Header: 8 bytes server_time (BigInt64/Float64 or uint32), followed by PCM data
    if (buffer.byteLength < 8) return;
    const view = new DataView(buffer);
    let serverTime: number | undefined;
    try {
      serverTime = Number(view.getBigInt64(0, false));
    } catch {
      serverTime = view.getUint32(0, false);
    }
    const pcmData = buffer.slice(8);
    const sampleRate = this.currentFormat?.sampleRate || 44100;
    const channels = this.currentFormat?.channels || 2;
    const bitDepth = this.currentFormat?.bitDepth || 16;
    this.processAndPlayPcm(pcmData, sampleRate, channels, bitDepth, serverTime);
  }

  private processAndPlayPcm(
    pcmBuffer: ArrayBuffer,
    sampleRate: number,
    channels: number,
    bitDepth: number,
    serverTimeMs?: number
  ) {
    this.initAudioContext();
    if (!this.audioCtx || !this.gainNode) return;

    const numSamples = Math.floor(pcmBuffer.byteLength / (channels * (bitDepth / 8)));
    if (numSamples <= 0) return;

    const audioBuffer = this.audioCtx.createBuffer(channels, numSamples, sampleRate);
    const view = new DataView(pcmBuffer);

    if (bitDepth === 16) {
      for (let ch = 0; ch < channels; ch++) {
        const channelData = audioBuffer.getChannelData(ch);
        let offset = ch * 2;
        const stride = channels * 2;
        for (let i = 0; i < numSamples; i++) {
          if (offset + 1 < pcmBuffer.byteLength) {
            const int16 = view.getInt16(offset, true); // little-endian
            channelData[i] = int16 / 32768;
          }
          offset += stride;
        }
      }
    } else if (bitDepth === 24) {
      for (let ch = 0; ch < channels; ch++) {
        const channelData = audioBuffer.getChannelData(ch);
        let offset = ch * 3;
        const stride = channels * 3;
        for (let i = 0; i < numSamples; i++) {
          if (offset + 2 < pcmBuffer.byteLength) {
            const b0 = view.getUint8(offset);
            const b1 = view.getUint8(offset + 1);
            const b2 = view.getInt8(offset + 2);
            const int24 = (b2 << 16) | (b1 << 8) | b0;
            channelData[i] = int24 / 8388608;
          }
          offset += stride;
        }
      }
    } else {
      // 32-bit float or default fallback
      for (let ch = 0; ch < channels; ch++) {
        const channelData = audioBuffer.getChannelData(ch);
        let offset = ch * 4;
        const stride = channels * 4;
        for (let i = 0; i < numSamples; i++) {
          if (offset + 3 < pcmBuffer.byteLength) {
            channelData[i] = view.getFloat32(offset, true);
          }
          offset += stride;
        }
      }
    }

    // Schedule audio node
    const now = this.audioCtx.currentTime;
    let targetTime = Math.max(now, this.nextScheduledAudioTime);

    if (serverTimeMs) {
      const nowMs = Date.now();
      const currentServerTime = nowMs + this.serverTimeOffsetMs;
      const leadMs = serverTimeMs - currentServerTime;
      const targetTimeFromClock = now + leadMs / 1000;
      if (targetTimeFromClock > now && Math.abs(targetTimeFromClock - targetTime) < 0.5) {
        targetTime = targetTimeFromClock;
      }
    }

    const sourceNode = this.audioCtx.createBufferSource();
    sourceNode.buffer = audioBuffer;
    sourceNode.connect(this.gainNode);

    sourceNode.start(targetTime);
    this.nextScheduledAudioTime = targetTime + audioBuffer.duration;

    this.scheduledSources.add(sourceNode);
    sourceNode.onended = () => {
      this.scheduledSources.delete(sourceNode);
    };

    if (!this.isPlaying) {
      this.isPlaying = true;
      this.notifyState();
    }
  }

  private stopScheduledAudio() {
    for (const source of this.scheduledSources) {
      try {
        source.stop();
        source.disconnect();
      } catch {}
    }
    this.scheduledSources.clear();
    this.nextScheduledAudioTime = 0;
  }

  private base64ToArrayBuffer(base64: string): ArrayBuffer {
    const binaryString = atob(base64);
    const len = binaryString.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
      bytes[i] = binaryString.charCodeAt(i);
    }
    return bytes.buffer;
  }
}
