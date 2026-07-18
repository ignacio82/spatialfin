import type {
  MAMediaItem,
  MAMediaType,
  MAPlayer,
  MAQueue,
  MAQueueItem,
  MASearchResults,
  MAServerInfo,
} from './MusicAssistantTypes';

export type MAEventListener = (eventType: string, data: any) => void;

export class MusicAssistantClient {
  private ws: WebSocket | null = null;
  private messageIdCounter = 1;
  private pendingRequests = new Map<
    number,
    { resolve: (res: any) => void; reject: (err: any) => void }
  >();

  private serverInfo: MAServerInfo | null = null;
  private isConnected = false;
  private isAuthenticated = false;
  private serverUrl = '';
  private token = '';

  private players = new Map<string, MAPlayer>();
  private queues = new Map<string, MAQueue>();

  private eventListeners = new Set<MAEventListener>();

  public get server(): MAServerInfo | null {
    return this.serverInfo;
  }

  public get connected(): boolean {
    return this.isConnected && this.isAuthenticated;
  }

  public get currentPlayers(): MAPlayer[] {
    return Array.from(this.players.values());
  }

  public get currentQueues(): MAQueue[] {
    return Array.from(this.queues.values());
  }

  public subscribeEvents(listener: MAEventListener): () => void {
    this.eventListeners.add(listener);
    return () => this.eventListeners.delete(listener);
  }

  private notifyEvent(eventType: string, data: any) {
    for (const listener of this.eventListeners) {
      try {
        listener(eventType, data);
      } catch (e) {
        console.error('[MA Client] Listener error:', e);
      }
    }
  }

  public async connect(serverUrl: string, token?: string): Promise<boolean> {
    this.disconnect();
    this.serverUrl = serverUrl;
    this.token = token || '';

    const wsUrl = this.resolveWsUrl(serverUrl);
    console.log('[MA Client] Connecting to:', wsUrl);

    return new Promise((resolve) => {
      try {
        this.ws = new WebSocket(wsUrl);

        this.ws.onopen = async () => {
          console.log('[MA Client] WebSocket opened, starting handshake');
          this.isConnected = true;

          try {
            // 1. Fetch server_info
            const info = await this.sendCommand('server/info');
            this.serverInfo = info;
            console.log('[MA Client] Server info:', info);

            // 2. Auth
            const authRes = await this.sendCommand('auth', { token: this.token });
            console.log('[MA Client] Auth result:', authRes);
            this.isAuthenticated = true;

            // 3. Initial state load
            await this.refreshPlayers();
            await this.refreshQueues();

            this.notifyEvent('connected', { serverInfo: this.serverInfo });
            resolve(true);
          } catch (err) {
            console.error('[MA Client] Handshake/Auth failed:', err);
            this.disconnect();
            resolve(false);
          }
        };

        this.ws.onmessage = (event) => {
          this.handleMessage(event.data);
        };

        this.ws.onerror = (err) => {
          console.warn('[MA Client] WebSocket error:', err);
        };

        this.ws.onclose = () => {
          console.log('[MA Client] WebSocket closed');
          this.isConnected = false;
          this.isAuthenticated = false;
          this.notifyEvent('disconnected', null);
        };
      } catch (e) {
        console.error('[MA Client] Connection error:', e);
        this.disconnect();
        resolve(false);
      }
    });
  }

  public disconnect(): void {
    if (this.ws) {
      try {
        this.ws.close();
      } catch {}
      this.ws = null;
    }
    this.isConnected = false;
    this.isAuthenticated = false;
    for (const [, req] of this.pendingRequests) {
      req.reject(new Error('Disconnected'));
    }
    this.pendingRequests.clear();
  }

  public sendCommand<T = any>(command: string, args: Record<string, any> = {}): Promise<T> {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('WebSocket is not connected'));
    }

    const messageId = this.messageIdCounter++;
    const msg = {
      cmd: command,
      args,
      message_id: messageId,
    };

    return new Promise((resolve, reject) => {
      this.pendingRequests.set(messageId, { resolve, reject });
      this.ws!.send(JSON.stringify(msg));

      // Timeout safety
      setTimeout(() => {
        if (this.pendingRequests.has(messageId)) {
          this.pendingRequests.delete(messageId);
          reject(new Error(`Command ${command} timed out`));
        }
      }, 15000);
    });
  }

  private handleMessage(rawMessage: string) {
    try {
      const msg = JSON.parse(rawMessage);

      // Check if it's a response to a command
      if (msg.message_id !== undefined && this.pendingRequests.has(msg.message_id)) {
        const { resolve, reject } = this.pendingRequests.get(msg.message_id)!;
        this.pendingRequests.delete(msg.message_id);

        if (msg.error) {
          reject(new Error(msg.error));
        } else {
          resolve(msg.result);
        }
        return;
      }

      // Check if it's a server event
      if (msg.event) {
        this.handleServerEvent(msg.event, msg.data);
      }
    } catch (e) {
      console.error('[MA Client] Error handling message:', e);
    }
  }

  private handleServerEvent(event: string, data: any) {
    console.log('[MA Client] Event:', event);

    if (event === 'player_updated' && data) {
      this.players.set(data.player_id, data);
      this.notifyEvent('player_updated', data);
    } else if (event === 'player_added' && data) {
      this.players.set(data.player_id, data);
      this.notifyEvent('player_added', data);
    } else if (event === 'player_removed' && data) {
      this.players.delete(data.player_id || data);
      this.notifyEvent('player_removed', data);
    } else if (event === 'queue_updated' && data) {
      this.queues.set(data.queue_id, data);
      this.notifyEvent('queue_updated', data);
    } else {
      this.notifyEvent(event, data);
    }
  }

  public async refreshPlayers(): Promise<MAPlayer[]> {
    const res = await this.sendCommand<MAPlayer[]>('players/all');
    this.players.clear();
    if (Array.isArray(res)) {
      for (const p of res) {
        this.players.set(p.player_id, p);
      }
    }
    return this.currentPlayers;
  }

  public async refreshQueues(): Promise<MAQueue[]> {
    const res = await this.sendCommand<MAQueue[]>('player_queues/all');
    this.queues.clear();
    if (Array.isArray(res)) {
      for (const q of res) {
        this.queues.set(q.queue_id, q);
      }
    }
    return this.currentQueues;
  }

  public async getQueueItems(queueId: string): Promise<MAQueueItem[]> {
    return (await this.sendCommand<MAQueueItem[]>('player_queues/items', { queue_id: queueId })) || [];
  }

  public async getLibraryItems<T extends MAMediaItem = MAMediaItem>(
    mediaType: MAMediaType,
    limit = 50,
    offset = 0
  ): Promise<T[]> {
    const endpoint = `music/${mediaType}s`;
    const res = await this.sendCommand<T[]>(endpoint, {
      library_only: true,
      limit,
      offset,
    });
    return Array.isArray(res) ? res : [];
  }

  public async search(query: string, mediaTypes?: MAMediaType[]): Promise<MASearchResults> {
    const res = await this.sendCommand<MASearchResults>('music/search', {
      search_query: query,
      media_types: mediaTypes,
      limit: 20,
    });
    return res || {};
  }

  public async getItemDetails(mediaType: MAMediaType, itemId: string, provider: string = 'library'): Promise<MAMediaItem | null> {
    const singularType = mediaType.endsWith('s') ? mediaType.slice(0, -1) : mediaType;
    const res = await this.sendCommand<MAMediaItem>(`music/${singularType}`, {
      item_id: itemId,
      provider,
    });
    return res || null;
  }

  public async play(playerId: string): Promise<void> {
    await this.sendCommand('players/cmd/play', { player_id: playerId });
  }

  public async pause(playerId: string): Promise<void> {
    await this.sendCommand('players/cmd/pause', { player_id: playerId });
  }

  public async playPause(playerId: string): Promise<void> {
    await this.sendCommand('players/cmd/play_pause', { player_id: playerId });
  }

  public async stop(playerId: string): Promise<void> {
    await this.sendCommand('players/cmd/stop', { player_id: playerId });
  }

  public async next(playerId: string): Promise<void> {
    await this.sendCommand('players/cmd/next', { player_id: playerId });
  }

  public async previous(playerId: string): Promise<void> {
    await this.sendCommand('players/cmd/previous', { player_id: playerId });
  }

  public async setVolume(playerId: string, volume: number): Promise<void> {
    await this.sendCommand('players/cmd/volume_set', {
      player_id: playerId,
      volume_level: Math.round(volume),
    });
  }

  public async setMuted(playerId: string, muted: boolean): Promise<void> {
    await this.sendCommand('players/cmd/volume_mute', {
      player_id: playerId,
      muted,
    });
  }

  public async playMedia(
    queueOrPlayerId: string,
    media: MAMediaItem | MAMediaItem[] | string | string[],
    option: 'play' | 'next' | 'add' = 'play'
  ): Promise<void> {
    const mediaItems = Array.isArray(media) ? media : [media];
    await this.sendCommand('player_queues/cmd/play_media', {
      queue_id: queueOrPlayerId,
      media: mediaItems,
      option,
    });
  }

  public async clearQueue(queueId: string): Promise<void> {
    await this.sendCommand('player_queues/cmd/clear', { queue_id: queueId });
  }

  public getImageUrl(item?: MAMediaItem | MAQueueItem | null): string | null {
    if (!item) return null;

    const img = (item as any).image_url || (item as any).image;
    if (img) {
      if (img.startsWith('http://') || img.startsWith('https://')) {
        return img;
      }
      return `${this.serverUrl.replace(/\/+$/, '')}/${img.replace(/^\/+/, '')}`;
    }

    if ((item as any).media_item) {
      return this.getImageUrl((item as any).media_item);
    }

    return null;
  }

  private resolveWsUrl(baseUrl: string): string {
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
    if (!urlStr.endsWith('/ws')) {
      urlStr += '/ws';
    }
    return urlStr;
  }
}
