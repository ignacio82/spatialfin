import * as xb from 'xrblocks';
import { remoteControlCoordinator, type RemoteSession } from './RemoteControlCoordinator';
import { CanvasView, fillRoundedRect } from './CanvasView';

interface RemoteControlCanvasActions {
  selectSession: (id: string | null) => void;
  sendCommand: (command: string) => void;
  close: () => void;
}

class RemoteControlCanvasView extends CanvasView {
  private sessions: RemoteSession[] = [];
  private selectedSessionId: string | null = null;
  private readonly actions: RemoteControlCanvasActions;

  constructor(actions: RemoteControlCanvasActions) {
    super(600, 800);
    this.actions = actions;
    remoteControlCoordinator.addListener(this.handleSessionsUpdate);
  }

  private handleSessionsUpdate = (sessions: RemoteSession[]) => {
    this.sessions = sessions;
    this.selectedSessionId = remoteControlCoordinator.getSelectedSessionId();
    this.redraw();
  };

  override dispose(): void {
    super.dispose();
    remoteControlCoordinator.removeListener(this.handleSessionsUpdate);
  }

  protected override draw() {
    this.hitZones = [];
    this.context.clearRect(0, 0, this.logicalWidth, this.logicalHeight);
    
    // Background
    fillRoundedRect(this.context, '#111318a8', 0, 0, this.logicalWidth, this.logicalHeight, 32);
    
    this.context.fillStyle = '#ffffff';
    this.context.font = '700 36px "Inter", sans-serif';
    this.context.textAlign = 'left';
    this.context.fillText('Network Remote', 40, 60);

    // Close button
    this.hitZones.push({
      id: 'close', x: 500, y: 20, width: 80, height: 80, 
      action: this.actions.close
    });
    this.context.fillStyle = '#ffffff';
    this.context.font = '400 32px sans-serif';
    this.context.textAlign = 'center';
    this.context.fillText('×', 540, 65);

    let y = 120;
    if (this.sessions.length === 0) {
      this.context.fillStyle = '#9ca3af';
      this.context.font = '400 24px "Inter", sans-serif';
      this.context.textAlign = 'center';
      this.context.fillText('No active controllable sessions.', this.logicalWidth / 2, y + 40);
      return;
    }

    // Sessions List
    for (const session of this.sessions) {
      const isSelected = session.Id === this.selectedSessionId;
      
      const bgColor = isSelected ? '#3b82f6' : '#1f2937';
      fillRoundedRect(this.context, bgColor, 40, y, 520, 100, 16);
      
      this.context.fillStyle = '#ffffff';
      this.context.font = '600 24px "Inter", sans-serif';
      this.context.textAlign = 'left';
      this.context.fillText(session.DeviceName || session.Client, 60, y + 40);
      
      this.context.fillStyle = '#9ca3af';
      this.context.font = '400 20px "Inter", sans-serif';
      if (session.NowPlayingItem) {
        this.context.fillText(`Playing: ${session.NowPlayingItem.Name}`, 60, y + 70);
      } else {
        this.context.fillText('Idle', 60, y + 70);
      }

      this.hitZones.push({
        id: `session-${session.Id}`,
        x: 40, y, width: 520, height: 100,
        action: () => this.actions.selectSession(isSelected ? null : session.Id)
      });
      y += 120;
    }

    // Controls for selected session
    if (this.selectedSessionId) {
      y = this.logicalHeight - 200;
      
      fillRoundedRect(this.context, '#1f2937', 40, y, 520, 160, 24);
      
      // Playback Controls
      const btnSize = 64;
      const spacing = 32;
      const startX = 40 + (520 - (btnSize * 4 + spacing * 3)) / 2;
      
      const drawBtn = (icon: string, dx: number, actionStr: string) => {
        fillRoundedRect(this.context, '#374151', dx, y + 48, btnSize, btnSize, btnSize / 2);
        this.context.fillStyle = '#ffffff';
        this.context.font = '32px sans-serif';
        this.context.textAlign = 'center';
        this.context.fillText(icon, dx + btnSize / 2, y + 48 + 42);
        this.hitZones.push({
          id: `btn-${actionStr}`,
          x: dx, y: y + 48, width: btnSize, height: btnSize,
          action: () => this.actions.sendCommand(actionStr)
        });
      };

      drawBtn('⏮', startX, 'PreviousTrack');
      drawBtn('⏯', startX + btnSize + spacing, 'PlayPause');
      drawBtn('⏹', startX + (btnSize + spacing) * 2, 'Stop');
      drawBtn('⏭', startX + (btnSize + spacing) * 3, 'NextTrack');
    }
  }
}

export class RemoteControlSpace extends xb.Script {
  private canvas: RemoteControlCanvasView | null = null;
  private disposed = false;

  override init() {
    this.name = 'SpatialFin Remote Control';
    this.disposed = false;

    const panel = new xb.SpatialPanel({
      width: 0.6,
      height: 0.8,
      backgroundColor: '#00000000',
      borderWidth: 0,
      showHighlights: true,
      dragFacingCamera: true,
    });
    panel.position.set(0.8, Math.max(xb.user.height - 0.2, 1.3), -1.2);
    panel.rotation.y = -Math.PI / 6;

    this.canvas = new RemoteControlCanvasView({
      selectSession: (id) => {
        remoteControlCoordinator.selectSession(id);
        // Relying on handleSessionsUpdate to redraw if sessions update
        // But since we just selected locally, trigger fetch manually to redraw or just wait
        void remoteControlCoordinator.fetchSessions();
      },
      sendCommand: (command) => {
        void remoteControlCoordinator.sendCommand(command);
      },
      close: () => {
        this.removeFromParent();
      }
    });
    
    panel.add(this.canvas);
    this.add(panel);
  }

  override dispose() {
    if (this.disposed) return;
    this.disposed = true;
    this.canvas?.dispose();
  }
}
