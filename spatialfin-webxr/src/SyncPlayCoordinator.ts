import { getAuthHeaders, getServerUrl } from './auth';
import { jellyfinSocket, type JellyfinMessage, type SocketApiState } from './JellyfinSocket';
import { type JellyfinItem } from './api';

export interface SyncPlayGroup {
  Id: string;
  Name: string;
}

export type SyncPlayState = 'Idle' | 'Waiting' | 'Paused' | 'Playing';

export interface SyncPlayUiState {
  isLoading: boolean;
  statusMessage: string | null;
  activeGroup: SyncPlayGroup | null;
  availableGroups: SyncPlayGroup[];
}

export interface SyncPlayHost {
  player: HTMLVideoElement | null;
  currentItemId: string | null;
  currentItemTitle: string | null;
  replaceItems: (items: JellyfinItem[]) => void;
  initializePlayer: (
    itemId: string,
    startFromBeginning: boolean,
    autoPlay: boolean,
    startPositionTicks?: number
  ) => void;
}

export class SyncPlayCoordinator {
  private state: SyncPlayUiState = {
    isLoading: false,
    statusMessage: null,
    activeGroup: null,
    availableGroups: [],
  };

  private stateListeners: Set<(state: SyncPlayUiState) => void> = new Set();
  private host: SyncPlayHost | null = null;
  private activeGroupId: string | null = null;
  private suppressSyncUntilMs = 0;

  constructor() {
    jellyfinSocket.addStateListener(this.handleSocketState.bind(this));
    jellyfinSocket.addMessageListener(this.handleSocketMessage.bind(this));
  }

  public setHost(host: SyncPlayHost | null) {
    this.host = host;
  }

  public getState(): SyncPlayUiState {
    return this.state;
  }

  public addStateListener(listener: (state: SyncPlayUiState) => void) {
    this.stateListeners.add(listener);
    listener(this.state);
  }

  public removeStateListener(listener: (state: SyncPlayUiState) => void) {
    this.stateListeners.delete(listener);
  }

  private updateState(partial: Partial<SyncPlayUiState>) {
    this.state = { ...this.state, ...partial };
    for (const listener of this.stateListeners) {
      listener(this.state);
    }
  }

  public isActive(): boolean {
    return this.activeGroupId !== null;
  }

  public shouldSuppressEvents(): boolean {
    return performance.now() < this.suppressSyncUntilMs;
  }

  private applyRemoteSync(action: () => void) {
    this.suppressSyncUntilMs = performance.now() + 1500;
    action();
  }

  private async fetchApi<T>(path: string, options: RequestInit = {}): Promise<T> {
    const serverUrl = getServerUrl();
    if (!serverUrl) throw new Error('No server URL');
    const response = await fetch(`${serverUrl}${path.startsWith('/') ? path : `/${path}`}`, {
      ...options,
      headers: {
        ...getAuthHeaders(true),
        ...options.headers,
      }
    });
    if (!response.ok) {
      throw new Error(`SyncPlay API error: ${response.status}`);
    }
    const text = await response.text();
    return text ? JSON.parse(text) : null as any as T;
  }

  public async refreshGroups() {
    this.updateState({ isLoading: true, statusMessage: null });
    try {
      const groups = await this.fetchApi<SyncPlayGroup[]>('/SyncPlay/Groups');
      const active = groups.find(g => g.Id === this.activeGroupId) || this.state.activeGroup;
      this.updateState({
        isLoading: false,
        availableGroups: groups,
        activeGroup: active
      });
    } catch (error) {
      console.warn('Failed to refresh SyncPlay groups', error);
      this.updateState({
        isLoading: false,
        statusMessage: error instanceof Error ? error.message : 'Unable to load SyncPlay groups'
      });
    }
  }

  public async createGroup() {
    if (!this.host || !this.host.currentItemId) {
      this.updateState({ statusMessage: 'SyncPlay requires an active media item' });
      return;
    }

    const groupName = this.host.currentItemTitle || 'SpatialFin Group';
    this.updateState({ isLoading: true, statusMessage: null });

    try {
      const group = await this.fetchApi<SyncPlayGroup>('/SyncPlay/NewGroup', {
        method: 'POST',
        body: JSON.stringify({ GroupName: groupName })
      });

      const positionTicks = Math.floor((this.host.player?.currentTime || 0) * 10_000_000);
      
      await this.fetchApi('/SyncPlay/SetQueue', {
        method: 'POST',
        body: JSON.stringify({
          ItemIds: [this.host.currentItemId],
          PlayingItemIndex: 0,
          StartPositionTicks: positionTicks
        })
      });

      this.activeGroupId = group.Id;
      this.updateState({
        isLoading: false,
        activeGroup: group,
        statusMessage: `Created SyncPlay group: ${group.Name}`
      });
      void this.refreshGroups();
    } catch (error) {
      console.warn('Failed to create SyncPlay group', error);
      this.updateState({
        isLoading: false,
        statusMessage: error instanceof Error ? error.message : 'Unable to create SyncPlay group'
      });
    }
  }

  public async joinGroup(groupId: string) {
    this.updateState({ isLoading: true, statusMessage: null });
    try {
      await this.fetchApi('/SyncPlay/JoinGroup', {
        method: 'POST',
        body: JSON.stringify({ GroupId: groupId })
      });
      
      this.activeGroupId = groupId;
      this.updateState({
        isLoading: false,
        statusMessage: 'Joined SyncPlay group'
      });
      void this.refreshGroups();
    } catch (error) {
      console.warn('Failed to join SyncPlay group', error);
      this.updateState({
        isLoading: false,
        statusMessage: error instanceof Error ? error.message : 'Unable to join SyncPlay group'
      });
    }
  }

  public async leaveGroup() {
    try {
      await this.fetchApi('/SyncPlay/LeaveGroup', { method: 'POST' });
    } catch (error) {
      console.warn('Failed to leave SyncPlay group', error);
    }
    this.activeGroupId = null;
    this.updateState({ activeGroup: null, statusMessage: 'Left SyncPlay group' });
    void this.refreshGroups();
  }

  private handleSocketState(state: SocketApiState) {
    if (state === 'Connected') {
      if (this.isActive()) {
        this.updateState({ statusMessage: 'SyncPlay reconnected' });
        void this.refreshGroups();
      }
    } else if (state === 'Connecting') {
      if (this.isActive()) {
        this.updateState({ statusMessage: 'Reconnecting SyncPlay...' });
      }
    } else if (state === 'Disconnected') {
      if (this.isActive()) {
        this.updateState({ statusMessage: 'SyncPlay connection lost' });
      }
    }
  }

  private async handleSocketMessage(message: JellyfinMessage) {
    const data = message.Data;
    if (!data) return;

    if (message.MessageType === 'SyncPlayCommand' && data.GroupId === this.activeGroupId) {
      await this.handleSyncPlayCommand(data);
    } else if (message.MessageType === 'SyncPlayGroupUpdate') {
      await this.handleSyncPlayGroupUpdate(data);
    } else if (message.MessageType === 'Playstate') {
      await this.handlePlaystate(data);
    }
  }

  private async handleSyncPlayCommand(command: any) {
    if (!this.host || !this.host.player) return;
    const player = this.host.player;

    switch (command.Command) {
      case 'Pause':
        this.applyRemoteSync(() => player.pause());
        break;
      case 'Unpause':
        this.applyRemoteSync(() => void player.play());
        break;
      case 'Seek':
        if (typeof command.PositionTicks === 'number') {
          this.applyRemoteSync(() => { player.currentTime = command.PositionTicks / 10_000_000; });
        }
        break;
      case 'Stop':
        this.applyRemoteSync(() => {
          player.pause();
          player.currentTime = 0;
        });
        break;
    }
  }

  private async handleSyncPlayGroupUpdate(update: any) {
    if (update.Type === 'GroupJoined' && this.activeGroupId === update.GroupId) {
      this.updateState({ activeGroup: update.Data });
    } else if (update.Type === 'PlayQueueUpdate' && this.activeGroupId === update.GroupId) {
      const queue = update.Data.Playlist || [];
      const playingIndex = update.Data.PlayingItemIndex || 0;
      const startPositionTicks = update.Data.StartPositionTicks || 0;
      const isPlaying = update.Data.IsPlaying || false;

      if (!this.host || !this.host.player) return;
      const targetItem = queue[playingIndex];
      if (!targetItem) return;

      if (this.host.currentItemId !== targetItem.ItemId) {
        this.applyRemoteSync(() => {
          this.host!.initializePlayer(targetItem.ItemId, startPositionTicks <= 0, isPlaying, startPositionTicks);
        });
      } else {
        this.applyRemoteSync(() => {
          this.host!.player!.currentTime = startPositionTicks / 10_000_000;
          if (isPlaying) void this.host!.player!.play();
          else this.host!.player!.pause();
        });
      }
    } else if (update.Type === 'StateUpdate' && this.activeGroupId === update.GroupId) {
      if (!this.host || !this.host.player) return;
      const state = update.Data.State;
      if (state === 'Playing') {
        this.applyRemoteSync(() => void this.host!.player!.play());
      } else if (state === 'Paused' || state === 'Waiting') {
        this.applyRemoteSync(() => this.host!.player!.pause());
      }
    } else if (
      (update.Type === 'GroupLeft' || update.Type === 'NotInGroup' || update.Type === 'GroupDoesNotExist') &&
      this.activeGroupId === update.GroupId
    ) {
      this.activeGroupId = null;
      this.updateState({ activeGroup: null, statusMessage: 'SyncPlay group ended' });
      void this.refreshGroups();
    }
  }

  private async handlePlaystate(command: any) {
    if (!this.host || !this.host.player) return;
    const player = this.host.player;

    switch (command.Command) {
      case 'Pause':
        this.applyRemoteSync(() => player.pause());
        break;
      case 'Unpause':
        this.applyRemoteSync(() => void player.play());
        break;
      case 'PlayPause':
        this.applyRemoteSync(() => {
          if (player.paused) void player.play();
          else player.pause();
        });
        break;
      case 'Seek':
        if (typeof command.SeekPositionTicks === 'number') {
          this.applyRemoteSync(() => { player.currentTime = command.SeekPositionTicks / 10_000_000; });
        }
        break;
      case 'Stop':
        this.applyRemoteSync(() => {
          player.pause();
          player.currentTime = 0;
        });
        break;
    }
  }

  public reportPlaybackEvent(event: 'Play' | 'Pause' | 'Seek', positionTicks: number) {
    if (!this.isActive() || this.shouldSuppressEvents()) return;
    if (event === 'Play') {
      void this.fetchApi('/SyncPlay/Unpause', { method: 'POST' });
    } else if (event === 'Pause') {
      void this.fetchApi('/SyncPlay/Pause', { method: 'POST' });
    } else if (event === 'Seek') {
      void this.fetchApi('/SyncPlay/Seek', {
        method: 'POST',
        body: JSON.stringify({ PositionTicks: positionTicks })
      });
    }
  }
}

export const syncPlayCoordinator = new SyncPlayCoordinator();
