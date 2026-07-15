import { getAccessToken, getDeviceId, getServerUrl } from './auth';

export type SocketApiState = 'Connected' | 'Connecting' | 'Disconnected';

export type MessageType =
  | 'SyncPlayCommand'
  | 'SyncPlayGroupUpdate'
  | 'Playstate'
  | 'GeneralCommand'
  | 'Sessions'
  | 'UserDataChanged'
  | string;

export interface JellyfinMessage<T = any> {
  MessageType: MessageType;
  MessageId?: string;
  Data?: T;
}

export type JellyfinSocketListener = (message: JellyfinMessage) => void;

export class JellyfinSocketController {
  private ws: WebSocket | null = null;
  private state: SocketApiState = 'Disconnected';
  private stateListeners: Set<(state: SocketApiState) => void> = new Set();
  private messageListeners: Set<JellyfinSocketListener> = new Set();
  private reconnectTimeout: number | null = null;
  private reconnectAttempts = 0;

  constructor() {
    this.connect();
  }

  public getState(): SocketApiState {
    return this.state;
  }

  public addStateListener(listener: (state: SocketApiState) => void) {
    this.stateListeners.add(listener);
    listener(this.state);
  }

  public removeStateListener(listener: (state: SocketApiState) => void) {
    this.stateListeners.delete(listener);
  }

  public addMessageListener(listener: JellyfinSocketListener) {
    this.messageListeners.add(listener);
  }

  public removeMessageListener(listener: JellyfinSocketListener) {
    this.messageListeners.delete(listener);
  }

  private setState(newState: SocketApiState) {
    if (this.state === newState) return;
    this.state = newState;
    for (const listener of this.stateListeners) {
      listener(newState);
    }
  }

  private getSocketUrl(): string | null {
    const serverUrl = getServerUrl();
    const token = getAccessToken();
    const deviceId = getDeviceId();
    if (!serverUrl || !token || !deviceId) return null;
    
    const url = new URL(serverUrl);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    url.pathname = url.pathname.replace(/\/$/, '') + '/socket';
    url.searchParams.set('api_key', token);
    url.searchParams.set('deviceId', deviceId);
    return url.toString();
  }

  public connect() {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    
    const socketUrl = this.getSocketUrl();
    if (!socketUrl) {
      this.setState('Disconnected');
      return;
    }

    this.setState('Connecting');
    this.ws = new WebSocket(socketUrl);

    this.ws.onopen = () => {
      this.setState('Connected');
      this.reconnectAttempts = 0;
      void this.sendCapabilities();
    };

    this.ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data) as JellyfinMessage;
        for (const listener of this.messageListeners) {
          listener(message);
        }
      } catch (e) {
        console.error('Failed to parse Jellyfin WebSocket message', e);
      }
    };

    this.ws.onclose = () => {
      this.setState('Disconnected');
      this.ws = null;
      this.scheduleReconnect();
    };

    this.ws.onerror = () => {
      // close event will fire next
    };
  }

  private scheduleReconnect() {
    if (this.reconnectTimeout !== null) return;
    const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000);
    this.reconnectAttempts++;
    this.reconnectTimeout = window.setTimeout(() => {
      this.reconnectTimeout = null;
      this.connect();
    }, delay);
  }

  public destroy() {
    if (this.reconnectTimeout !== null) {
      clearTimeout(this.reconnectTimeout);
      this.reconnectTimeout = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.setState('Disconnected');
  }

  private async sendCapabilities() {
    const serverUrl = getServerUrl();
    const token = getAccessToken();
    if (!serverUrl || !token) return;
    
    try {
      await fetch(`${serverUrl}/Sessions/Capabilities/Full`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `MediaBrowser Token="${token}"`,
        },
        body: JSON.stringify({
          PlayableMediaTypes: ['Video'],
          SupportedCommands: [
            'MoveUp', 'MoveDown', 'MoveLeft', 'MoveRight', 'PageUp', 'PageDown',
            'PreviousLetter', 'NextLetter', 'ToggleOsd', 'ToggleContextMenu', 'Select', 'Back',
            'TakeScreenshot', 'SendKey', 'SendString', 'GoHome', 'GoToSettings', 'VolumeUp',
            'VolumeDown', 'Mute', 'Unmute', 'ToggleMute', 'SetVolume', 'SetAudioStreamIndex',
            'SetSubtitleStreamIndex', 'ToggleFullscreen', 'DisplayContent', 'GoToSearch',
            'DisplayMessage', 'SetRepeatMode', 'ChannelUp', 'ChannelDown', 'Guide', 'ToggleStats',
            'PlayMediaSource', 'PlayTrailers', 'SetShuffleMode', 'PlayState'
          ],
          SupportsMediaControl: true,
          SupportsSyncPlay: true,
          SupportsPersistentIdentifier: true,
        }),
      });
    } catch (e) {
      console.warn('Failed to post session capabilities', e);
    }
  }
}

export const jellyfinSocket = new JellyfinSocketController();
