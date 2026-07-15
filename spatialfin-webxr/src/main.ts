import 'xrblocks/addons/simulator/SimulatorAddons.js';
import * as xb from 'xrblocks';
import { Html5Qrcode } from 'html5-qrcode';
import {
  getGeminiKey,
  getServerUrl,
  isAuthenticated,
  JellyfinAuthError,
  login,
  loginViaCompanion,
  loginViaCompanionSetupToken,
  logout,
  normalizeServerUrl,
  validateSession,
  saveSession,
  getCompanionUrl,
} from './auth';
import type { AuthSession } from './auth';
import { FCastClient } from './fcast';
import {
  browserClassifiesAddressSpace,
  createJellyfinRequest,
  localNetworkTargetForRequest,
} from './network';
import {HomeSpace} from './HomeSpace';
import {BrowserApp} from './BrowserApp';
import './style.css';

const loginOverlay = document.querySelector<HTMLElement>('#login-overlay');
const loginForm = document.querySelector<HTMLFormElement>('#login-form');
const companionLoginForm = document.querySelector<HTMLFormElement>('#companion-login-form');
const companionIpInput = document.querySelector<HTMLInputElement>('#companion-ip');
const scanQrButton = document.querySelector<HTMLButtonElement>('#scan-qr-button');
const qrReaderElement = document.querySelector<HTMLElement>('#qr-reader');
const serverInput = document.querySelector<HTMLInputElement>('#server');
const usernameInput = document.querySelector<HTMLInputElement>('#username');
const passwordInput = document.querySelector<HTMLInputElement>('#password');
const geminiInput = document.querySelector<HTMLInputElement>('#gemini');
const rememberInput = document.querySelector<HTMLInputElement>('#remember');
const submitButton = document.querySelector<HTMLButtonElement>('#connect-button');
const errorMessage = document.querySelector<HTMLElement>('#login-error');
const statusMessage = document.querySelector<HTMLElement>('#login-status');
const sessionBar = document.querySelector<HTMLElement>('#session-bar');
const sessionServer = document.querySelector<HTMLElement>('#session-server');
const signOutButton = document.querySelector<HTMLButtonElement>('#sign-out-button');
const browserSignOutButton = document.querySelector<HTMLButtonElement>('#browser-sign-out-button');
const enterXrButton = document.querySelector<HTMLButtonElement>('#enter-xr-button');
const switchUserButton = document.querySelector<HTMLButtonElement>('#switch-user-button');
const browserSwitchUserButton = document.querySelector<HTMLButtonElement>('#browser-switch-user-button');

const userSelectionDialog = document.querySelector<HTMLDialogElement>('#user-selection-dialog');
const userSelectionList = document.querySelector<HTMLElement>('#user-selection-list');
const cancelUserSelectionButton = document.querySelector<HTMLButtonElement>('#cancel-user-selection');

const castButton = document.querySelector<HTMLButtonElement>('#browser-cast-button');
const receiveButton = document.querySelector<HTMLButtonElement>('#browser-receive-button');
const castSelectionDialog = document.querySelector<HTMLDialogElement>('#cast-selection-dialog');
const castSelectionList = document.querySelector<HTMLElement>('#cast-selection-list');
const cancelCastSelectionButton = document.querySelector<HTMLButtonElement>('#cancel-cast-selection');

let currentFCastClient: FCastClient | null = null;
let currentReceiverWs: WebSocket | null = null;

const browserApp = new BrowserApp();

let xrStartPromise: Promise<void> | null = null;
const automationMode = new URLSearchParams(window.location.search).has('xrAutomation');

if (automationMode) {
  (window as typeof window & {
    __spatialfinNetworkTest?: {
      browserClassifiesAddressSpace: typeof browserClassifiesAddressSpace;
      createJellyfinRequest: typeof createJellyfinRequest;
      localNetworkTargetForRequest: typeof localNetworkTargetForRequest;
      normalizeServerUrl: typeof normalizeServerUrl;
    };
  }).__spatialfinNetworkTest = {
    browserClassifiesAddressSpace,
    createJellyfinRequest,
    localNetworkTargetForRequest,
    normalizeServerUrl,
  };
}

function setXrButtonState(label: string, disabled: boolean) {
  if (!enterXrButton) return;
  enterXrButton.textContent = label;
  enterXrButton.disabled = disabled;
}

async function exitXrSession() {
  const session = xb.core.renderer.xr.getSession();
  if (session) await session.end();
}

window.addEventListener('spatialfin:exit-xr', () => {
  void exitXrSession()
    .catch((error) => console.warn('Could not exit XR session:', error))
    .finally(() => void showBrowserApp());
});

function setBusy(busy: boolean, message = '') {
  if (submitButton) {
    submitButton.disabled = busy;
    submitButton.textContent = busy ? 'Connecting...' : 'Connect';
  }
  if (statusMessage) {
    statusMessage.textContent = message;
    statusMessage.hidden = !message;
  }
}

function showError(message: string) {
  if (!errorMessage) return;
  errorMessage.textContent = message;
  errorMessage.hidden = false;
}

function clearError() {
  if (!errorMessage) return;
  errorMessage.textContent = '';
  errorMessage.hidden = true;
}

if (cancelCastSelectionButton && castSelectionDialog) {
  cancelCastSelectionButton.onclick = () => {
    castSelectionDialog.close();
  };
}

if (castButton) {
  castButton.onclick = async () => {
    if (currentFCastClient) {
      currentFCastClient.disconnect();
      currentFCastClient = null;
      castButton.textContent = 'Cast';
      return;
    }

    const companionUrl = getCompanionUrl();
    if (!companionUrl) {
      alert('You must connect via the Companion App to discover Cast receivers.');
      return;
    }
    
    castButton.textContent = 'Searching...';
    try {
      const res = await fetch(`${companionUrl}/api/fcast/receivers`);
      const receivers = await res.json();
      
      if (castSelectionList && castSelectionDialog) {
        castSelectionList.innerHTML = '';
        receivers.forEach((receiver: any) => {
          const btn = document.createElement('button');
          btn.type = 'button';
          btn.className = 'connect-button';
          btn.style.marginTop = '8px';
          btn.textContent = `${receiver.name}`;
          btn.onclick = () => {
            castSelectionDialog.close();
            connectToReceiver(receiver.ip, receiver.port, receiver.name);
          };
          castSelectionList.appendChild(btn);
        });
        
        if (receivers.length === 0) {
          const msg = document.createElement('p');
          msg.textContent = 'No receivers found on the local network.';
          castSelectionList.appendChild(msg);
        }
        
        castSelectionDialog.showModal();
      }
    } catch (e) {
      alert('Failed to fetch receivers: ' + e);
    } finally {
      castButton.textContent = 'Cast';
    }
  };
}

if (receiveButton) {
  receiveButton.onclick = () => {
    if (currentReceiverWs) {
      currentReceiverWs.close();
      currentReceiverWs = null;
      receiveButton.textContent = 'Enable Receiver';
      return;
    }
    const companionUrlStr = getCompanionUrl();
    if (!companionUrlStr) {
      alert('You must connect via the Companion App to act as a receiver.');
      return;
    }
    
    receiveButton.textContent = 'Connecting...';
    try {
      const companionUrl = new URL(companionUrlStr);
      // Ensure we use ws or wss based on the companion url protocol
      const protocol = companionUrl.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = `${protocol}//${companionUrl.host}/api/fcast/receive`;
      currentReceiverWs = new WebSocket(wsUrl);
      currentReceiverWs.binaryType = 'arraybuffer';
      
      let playbackUpdateInterval = 0;
      let splitAvRole: string | undefined;

      const sendFcastMessage = (opcode: number, payloadObj?: any) => {
        if (!currentReceiverWs || currentReceiverWs.readyState !== WebSocket.OPEN) return;
        if (payloadObj) {
          const jsonStr = JSON.stringify(payloadObj);
          const jsonBytes = new TextEncoder().encode(jsonStr);
          const buffer = new Uint8Array(1 + jsonBytes.length);
          buffer[0] = opcode;
          buffer.set(jsonBytes, 1);
          currentReceiverWs.send(buffer);
        } else {
          currentReceiverWs.send(new Uint8Array([opcode]));
        }
      };

      const startBeacons = (syncCadenceHz: number = 1) => {
        if (playbackUpdateInterval) window.clearInterval(playbackUpdateInterval);
        const video = document.querySelector<HTMLVideoElement>('#browser-video');
        playbackUpdateInterval = window.setInterval(() => {
          if (!video) return;
          sendFcastMessage(6, {
            generationTime: Date.now(),
            state: video.paused ? 2 : 1,
            time: video.currentTime,
            duration: video.duration || 0,
            speed: video.playbackRate,
            monotonicSampleMs: Math.round(performance.now())
          });
        }, 1000 / syncCadenceHz);
      };

      const stopBeacons = () => {
        if (playbackUpdateInterval) {
          window.clearInterval(playbackUpdateInterval);
          playbackUpdateInterval = 0;
        }
      };
      
      currentReceiverWs.onopen = () => {
        receiveButton.textContent = 'Disable Receiver';
      };
      
      currentReceiverWs.onclose = () => {
        currentReceiverWs = null;
        stopBeacons();
        if (receiveButton) receiveButton.textContent = 'Enable Receiver';
        
        // Ensure UI is torn down on disconnect
        const video = document.querySelector<HTMLVideoElement>('#browser-video');
        const player = document.querySelector<HTMLElement>('#browser-player');
        const audioOverlay = document.querySelector<HTMLElement>('#audio-receiver-overlay');
        if (video) { video.pause(); video.src = ''; video.style.visibility = ''; video.style.position = ''; }
        if (player) player.hidden = true;
        if (audioOverlay) audioOverlay.hidden = true;
      };
      
      currentReceiverWs.onmessage = (event) => {
        const data = event.data;
        if (data instanceof ArrayBuffer) {
          const view = new Uint8Array(data);
          if (view.length === 0) return;
          const opcode = view[0];
          let payload: any = null;
          if (view.length > 1) {
            try {
              const jsonStr = new TextDecoder().decode(view.subarray(1));
              if (jsonStr.trim()) payload = JSON.parse(jsonStr);
            } catch (e) {
              console.error("Error decoding receiver payload", e);
            }
          }

          const video = document.querySelector<HTMLVideoElement>('#browser-video');
          const player = document.querySelector<HTMLElement>('#browser-player');
          const audioOverlay = document.querySelector<HTMLElement>('#audio-receiver-overlay');
          
          if (opcode === 1 && payload) {
            // Opcode 1: Play
            console.log("Received PLAY command:", JSON.stringify(payload, null, 2));
            if (video) {
              video.src = payload.url;
            }
            
            splitAvRole = payload.metadata?.custom?.splitAv?.role?.toLowerCase();
            console.log("Split A/V role:", splitAvRole);
            
            if (splitAvRole === 'audio') {
              // ── Audio-only mode: show receiver overlay, hide video player ──
              if (player) player.hidden = true;
              if (video) {
                video.muted = false;
                video.style.visibility = 'hidden';
                video.style.position = 'absolute';
              }
              
              if (audioOverlay) {
                audioOverlay.hidden = false;
                
                // Title
                const titleEl = document.getElementById('audio-receiver-title');
                if (titleEl) titleEl.textContent = payload.metadata?.title || 'Cast Audio';
                
                // Thumbnail backdrop
                const bgEl = audioOverlay.querySelector<HTMLElement>('.audio-receiver-bg');
                const artworkEl = document.getElementById('audio-receiver-artwork');
                const thumbUrl = payload.metadata?.image;
                if (thumbUrl && bgEl) {
                  bgEl.style.backgroundImage = `url(${thumbUrl})`;
                }
                if (thumbUrl && artworkEl) {
                  artworkEl.innerHTML = `<img src="${thumbUrl}" alt="Artwork" />`;
                }
                
                // Codec badge
                const codecBadge = document.getElementById('audio-receiver-codec-badge');
                if (codecBadge) {
                  const audio = payload.metadata?.custom?.audio;
                  if (audio) {
                    const codec = (audio.sourceCodec || '').toUpperCase();
                    const mode = audio.transcoded ? 'Transcode' : 'Direct Play';
                    const lang = (audio.preferredLanguage || '').toUpperCase();
                    codecBadge.textContent = [codec, mode, lang].filter(Boolean).join(' · ');
                  } else {
                    codecBadge.textContent = '';
                  }
                }
                
                // Status badge — playing
                const statusBadge = document.getElementById('audio-receiver-status-badge');
                if (statusBadge) {
                  statusBadge.className = 'audio-receiver-badge audio-receiver-badge--status';
                  statusBadge.innerHTML = '<span class="audio-receiver-pulse"></span> Playing';
                }
                
                // Stop button
                const stopBtn = document.getElementById('audio-receiver-stop');
                if (stopBtn) {
                  stopBtn.onclick = () => {
                    sendFcastMessage(4); // Stop opcode
                    if (video) { video.pause(); video.src = ''; }
                    audioOverlay.hidden = true;
                    if (video) { video.style.visibility = ''; video.style.position = ''; }
                  };
                }
              }
              
              const syncCadenceHz = payload.metadata?.custom?.splitAv?.syncCadenceHz || 1;
              startBeacons(syncCadenceHz);
              if (video) video.play().catch(e => console.error("Play failed", e));
              
            } else {
              // ── Video mode (normal cast or video-only split) ──
              if (audioOverlay) audioOverlay.hidden = true;
              if (player) player.hidden = false;
              if (video) {
                video.style.visibility = '';
                video.style.position = '';
                video.muted = splitAvRole === 'video';
              }
              const playerTitle = document.querySelector<HTMLElement>('#browser-player-title');
              if (playerTitle) playerTitle.textContent = payload.metadata?.title || 'Unknown Cast Media';
              
              const syncCadenceHz = payload.metadata?.custom?.splitAv?.syncCadenceHz || 1;
              startBeacons(syncCadenceHz);
              if (video) video.play().catch(e => console.error("Play failed", e));
            }
            
          } else if (opcode === 2) {
            // Pause
            console.log("Received PAUSE");
            if (video) {
              video.pause();
              video.muted = true;
            }
            const statusBadge = document.getElementById('audio-receiver-status-badge');
            if (statusBadge && audioOverlay && !audioOverlay.hidden) {
              statusBadge.className = 'audio-receiver-badge audio-receiver-badge--status audio-receiver-badge--paused';
              statusBadge.innerHTML = '<span class="audio-receiver-pulse"></span> Paused';
            }
          } else if (opcode === 3) {
            // Resume
            console.log("Received RESUME");
            if (video) {
              video.muted = splitAvRole === 'video';
              video.play().catch(e => console.error(e));
            }
            const statusBadge = document.getElementById('audio-receiver-status-badge');
            if (statusBadge && audioOverlay && !audioOverlay.hidden) {
              statusBadge.className = 'audio-receiver-badge audio-receiver-badge--status';
              statusBadge.innerHTML = '<span class="audio-receiver-pulse"></span> Playing';
            }
          } else if (opcode === 4) {
            // Stop
            console.log("Received STOP");
            stopBeacons();
            if (video) { video.pause(); video.src = ''; video.style.visibility = ''; video.style.position = ''; }
            if (player) player.hidden = true;
            if (audioOverlay) audioOverlay.hidden = true;
          } else if (opcode === 5 && payload) {
            // Seek
            console.log("Received SEEK:", payload.time);
            if (video && payload.time !== undefined) video.currentTime = payload.time;
          } else if (opcode === 12 && payload) {
            // Ping
            const t2 = Math.round(performance.now());
            sendFcastMessage(13, {
              t1: payload.t1,
              t2: t2,
              t3: Math.round(performance.now())
            });
          }
        }
      };
    } catch (e) {
      console.error(e);
      alert('Failed to start receiver connection.');
      receiveButton.textContent = 'Enable Receiver';
    }
  };
}

function connectToReceiver(ip: string, port: number, name: string) {
  if (currentFCastClient) {
    currentFCastClient.disconnect();
  }
  currentFCastClient = new FCastClient(ip, port);
  currentFCastClient.onConnect = () => {
    if (castButton) {
      castButton.textContent = `Disconnect (${name})`;
    }
  };
  currentFCastClient.onDisconnect = () => {
    currentFCastClient = null;
    if (castButton) {
      castButton.textContent = 'Cast';
    }
  };
  currentFCastClient.onError = (err) => {
    console.error('FCast error', err);
    alert('Lost connection to the Cast receiver.');
  };
  currentFCastClient.connect();
}

browserApp.onPlayRequest = (item) => {
  if (currentFCastClient && currentFCastClient['ws']?.readyState === WebSocket.OPEN) {
    const serverUrl = getServerUrl();
    const sessionStr = sessionStorage.getItem('spatialfin_session') || localStorage.getItem('spatialfin_session');
    let accessToken = '';
    if (sessionStr) {
      try {
        accessToken = JSON.parse(sessionStr).accessToken;
      } catch (e) {}
    }
    
    // Construct the stream URL
    let streamUrl = `${serverUrl}/Videos/${item.Id}/stream?static=true`;
    
    currentFCastClient.sendMessage(1, {
      container: 'video/mp4',
      url: streamUrl,
      headers: {
        'X-Emby-Token': accessToken,
      },
      metadata: {
        type: 1, // Video
        title: item.Name,
      }
    });
    return true; // Handled by FCast
  }
  return false;
};

function showLogin() {
  if (loginOverlay) loginOverlay.hidden = false;
  if (sessionBar) sessionBar.hidden = true;
  serverInput?.focus();
}

async function showBrowserApp() {
  if (loginOverlay) loginOverlay.hidden = true;
  if (sessionBar) sessionBar.hidden = true;

  const serverUrl = getServerUrl();
  if (sessionServer && serverUrl) {
    try {
      const url = new URL(serverUrl);
      sessionServer.textContent = `${url.host}${url.pathname.replace(/\/$/, '')}`;
    } catch {
      sessionServer.textContent = serverUrl;
    }
  }

  const hasCompanion = !!getCompanionUrl();
  if (switchUserButton) switchUserButton.hidden = !hasCompanion;
  if (browserSwitchUserButton) browserSwitchUserButton.hidden = !hasCompanion;

  const castButton = document.getElementById('browser-cast-button') as HTMLButtonElement | null;
  const receiveButton = document.getElementById('browser-receive-button') as HTMLButtonElement | null;
  const castTooltipContainer = document.getElementById('cast-tooltip-container');
  const receiveTooltipContainer = document.getElementById('receive-tooltip-container');

  if (castButton && castTooltipContainer) {
    castButton.disabled = !hasCompanion;
    if (!hasCompanion) {
      castTooltipContainer.classList.add('disabled-mode');
    } else {
      castTooltipContainer.classList.remove('disabled-mode');
    }
  }

  if (receiveButton && receiveTooltipContainer) {
    receiveButton.disabled = !hasCompanion;
    if (!hasCompanion) {
      receiveTooltipContainer.classList.add('disabled-mode');
    } else {
      receiveTooltipContainer.classList.remove('disabled-mode');
    }
  }

  // Initialize the renderer and WebXR capability check in the background.
  // That leaves this page fully usable as a normal web app while ensuring the
  // single Enter XR click can call requestSession within the user's gesture.
  if (!automationMode) void prepareXr();
  await browserApp.show();
}

async function startXR() {
  if (xrStartPromise) return xrStartPromise;

  xrStartPromise = (async () => {
    (window as typeof window & {xb?: typeof xb}).xb = xb;

    const options = new xb.Options();
    options.enableUI();
    // SpatialFin owns the browser-facing entry point. Leaving the stock
    // XRBlocks button enabled creates a second, redundant Enter XR click.
    options.xrButton.enabled = false;
    options.setAppTitle('SpatialFin WebXR');
    options.controllers.visualizeRays = true;
    options.reticles.enabled = true;
    options.simulator.defaultMode = xb.SimulatorMode.POSE;

    const geminiKey = getGeminiKey();
    if (geminiKey) {
      options.enableAI();
      options.ai.gemini.apiKey = geminiKey;
    }

    xb.add(new HomeSpace());
    await xb.init(options);
  })();

  try {
    await xrStartPromise;
  } catch (error) {
    xrStartPromise = null;
    throw error;
  }
}

async function prepareXr() {
  setXrButtonState('Preparing XR…', true);
  try {
    await startXR();
    const supported = xb.core.webXRSessionManager?.isXRSupported();
    // The simulator is already running in automation mode even though the
    // browser itself has no immersive-vr device to report.
    setXrButtonState(supported || automationMode ? 'Enter XR' : 'XR unavailable', !supported && !automationMode);
  } catch (error) {
    setXrButtonState('XR unavailable', true);
    console.warn('XR could not initialize:', error);
  }
}

function enterXr() {
  if (automationMode) {
    browserApp.hide();
    void startXR().catch((error) => {
      void showBrowserApp();
      showError(`XRBlocks could not start: ${error instanceof Error ? error.message : String(error)}`);
    });
    return;
  }
  const manager = xb.core.webXRSessionManager;
  if (!manager) return;
  if (manager.isXRSupported() !== true) return;
  try {
    // This intentionally remains synchronous with the click handler: WebXR
    // session requests require a browser user activation.
    manager.startSession();
    browserApp.hide();
  } catch (error) {
    void showBrowserApp();
    showError(`Could not enter XR: ${error instanceof Error ? error.message : String(error)}`);
  }
}

async function handleLogin(event: SubmitEvent) {
  event.preventDefault();
  if (!serverInput || !usernameInput || !passwordInput) return;

  clearError();
  setBusy(true, 'Authenticating with Jellyfin...');

  try {
    await login(
      serverInput.value,
      usernameInput.value,
      passwordInput.value,
      geminiInput?.value,
      rememberInput?.checked ?? false,
    );
    passwordInput.value = '';
    if (geminiInput) geminiInput.value = '';
    await showBrowserApp();
  } catch (error) {
    showLogin();
    showError(error instanceof Error ? error.message : 'Unable to connect to Jellyfin.');
  } finally {
    setBusy(false);
  }
}

function promptUserSelection(sessions: AuthSession[]): Promise<AuthSession> {
  return new Promise((resolve, reject) => {
    if (!userSelectionDialog || !userSelectionList || !cancelUserSelectionButton) {
      reject(new Error("UI not found for user selection"));
      return;
    }

    userSelectionList.innerHTML = '';
    sessions.forEach(session => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'connect-button';
      btn.style.marginTop = '8px';
      btn.textContent = `${session.userName} (${session.serverUrl})`;
      btn.onclick = () => {
        userSelectionDialog.close();
        resolve(session);
      };
      userSelectionList.appendChild(btn);
    });

    cancelUserSelectionButton.onclick = () => {
      userSelectionDialog.close();
      reject(new Error("User selection cancelled"));
    };

    userSelectionDialog.showModal();
  });
}

async function handleCompanionLogin(event: SubmitEvent) {
  event.preventDefault();
  if (!companionIpInput) return;

  clearError();
  setBusy(true, 'Fetching credentials from Companion App...');
  const remember = rememberInput?.checked ?? false;

  try {
    const validSessions = await loginViaCompanion(companionIpInput.value);
    
    let selectedSession: AuthSession;
    if (validSessions.length === 1) {
      selectedSession = validSessions[0];
    } else {
      setBusy(false);
      selectedSession = await promptUserSelection(validSessions);
      setBusy(true, 'Finishing login...');
    }
    
    saveSession(selectedSession, remember);
    await showBrowserApp();
  } catch (error) {
    showLogin();
    showError(error instanceof Error ? error.message : 'Unable to connect to Companion App.');
  } finally {
    setBusy(false);
  }
}

let qrScanner: Html5Qrcode | null = null;
async function startQrScanner() {
  if (!qrReaderElement || !companionIpInput) return;
  
  if (qrScanner) {
    await qrScanner.stop();
    qrScanner.clear();
    qrScanner = null;
    qrReaderElement.style.display = 'none';
    return;
  }

  qrReaderElement.style.display = 'block';
  qrScanner = new Html5Qrcode("qr-reader");

  try {
    await qrScanner.start(
      { facingMode: "environment" },
      { fps: 10, qrbox: { width: 250, height: 250 } },
      async (decodedText) => {
        let companionUrl = '';
        let setupToken = '';
        
        try {
          if (decodedText.startsWith("sfcp:")) {
            const data = decodedText.substring(5).split("|");
            if (data.length === 2) {
              companionUrl = data[0];
              setupToken = data[1];
            }
          } else {
            const payload = JSON.parse(decodedText);
            if (payload.companion_url && payload.setup_token) {
              companionUrl = payload.companion_url;
              setupToken = payload.setup_token;
            }
          }
        } catch (e) {
          // ignore parsing errors
        }

        if (!companionUrl) {
          // If not a valid setup token QR, maybe it's just an IP
          const ipPort = decodedText.replace(/^fcast:\/\//i, '');
          companionIpInput.value = ipPort;
          
          await qrScanner?.stop();
          qrScanner?.clear();
          qrScanner = null;
          qrReaderElement.style.display = 'none';
          
          companionLoginForm?.dispatchEvent(new SubmitEvent('submit', { cancelable: true }));
          return;
        }
        
        // Stop scanner
        await qrScanner?.stop();
        qrScanner?.clear();
        qrScanner = null;
        qrReaderElement.style.display = 'none';
        
        // Login with setup token
        clearError();
        setBusy(true, 'Fetching configuration from Companion App...');
        const remember = rememberInput?.checked ?? false;
        try {
          const validSessions = await loginViaCompanionSetupToken(companionUrl, setupToken);
          
          let selectedSession: AuthSession;
          if (validSessions.length === 1) {
            selectedSession = validSessions[0];
          } else {
            setBusy(false);
            selectedSession = await promptUserSelection(validSessions);
            setBusy(true, 'Finishing login...');
          }
          
          saveSession(selectedSession, remember);
          await showBrowserApp();
        } catch (error) {
          showLogin();
          showError(error instanceof Error ? error.message : 'Unable to connect to Companion App.');
        } finally {
          setBusy(false);
        }
      },
      () => {
        // Ignore parse errors, just keep scanning
      }
    );
  } catch (err) {
    showError("Could not start camera for QR scanning.");
    qrReaderElement.style.display = 'none';
  }
}

async function restoreSession() {
  if (!isAuthenticated()) {
    showLogin();
    return;
  }

  if (serverInput) serverInput.value = getServerUrl() ?? '';
  setBusy(true, 'Restoring your Jellyfin session...');

  try {
    await validateSession();
  } catch (error) {
    const isTemporaryFailure =
      error instanceof JellyfinAuthError && error.isTransient;
    if (!isTemporaryFailure) {
      showLogin();
      showError(
        error instanceof Error ? error.message : 'The saved Jellyfin session is invalid.',
      );
      setBusy(false);
      return;
    }
    // Preserve a valid saved session during temporary network failures. The
    // spatial retry state provides another way to reconnect.
  }

  await showBrowserApp();
  setBusy(false);
}

async function handleSwitchUser() {
  const companionUrl = getCompanionUrl();
  if (!companionUrl) return;

  setBusy(true, 'Fetching users from Companion App...');
  
  try {
    const validSessions = await loginViaCompanion(companionUrl);
    setBusy(false);
    
    if (validSessions.length > 0) {
      const selectedSession = await promptUserSelection(validSessions);
      setBusy(true, 'Finishing login...');
      saveSession(selectedSession, true);
      window.location.reload();
    }
  } catch (error) {
    showError(error instanceof Error ? error.message : 'Unable to switch user.');
  } finally {
    setBusy(false);
  }
}

loginForm?.addEventListener('submit', (event) => void handleLogin(event));
companionLoginForm?.addEventListener('submit', (event) => void handleCompanionLogin(event));
scanQrButton?.addEventListener('click', () => void startQrScanner());
signOutButton?.addEventListener('click', () => void logout());
browserSignOutButton?.addEventListener('click', () => void logout());
switchUserButton?.addEventListener('click', () => void handleSwitchUser());
browserSwitchUserButton?.addEventListener('click', () => void handleSwitchUser());
enterXrButton?.addEventListener('click', () => {
  enterXr();
});

void restoreSession();
