import {
  nextUpmixMode,
  TheaterAvrClient,
  type TheaterAvrState,
  upmixModeLabel,
} from './TheaterAvrClient.ts';

/**
 * DOM controller mounting SpatialFin AVR (Theater Audio) pairing, room selection, and live room
 * controls inside `#cast-selection-dialog` using safe DOM APIs (`createElement` / `textContent`).
 */
export class TheaterAvrUi {
  private readonly client: TheaterAvrClient;
  private readonly container: HTMLElement | null;
  private statusText = '';
  private errorText = '';
  private busy = false;
  private hostDraft = '';
  private pinDraft = '';
  private tokenDraft = '';

  constructor(client: TheaterAvrClient, containerSelector = '#theater-avr-section') {
    this.client = client;
    this.container = document.querySelector<HTMLElement>(containerSelector);
    this.hostDraft = client.getState().baseUrl;
    this.client.subscribe((state) => this.render(state));
  }

  refresh() {
    this.render(this.client.getState());
  }

  private render(state: TheaterAvrState) {
    const root = this.container;
    if (!root) return;
    root.replaceChildren();

    const heading = document.createElement('h3');
    heading.className = 'theater-avr-heading';
    heading.textContent = 'Theater Audio (SpatialFin AVR)';
    root.appendChild(heading);

    if (window.location.protocol === 'https:') {
      const hint = document.createElement('p');
      hint.className = 'theater-avr-hint';
      hint.textContent =
        'HTTPS mode: connect via a browser-trusted HTTPS AVR URL (SPATIALFIN_AVR_USE_TLS=true + PEM cert or Tailscale Serve) with SPATIALFIN_AVR_CORS_ORIGINS=' +
        window.location.origin;
      root.appendChild(hint);
    }

    if (state.baseUrl && state.token) {
      const statusRow = document.createElement('div');
      statusRow.className = 'theater-avr-status-row';

      const badge = document.createElement('span');
      badge.className = 'theater-avr-badge';
      if (state.fallbackActive) {
        badge.textContent = 'Bed Fallback';
      } else if (state.rendererMode === 'truehd_vbap') {
        badge.textContent =
          state.activeOamdObjects && state.activeOamdObjects > 0
            ? `Atmos VBAP · ${state.activeOamdObjects} obj`
            : 'Atmos VBAP';
      } else if (state.connected) {
        badge.textContent = state.activeDecodeChain ?? 'Streaming';
      } else {
        badge.textContent = state.enabled ? 'Ready' : 'Local Audio';
      }
      statusRow.appendChild(badge);

      const serverLabel = document.createElement('span');
      serverLabel.className = 'theater-avr-server-label';
      serverLabel.textContent = state.baseUrl;
      statusRow.appendChild(serverLabel);
      root.appendChild(statusRow);

      // Room picker buttons
      const roomList = document.createElement('div');
      roomList.className = 'theater-avr-room-list';

      const localBtn = document.createElement('button');
      localBtn.type = 'button';
      localBtn.className = `theater-avr-room-btn${!state.enabled ? ' is-selected' : ''}`;
      localBtn.textContent = `${!state.enabled ? '✓ ' : ''}This Browser (Local Audio)`;
      localBtn.onclick = () => this.client.selectRoom(null);
      roomList.appendChild(localBtn);

      state.rooms.forEach((room) => {
        const selected = state.enabled && state.selectedRoomId === room.room_id;
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = `theater-avr-room-btn${selected ? ' is-selected' : ''}`;
        const layoutSuffix = room.layout ? ` (${room.layout})` : '';
        btn.textContent = `${selected ? '✓ ' : ''}${room.room_name}${layoutSuffix}`;
        btn.onclick = () => this.client.selectRoom(room.room_id);
        roomList.appendChild(btn);
      });
      root.appendChild(roomList);

      // Quick room DSP/volume controls
      const controls = document.createElement('div');
      controls.className = 'theater-avr-controls';

      const volSlider = document.createElement('input');
      volSlider.type = 'range';
      volSlider.min = '0';
      volSlider.max = '100';
      volSlider.value = String(Math.round(state.masterVolume * 100));
      volSlider.setAttribute('aria-label', 'Theater AVR volume');
      volSlider.onchange = () => {
        const nextVol = Math.max(0, Math.min(1, Number(volSlider.value) / 100));
        void this.client.updateRoomControls({ master_volume: nextVol, muted: false }).catch((err) => {
          this.errorText = err instanceof Error ? err.message : String(err);
          this.render(this.client.getState());
        });
      };
      controls.appendChild(volSlider);

      const muteBtn = document.createElement('button');
      muteBtn.type = 'button';
      muteBtn.className = 'theater-avr-chip-btn';
      muteBtn.textContent = state.muted ? 'Muted' : `Vol ${Math.round(state.masterVolume * 100)}%`;
      muteBtn.onclick = () => {
        void this.client.updateRoomControls({ muted: !state.muted }).catch((err) => {
          this.errorText = err instanceof Error ? err.message : String(err);
          this.render(this.client.getState());
        });
      };
      controls.appendChild(muteBtn);

      const eqBtn = document.createElement('button');
      eqBtn.type = 'button';
      eqBtn.className = 'theater-avr-chip-btn';
      eqBtn.textContent = state.eqBypass ? 'EQ: Bypass' : 'EQ: On';
      eqBtn.onclick = () => {
        void this.client.updateRoomControls({ eq_bypass: !state.eqBypass }).catch((err) => {
          this.errorText = err instanceof Error ? err.message : String(err);
          this.render(this.client.getState());
        });
      };
      controls.appendChild(eqBtn);

      const upmixBtn = document.createElement('button');
      upmixBtn.type = 'button';
      upmixBtn.className = 'theater-avr-chip-btn';
      upmixBtn.textContent = upmixModeLabel(state.upmixMode);
      upmixBtn.onclick = () => {
        const next = nextUpmixMode(state.upmixMode);
        void this.client.updateRoomControls({ upmix_mode: next }).catch((err) => {
          this.errorText = err instanceof Error ? err.message : String(err);
          this.render(this.client.getState());
        });
      };
      controls.appendChild(upmixBtn);

      const refreshBtn = document.createElement('button');
      refreshBtn.type = 'button';
      refreshBtn.className = 'theater-avr-chip-btn';
      refreshBtn.textContent = 'Refresh Rooms';
      refreshBtn.onclick = () => {
        void this.client.refreshRooms().catch((err) => {
          this.errorText = err instanceof Error ? err.message : String(err);
          this.render(this.client.getState());
        });
      };
      controls.appendChild(refreshBtn);

      const forgetBtn = document.createElement('button');
      forgetBtn.type = 'button';
      forgetBtn.className = 'theater-avr-chip-btn is-danger';
      forgetBtn.textContent = 'Unpair';
      forgetBtn.onclick = () => {
        this.statusText = '';
        this.errorText = '';
        this.client.disconnectAndForget();
      };
      controls.appendChild(forgetBtn);

      root.appendChild(controls);
    } else {
      // Manual host + PIN / Token pairing form
      const formWrap = document.createElement('div');
      formWrap.className = 'theater-avr-form';

      const hostInput = document.createElement('input');
      hostInput.type = 'text';
      hostInput.placeholder =
        window.location.protocol === 'https:' ? 'https://avr.lan:1987' : '192.168.1.90:1986';
      hostInput.value = this.hostDraft;
      hostInput.oninput = () => {
        this.hostDraft = hostInput.value;
      };
      formWrap.appendChild(hostInput);

      const pinInput = document.createElement('input');
      pinInput.type = 'text';
      pinInput.inputMode = 'numeric';
      pinInput.maxLength = 6;
      pinInput.placeholder = '6-digit pairing PIN';
      pinInput.value = this.pinDraft;
      pinInput.oninput = () => {
        this.pinDraft = pinInput.value.replace(/\D/g, '').slice(0, 6);
        pinInput.value = this.pinDraft;
      };
      formWrap.appendChild(pinInput);

      const tokenInput = document.createElement('input');
      tokenInput.type = 'password';
      tokenInput.placeholder = 'Or paste Bearer token (optional)';
      tokenInput.value = this.tokenDraft;
      tokenInput.oninput = () => {
        this.tokenDraft = tokenInput.value;
      };
      formWrap.appendChild(tokenInput);

      const pairBtn = document.createElement('button');
      pairBtn.type = 'button';
      pairBtn.className = 'connect-button';
      pairBtn.disabled = this.busy;
      pairBtn.textContent = this.busy ? 'Connecting...' : 'Connect Theater Audio';
      pairBtn.onclick = async () => {
        this.busy = true;
        this.errorText = '';
        this.statusText = '';
        this.render(this.client.getState());
        try {
          if (this.pinDraft.length === 6) {
            await this.client.pairWithPin(this.hostDraft, this.pinDraft);
            this.pinDraft = '';
            this.statusText = 'Paired with spatialfin-avr.';
          } else if (this.tokenDraft.trim()) {
            await this.client.connectWithToken(this.hostDraft, this.tokenDraft.trim());
            this.tokenDraft = '';
            this.statusText = 'Connected to spatialfin-avr.';
          } else {
            this.errorText = 'Enter a 6-digit PIN or Bearer token.';
          }
        } catch (err) {
          this.errorText = err instanceof Error ? err.message : String(err);
        } finally {
          this.busy = false;
          this.render(this.client.getState());
        }
      };
      formWrap.appendChild(pairBtn);
      root.appendChild(formWrap);
    }

    const displayErr = this.errorText || state.lastError;
    if (displayErr) {
      const errEl = document.createElement('p');
      errEl.className = 'theater-avr-error';
      errEl.textContent = displayErr;
      root.appendChild(errEl);
    } else if (this.statusText) {
      const okEl = document.createElement('p');
      okEl.className = 'theater-avr-status-ok';
      okEl.textContent = this.statusText;
      root.appendChild(okEl);
    }
  }
}
