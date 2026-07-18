import { MusicAssistantManager } from './MusicAssistantManager';
import type { MAManagerState } from './MusicAssistantManager';

export class MusicAssistantUi {
  private manager: MusicAssistantManager;

  private statusButton: HTMLButtonElement | null;
  private statusBadge: HTMLElement | null;

  private settingsDialog: HTMLDialogElement | null;
  private settingsForm: HTMLFormElement | null;
  private serverUrlInput: HTMLInputElement | null;
  private tokenInput: HTMLInputElement | null;
  private sendspinCheckbox: HTMLInputElement | null;
  private playerSelect: HTMLSelectElement | null;
  private settingsCloseBtn: HTMLButtonElement | null;

  private miniPlayer: HTMLElement | null;
  private miniArtwork: HTMLImageElement | null;
  private miniTitle: HTMLElement | null;
  private miniArtist: HTMLElement | null;
  private miniBadge: HTMLElement | null;
  private miniPlaypauseBtn: HTMLButtonElement | null;
  private miniIconPlay: SVGElement | null;
  private miniIconPause: SVGElement | null;
  private miniNextBtn: HTMLButtonElement | null;
  private miniPrevBtn: HTMLButtonElement | null;
  private miniTargetPlayer: HTMLSelectElement | null;
  private miniQueueBtn: HTMLButtonElement | null;
  private miniMuteBtn: HTMLButtonElement | null;
  private miniVolumeInput: HTMLInputElement | null;

  private queueDialog: HTMLDialogElement | null;
  private queueList: HTMLElement | null;
  private queueClearBtn: HTMLButtonElement | null;
  private queueCloseBtn: HTMLButtonElement | null;
  private queueDoneBtn: HTMLButtonElement | null;

  constructor() {
    this.manager = MusicAssistantManager.getInstance();

    this.statusButton = document.querySelector<HTMLButtonElement>('#ma-status-button');
    this.statusBadge = document.querySelector<HTMLElement>('#ma-status-badge');

    this.settingsDialog = document.querySelector<HTMLDialogElement>('#ma-settings-dialog');
    this.settingsForm = document.querySelector<HTMLFormElement>('#ma-settings-form');
    this.serverUrlInput = document.querySelector<HTMLInputElement>('#ma-server-url');
    this.tokenInput = document.querySelector<HTMLInputElement>('#ma-token');
    this.sendspinCheckbox = document.querySelector<HTMLInputElement>('#ma-sendspin-enabled');
    this.playerSelect = document.querySelector<HTMLSelectElement>('#ma-player-select');
    this.settingsCloseBtn = document.querySelector<HTMLButtonElement>('#ma-settings-close');

    this.miniPlayer = document.querySelector<HTMLElement>('#ma-mini-player');
    this.miniArtwork = document.querySelector<HTMLImageElement>('#ma-mini-artwork');
    this.miniTitle = document.querySelector<HTMLElement>('#ma-mini-title');
    this.miniArtist = document.querySelector<HTMLElement>('#ma-mini-artist');
    this.miniBadge = document.querySelector<HTMLElement>('#ma-mini-badge');
    this.miniPlaypauseBtn = document.querySelector<HTMLButtonElement>('#ma-mini-playpause');
    this.miniIconPlay = document.querySelector<SVGElement>('#ma-mini-icon-play');
    this.miniIconPause = document.querySelector<SVGElement>('#ma-mini-icon-pause');
    this.miniNextBtn = document.querySelector<HTMLButtonElement>('#ma-mini-next');
    this.miniPrevBtn = document.querySelector<HTMLButtonElement>('#ma-mini-prev');
    this.miniTargetPlayer = document.querySelector<HTMLSelectElement>('#ma-mini-target-player');
    this.miniQueueBtn = document.querySelector<HTMLButtonElement>('#ma-mini-queue-btn');
    this.miniMuteBtn = document.querySelector<HTMLButtonElement>('#ma-mini-mute-btn');
    this.miniVolumeInput = document.querySelector<HTMLInputElement>('#ma-mini-volume');

    this.queueDialog = document.querySelector<HTMLDialogElement>('#ma-queue-dialog');
    this.queueList = document.querySelector<HTMLElement>('#ma-queue-list');
    this.queueClearBtn = document.querySelector<HTMLButtonElement>('#ma-queue-clear');
    this.queueCloseBtn = document.querySelector<HTMLButtonElement>('#ma-queue-close');
    this.queueDoneBtn = document.querySelector<HTMLButtonElement>('#ma-queue-done');

    this.initEvents();
    this.manager.subscribe((state) => this.renderState(state));
  }

  private initEvents() {
    if (this.statusButton) {
      this.statusButton.onclick = () => this.openSettings();
    }

    if (this.settingsCloseBtn && this.settingsDialog) {
      this.settingsCloseBtn.onclick = () => this.settingsDialog?.close();
    }

    if (this.settingsForm) {
      this.settingsForm.onsubmit = (e) => {
        e.preventDefault();
        this.saveSettings();
        this.settingsDialog?.close();
      };
    }

    if (this.miniPlaypauseBtn) {
      this.miniPlaypauseBtn.onclick = () => void this.manager.playPause();
    }
    if (this.miniNextBtn) {
      this.miniNextBtn.onclick = () => void this.manager.next();
    }
    if (this.miniPrevBtn) {
      this.miniPrevBtn.onclick = () => void this.manager.previous();
    }

    if (this.miniVolumeInput) {
      this.miniVolumeInput.oninput = () => {
        const val = parseInt(this.miniVolumeInput!.value, 10);
        void this.manager.setVolume(val);
      };
    }

    if (this.miniMuteBtn) {
      this.miniMuteBtn.onclick = () => {
        const state = this.manager.getState();
        void this.manager.setMuted(!state.isMuted);
      };
    }

    if (this.miniTargetPlayer) {
      this.miniTargetPlayer.onchange = () => {
        const val = this.miniTargetPlayer!.value;
        if (val) {
          this.manager.selectActivePlayer(val);
        }
      };
    }

    if (this.miniQueueBtn) {
      this.miniQueueBtn.onclick = () => void this.openQueueModal();
    }

    if (this.queueCloseBtn && this.queueDialog) {
      this.queueCloseBtn.onclick = () => this.queueDialog?.close();
    }
    if (this.queueDoneBtn && this.queueDialog) {
      this.queueDoneBtn.onclick = () => this.queueDialog?.close();
    }
    if (this.queueClearBtn) {
      this.queueClearBtn.onclick = () => {
        void this.manager.clearQueue();
        if (this.queueList) this.queueList.innerHTML = '<p style="color: #aeb4be; padding: 12px;">Queue cleared</p>';
      };
    }
  }

  private openSettings() {
    const config = this.manager.getConfig();
    if (this.serverUrlInput) this.serverUrlInput.value = config.serverUrl || '';
    if (this.tokenInput) this.tokenInput.value = config.token || '';
    if (this.sendspinCheckbox) this.sendspinCheckbox.checked = config.sendspinEnabled;

    this.populatePlayerOptions(this.playerSelect, config.preferredPlayerId);
    this.settingsDialog?.showModal();
  }

  private saveSettings() {
    const serverUrl = this.serverUrlInput?.value.trim() || '';
    const token = this.tokenInput?.value.trim() || '';
    const sendspinEnabled = this.sendspinCheckbox?.checked ?? true;
    const preferredPlayerId = this.playerSelect?.value || undefined;

    this.manager.saveConfig({
      serverUrl,
      token,
      sendspinEnabled,
      preferredPlayerId,
    });
  }

  private async openQueueModal() {
    if (!this.queueDialog || !this.queueList) return;
    this.queueList.innerHTML = '<p style="color: #aeb4be; padding: 12px;">Loading queue...</p>';
    this.queueDialog.showModal();

    const items = await this.manager.getQueueItems();
    if (items.length === 0) {
      this.queueList.innerHTML = '<p style="color: #aeb4be; padding: 12px;">Queue is empty</p>';
      return;
    }

    this.queueList.innerHTML = '';
    items.forEach((item, index) => {
      const row = document.createElement('div');
      row.className = 'ma-track-row';
      row.style.fontSize = '0.9rem';

      const num = document.createElement('span');
      num.style.color = '#aeb4be';
      num.textContent = `${index + 1}`;

      const title = document.createElement('div');
      title.style.minWidth = '0';

      const nameSpan = document.createElement('div');
      nameSpan.style.fontWeight = '600';
      nameSpan.style.color = '#f7fafc';
      nameSpan.style.overflow = 'hidden';
      nameSpan.style.textOverflow = 'ellipsis';
      nameSpan.style.whiteSpace = 'nowrap';
      nameSpan.textContent = item.name;

      const subSpan = document.createElement('div');
      subSpan.style.fontSize = '0.8rem';
      subSpan.style.color = '#aeb4be';
      subSpan.textContent = item.media_item?.artists?.map((a) => a.name).join(', ') || '';

      title.appendChild(nameSpan);
      if (subSpan.textContent) title.appendChild(subSpan);

      const duration = document.createElement('span');
      duration.style.color = '#aeb4be';
      duration.textContent = item.duration ? this.formatSeconds(item.duration) : '';

      row.appendChild(num);
      row.appendChild(title);
      row.appendChild(duration);

      this.queueList!.appendChild(row);
    });
  }

  private renderState(state: MAManagerState) {
    if (this.statusBadge) {
      if (state.connected) {
        this.statusBadge.textContent = 'MA ✓';
        this.statusBadge.classList.add('ma-status-badge--connected');
      } else {
        this.statusBadge.textContent = 'MA';
        this.statusBadge.classList.remove('ma-status-badge--connected');
      }
    }

    // Populate dropdowns
    this.populatePlayerOptions(this.miniTargetPlayer, state.activePlayerId);

    // Mini Player visibility & info
    const hasMediaOrActive = state.currentTrackTitle || state.isPlaying || state.connected;
    if (this.miniPlayer) {
      this.miniPlayer.hidden = !hasMediaOrActive;
    }

    if (this.miniTitle) {
      this.miniTitle.textContent = state.currentTrackTitle || 'Music Assistant';
    }
    if (this.miniArtist) {
      this.miniArtist.textContent = state.currentTrackArtist || (state.connected ? 'Connected' : 'Not Connected');
    }
    if (this.miniBadge) {
      this.miniBadge.textContent = state.audioFormatPill || '';
      this.miniBadge.hidden = !state.audioFormatPill;
    }

    if (this.miniArtwork) {
      if (state.currentArtworkUrl) {
        this.miniArtwork.src = state.currentArtworkUrl;
        this.miniArtwork.hidden = false;
      } else {
        this.miniArtwork.hidden = true;
      }
    }

    if (this.miniIconPlay && this.miniIconPause) {
      this.miniIconPlay.style.display = state.isPlaying ? 'none' : 'block';
      this.miniIconPause.style.display = state.isPlaying ? 'block' : 'none';
    }

    if (this.miniVolumeInput) {
      this.miniVolumeInput.value = state.volume.toString();
    }
  }

  private populatePlayerOptions(selectElem: HTMLSelectElement | null, selectedId?: string) {
    if (!selectElem) return;
    const currentVal = selectedId || selectElem.value;
    selectElem.innerHTML = '';

    const players = this.manager.client.currentPlayers;
    if (players.length === 0) {
      const opt = document.createElement('option');
      opt.value = 'sendspin';
      opt.textContent = 'SpatialFin Web Receiver (SendSpin)';
      selectElem.appendChild(opt);
      return;
    }

    for (const player of players) {
      const opt = document.createElement('option');
      opt.value = player.player_id;
      opt.textContent = `${player.name}${player.state === 'playing' ? ' ▶' : ''}`;
      if (player.player_id === currentVal) opt.selected = true;
      selectElem.appendChild(opt);
    }
  }

  private formatSeconds(sec: number): string {
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  }
}
