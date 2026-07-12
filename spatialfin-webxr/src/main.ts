import 'xrblocks/addons/simulator/SimulatorAddons.js';
import * as xb from 'xrblocks';
import {
  getGeminiKey,
  getServerUrl,
  isAuthenticated,
  JellyfinAuthError,
  login,
  logout,
  validateSession,
} from './auth';
import {HomeSpace} from './HomeSpace';
import {BrowserApp} from './BrowserApp';
import './style.css';

const loginOverlay = document.querySelector<HTMLElement>('#login-overlay');
const loginForm = document.querySelector<HTMLFormElement>('#login-form');
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
const browserApp = new BrowserApp();

let xrStartPromise: Promise<void> | null = null;
const automationMode = new URLSearchParams(window.location.search).has('xrAutomation');

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

loginForm?.addEventListener('submit', (event) => void handleLogin(event));
signOutButton?.addEventListener('click', () => void logout());
browserSignOutButton?.addEventListener('click', () => void logout());
enterXrButton?.addEventListener('click', () => {
  enterXr();
});

void restoreSession();
