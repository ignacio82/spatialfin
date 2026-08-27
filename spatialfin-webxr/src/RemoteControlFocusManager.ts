/**
 * Universal Remote Control & D-Pad Focus Manager for SpatialFin Web App.
 * Enables 100% remote control operation (ArrowUp, ArrowDown, ArrowLeft, ArrowRight, Enter, Space, Escape, Backspace).
 */
/**
 * Frames a directional press will wait for a focusable element to appear before
 * giving up. Roughly a third of a second at 60fps — long enough to cover a route
 * transition, short enough that a truly empty screen does not spin.
 */
const MAX_MOVE_RETRIES = 20;

export class RemoteControlFocusManager {
  private static instance: RemoteControlFocusManager | null = null;
  private enabled = true;

  private constructor() {
    window.addEventListener('keydown', this.handleKeyDown.bind(this), true);
  }

  public static init(): RemoteControlFocusManager {
    if (!RemoteControlFocusManager.instance) {
      RemoteControlFocusManager.instance = new RemoteControlFocusManager();
    }
    return RemoteControlFocusManager.instance;
  }

  private isEditableTarget(target: EventTarget | null): boolean {
    if (!target || !(target instanceof HTMLElement)) return false;
    const tagName = target.tagName;
    return (
      target.isContentEditable ||
      tagName === 'INPUT' ||
      tagName === 'TEXTAREA' ||
      tagName === 'SELECT'
    );
  }

  private handleKeyDown(event: KeyboardEvent) {
    if (!this.enabled) return;

    const key = event.key;

    // Media keys from TV remotes
    if (key === 'MediaPlayPause' || key === 'MediaPlay' || key === 'MediaPause') {
      event.preventDefault();
      const playpauseBtn = document.querySelector<HTMLButtonElement>('#browser-player-playpause, #ma-mini-playpause');
      playpauseBtn?.click();
      return;
    }

    if (key === 'MediaTrackNext' || key === 'MediaNextTrack') {
      event.preventDefault();
      const nextBtn = document.querySelector<HTMLButtonElement>('#browser-player-play-next, #browser-player-next-chapter, #ma-mini-next');
      nextBtn?.click();
      return;
    }

    if (key === 'MediaTrackPrevious' || key === 'MediaPreviousTrack') {
      event.preventDefault();
      const prevBtn = document.querySelector<HTMLButtonElement>('#browser-player-prev-chapter, #ma-mini-prev');
      prevBtn?.click();
      return;
    }

    // Directional D-pad keys
    const dirMap: Record<string, 'up' | 'down' | 'left' | 'right'> = {
      ArrowUp: 'up',
      ArrowDown: 'down',
      ArrowLeft: 'left',
      ArrowRight: 'right',
      Up: 'up',
      Down: 'down',
      Left: 'left',
      Right: 'right',
      NavUp: 'up',
      NavDown: 'down',
      NavLeft: 'left',
      NavRight: 'right',
    };

    if (key in dirMap) {
      const activeEl = document.activeElement;
      const isInput = activeEl instanceof HTMLInputElement || activeEl instanceof HTMLTextAreaElement;

      // Range slider handling (e.g. scrubber, volume)
      if (activeEl instanceof HTMLInputElement && activeEl.type === 'range') {
        if (key === 'ArrowUp' || key === 'ArrowDown' || key === 'Up' || key === 'Down') {
          event.preventDefault();
          this.moveFocus(dirMap[key]);
          return;
        }
        return; // Allow native left/right slider adjustment
      }

      if (isInput && (key === 'ArrowLeft' || key === 'ArrowRight' || key === 'Left' || key === 'Right')) {
        return; // Allow native text cursor navigation
      }

      event.preventDefault();
      this.moveFocus(dirMap[key]);
      return;
    }

    // Enter / Space / Select for triggering focused element
    if (key === 'Enter' || key === 'Select') {
      const activeEl = document.activeElement;
      if (activeEl && activeEl instanceof HTMLElement && activeEl !== document.body) {
        if (
          !(activeEl instanceof HTMLButtonElement) &&
          !(activeEl instanceof HTMLInputElement) &&
          !(activeEl instanceof HTMLSelectElement) &&
          !(activeEl instanceof HTMLAnchorElement)
        ) {
          event.preventDefault();
          activeEl.click();
        }
      }
      return;
    }

    // Escape / Backspace / GoBack for back/cancel
    if (key === 'Escape' || key === 'GoBack' || (key === 'Backspace' && !this.isEditableTarget(event.target))) {
      event.preventDefault();
      this.handleBack();
      return;
    }
  }

  private getActiveContainer(): HTMLElement {
    const openDialog = document.querySelector<HTMLDialogElement>('dialog[open]');
    if (openDialog) return openDialog;

    const playerDialogBackdrop = Array.from(document.querySelectorAll<HTMLElement>('#browser-player-dialog-backdrop')).find(el => !el.hidden && el.offsetWidth > 0);
    if (playerDialogBackdrop) return playerDialogBackdrop;

    const overflowDropdown = Array.from(document.querySelectorAll<HTMLElement>('.hero-overflow-dropdown')).find(el => !el.hidden && el.offsetWidth > 0);
    if (overflowDropdown) return overflowDropdown;

    const playerOverlay = Array.from(document.querySelectorAll<HTMLElement>('#browser-player')).find(el => !el.hidden && el.offsetWidth > 0);
    if (playerOverlay) return playerOverlay;

    return document.body;
  }

  private isVisuallyHidden(el: HTMLElement): boolean {
    let curr: HTMLElement | null = el;
    while (curr && curr !== document.body) {
      if (curr.hidden || curr.style.display === 'none' || curr.style.visibility === 'hidden') {
        return true;
      }
      curr = curr.parentElement;
    }
    return false;
  }

  /**
   * Retries a directional press that arrived before the container had anything
   * focusable in it, e.g. the frame right after a route change while cards are
   * still being laid out. Without this the press is silently swallowed and the
   * D-pad looks dead until the user presses again.
   */
  private pendingMove: number | null = null;

  public moveFocus(direction: 'up' | 'down' | 'left' | 'right', attempt = 0) {
    if (this.pendingMove !== null) {
      cancelAnimationFrame(this.pendingMove);
      this.pendingMove = null;
    }
    const container = this.getActiveContainer();

    const selector = [
      'button:not([disabled])',
      'input:not([disabled]):not([type="hidden"])',
      'select:not([disabled])',
      'textarea:not([disabled])',
      'a[href]',
      '[tabindex="0"]:not([disabled])',
      '.media-card:not([disabled])',
      '.ma-media-card:not([disabled])',
      '.player-dialog-item',
      '.overflow-item',
      '.ma-track-row',
    ].join(',');

    const elements = Array.from(container.querySelectorAll<HTMLElement>(selector)).filter((el) => {
      if (el.getAttribute('tabindex') === '-1') return false;
      if (this.isVisuallyHidden(el)) return false;
      const rect = el.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0;
    });

    if (elements.length === 0) {
      // Nothing measurable yet — wait a frame and try again rather than
      // dropping the input. Bounded so a genuinely empty screen settles.
      if (attempt < MAX_MOVE_RETRIES) {
        this.pendingMove = requestAnimationFrame(() => {
          this.pendingMove = null;
          this.moveFocus(direction, attempt + 1);
        });
      }
      return;
    }

    let current = document.activeElement as HTMLElement | null;
    if (!current || !container.contains(current) || current === document.body || !elements.includes(current)) {
      for (const el of elements) {
        el.focus();
        if (document.activeElement !== el) {
          el.setAttribute('tabindex', '0');
          el.focus();
        }
        if (document.activeElement === el) {
          el.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
          return;
        }
      }
      return;
    }

    const currentRect = current.getBoundingClientRect();
    const currentCenter = {
      x: currentRect.left + currentRect.width / 2,
      y: currentRect.top + currentRect.height / 2,
    };

    let bestCandidate: HTMLElement | null = null;
    let bestScore = Infinity;

    for (const el of elements) {
      if (el === current) continue;
      const rect = el.getBoundingClientRect();
      const center = {
        x: rect.left + rect.width / 2,
        y: rect.top + rect.height / 2,
      };

      const dx = center.x - currentCenter.x;
      const dy = center.y - currentCenter.y;

      let isValidDirection = false;
      let primaryDistance = 0;
      let secondaryDistance = 0;

      switch (direction) {
        case 'up':
          isValidDirection = dy < -2;
          primaryDistance = Math.abs(dy);
          secondaryDistance = Math.abs(dx);
          break;
        case 'down':
          isValidDirection = dy > 2;
          primaryDistance = Math.abs(dy);
          secondaryDistance = Math.abs(dx);
          break;
        case 'left':
          isValidDirection = dx < -2;
          primaryDistance = Math.abs(dx);
          secondaryDistance = Math.abs(dy);
          break;
        case 'right':
          isValidDirection = dx > 2;
          primaryDistance = Math.abs(dx);
          secondaryDistance = Math.abs(dy);
          break;
      }

      if (!isValidDirection) continue;

      const score = primaryDistance + secondaryDistance * 2.5;
      if (score < bestScore) {
        bestScore = score;
        bestCandidate = el;
      }
    }

    if (bestCandidate) {
      bestCandidate.focus();
      if (document.activeElement !== bestCandidate) {
        bestCandidate.setAttribute('tabindex', '0');
        bestCandidate.focus();
      }
      bestCandidate.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
    } else {
      const currentIndex = elements.indexOf(current);
      if (currentIndex !== -1) {
        let nextIndex = currentIndex;
        if (direction === 'down' || direction === 'right') {
          nextIndex = (currentIndex + 1) % elements.length;
        } else if (direction === 'up' || direction === 'left') {
          nextIndex = (currentIndex - 1 + elements.length) % elements.length;
        }
        const fallback = elements[nextIndex];
        if (fallback) {
          fallback.focus();
          if (document.activeElement !== fallback) {
            fallback.setAttribute('tabindex', '0');
            fallback.focus();
          }
          fallback.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
        }
      }
    }
  }

  private handleBack() {
    const openDialog = document.querySelector<HTMLDialogElement>('dialog[open]');
    if (openDialog) {
      openDialog.close();
      return;
    }

    const playerDialogCloseBtn = document.querySelector<HTMLButtonElement>('#player-dialog-close');
    const playerDialogBackdrop = document.querySelector<HTMLElement>('#browser-player-dialog-backdrop:not([hidden])');
    if (playerDialogBackdrop && playerDialogCloseBtn) {
      playerDialogCloseBtn.click();
      return;
    }

    const overflowDropdown = document.querySelector<HTMLElement>('.hero-overflow-dropdown:not([hidden])');
    if (overflowDropdown) {
      overflowDropdown.hidden = true;
      const toggleBtn = document.querySelector<HTMLButtonElement>('.hero-overflow-toggle-btn');
      toggleBtn?.focus();
      return;
    }

    const playerOverlay = document.querySelector<HTMLElement>('#browser-player:not([hidden])');
    const playerBackBtn = document.querySelector<HTMLButtonElement>('#browser-player-back');
    if (playerOverlay && playerBackBtn) {
      playerBackBtn.click();
      return;
    }

    const backBtn = document.querySelector<HTMLButtonElement>('.browser-content .back-button');
    if (backBtn && backBtn.offsetParent !== null) {
      backBtn.click();
      return;
    }
  }
}
