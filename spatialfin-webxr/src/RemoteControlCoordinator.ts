import { getAuthHeaders, getServerUrl, getDeviceId } from './auth';
import { jellyfinSocket, type JellyfinMessage } from './JellyfinSocket';

export interface RemoteSession {
  Id: string;
  DeviceName: string;
  Client: string;
  SupportsRemoteControl: boolean;
  NowPlayingItem?: {
    Id: string;
    Name: string;
  };
}

export class RemoteControlCoordinator {
  private sessions: RemoteSession[] = [];
  private listeners: Set<(sessions: RemoteSession[]) => void> = new Set();
  private selectedSessionId: string | null = null;
  
  constructor() {
    jellyfinSocket.addMessageListener(this.handleSocketMessage.bind(this));
    setInterval(() => void this.fetchSessions(), 10000);
    void this.fetchSessions();
  }

  public addListener(listener: (sessions: RemoteSession[]) => void) {
    this.listeners.add(listener);
    listener(this.sessions);
  }

  public removeListener(listener: (sessions: RemoteSession[]) => void) {
    this.listeners.delete(listener);
  }

  public selectSession(sessionId: string | null) {
    this.selectedSessionId = sessionId;
  }

  public getSelectedSessionId(): string | null {
    return this.selectedSessionId;
  }

  private updateSessions(allSessions: any[]) {
    const ownDeviceId = getDeviceId();
    this.sessions = allSessions.filter((session: any) => 
      session.Id && 
      session.SupportsRemoteControl && 
      session.NowPlayingItem && 
      session.DeviceId !== ownDeviceId
    );
    
    if (this.selectedSessionId && !this.sessions.find(s => s.Id === this.selectedSessionId)) {
      this.selectedSessionId = null;
    }

    for (const listener of this.listeners) {
      listener(this.sessions);
    }
  }

  public async fetchSessions() {
    const serverUrl = getServerUrl();
    if (!serverUrl) return;

    try {
      const response = await fetch(`${serverUrl}/Sessions?ActiveWithinSeconds=960`, {
        headers: getAuthHeaders(true)
      });
      if (response.ok) {
        const data = await response.json();
        this.updateSessions(data);
      }
    } catch (e) {
      console.warn('Failed to fetch sessions', e);
    }
  }

  private handleSocketMessage(message: JellyfinMessage) {
    if (message.MessageType === 'Sessions') {
      this.updateSessions(message.Data);
    }
  }

  public async sendCommand(command: string) {
    if (!this.selectedSessionId) return;
    const serverUrl = getServerUrl();
    if (!serverUrl) return;

    try {
      await fetch(`${serverUrl}/Sessions/${this.selectedSessionId}/Playing/${command}`, {
        method: 'POST',
        headers: getAuthHeaders(true)
      });
    } catch (e) {
      console.warn(`Failed to send playstate command ${command}`, e);
    }
  }

  public async sendGeneralCommand(command: string, args: Record<string, string> = {}) {
    if (!this.selectedSessionId) return;
    const serverUrl = getServerUrl();
    if (!serverUrl) return;

    try {
      await fetch(`${serverUrl}/Sessions/${this.selectedSessionId}/Command`, {
        method: 'POST',
        headers: getAuthHeaders(true),
        body: JSON.stringify({ Name: command, Arguments: args })
      });
    } catch (e) {
      console.warn(`Failed to send general command ${command}`, e);
    }
  }
}

export const remoteControlCoordinator = new RemoteControlCoordinator();
