import Hls from 'hls.js';
import {
  fetchEpisodes,
  fetchItemImage,
  fetchItems,
  fetchLatestMedia,
  fetchNextUp,
  fetchPlaybackInfo,
  fetchResumeItems,
  fetchSuggestions,
  fetchViews,
  fetchItem,
  extractMediaPills,
  type JellyfinImageType,
  type JellyfinItem,
  type JellyfinView,
} from './api';

interface BrowserShelf {
  title: string;
  items: JellyfinItem[];
}

function metadata(item: JellyfinItem): string {
  return [
    item.ProductionYear?.toString(),
    item.Type === 'Episode' && item.SeriesName ? item.SeriesName : item.Type,
    item.CommunityRating ? `★ ${item.CommunityRating.toFixed(1)}` : null,
  ].filter(Boolean).join(' · ');
}

function progress(item: JellyfinItem): number | null {
  const position = item.UserData?.PlaybackPositionTicks ?? 0;
  const runtime = item.RunTimeTicks ?? 0;
  return position > 0 && runtime > 0 ? Math.min(100, (position / runtime) * 100) : null;
}

/** A browser-first Jellyfin client. XR is deliberately a separate opt-in mode. */
export class BrowserApp {
  private readonly root: HTMLElement;
  private readonly content: HTMLElement;
  private readonly player: HTMLElement;
  private readonly video: HTMLVideoElement;
  private readonly playerTitle: HTMLElement;
  private readonly playerStatus: HTMLElement;
  private readonly objectUrls = new Set<string>();
  private hls: Hls | null = null;
  private views: JellyfinView[] = [];
  private visible = false;
  private readonly automationMode = new URLSearchParams(window.location.search).has('xrAutomation');

  constructor() {
    this.root = this.requireElement('#browser-app');
    this.content = this.requireElement('#browser-content');
    this.player = this.requireElement('#browser-player');
    this.video = this.requireElement<HTMLVideoElement>('#browser-video');
    this.playerTitle = this.requireElement('#browser-player-title');
    this.playerStatus = this.requireElement('#browser-player-status');
    document.querySelectorAll<HTMLButtonElement>('[data-browser-route]').forEach((button) => {
      button.addEventListener('click', () => void this.showRoute(button.dataset.browserRoute ?? 'home'));
    });
    document.querySelector('#browser-player-close')?.addEventListener('click', () => this.closePlayer());
  }

  async show() {
    this.visible = true;
    this.root.hidden = false;
    await this.showRoute(location.hash.replace('#', '') || 'home');
  }

  hide() {
    this.visible = false;
    this.root.hidden = true;
    this.closePlayer();
    this.revokeImages();
  }

  dispose() {
    this.closePlayer();
    this.revokeImages();
  }

  private async showRoute(route: string) {
    this.setActiveRoute(route);
    this.revokeImages();
    if (route === 'libraries') {
      await this.showLibraries();
      return;
    }
    await this.showHome();
  }

  private async showHome() {
    this.loading('Loading your library…');
    try {
      const [suggestions, resume, nextUp, views] = await Promise.all([
        fetchSuggestions(), fetchResumeItems(), fetchNextUp(), fetchViews(),
      ]);
      this.views = views;
      const latest = await Promise.all(views.map(async (view) => ({
        title: `Latest in ${view.Name}`,
        items: await fetchLatestMedia(view.Id).catch(() => fetchItems(view.Id)),
      })));
      this.renderHome(suggestions, [
        {title: 'Continue watching', items: resume},
        {title: 'Next up', items: nextUp},
        ...latest,
      ]);
    } catch (error) {
      this.error(error);
    }
  }

  private async showLibraries() {
    this.loading('Loading your libraries…');
    try {
      this.views = await fetchViews();
      this.content.innerHTML = `
        <section class="browser-page-heading"><p class="eyebrow">Your Jellyfin server</p><h1>Libraries</h1></section>
        <div class="library-grid"></div>`;
      const grid = this.content.querySelector<HTMLElement>('.library-grid')!;
      this.views.forEach((view) => {
        const button = document.createElement('button');
        button.className = 'library-tile';
        button.innerHTML = `<span class="library-icon">▣</span><strong>${this.escape(view.Name)}</strong><span>Browse library</span>`;
        button.addEventListener('click', () => void this.showLibrary(view));
        grid.append(button);
      });
    } catch (error) {
      this.error(error);
    }
  }

  private async showLibrary(view: JellyfinView) {
    this.loading(`Loading ${view.Name}…`);
    try {
      const items = await fetchItems(view.Id);
      this.renderItemsPage(view.Name, items, () => void this.showLibraries());
    } catch (error) {
      this.error(error);
    }
  }

  private async showItem(item: JellyfinItem) {
    if (item.Type === 'Series') {
      this.loading(`Loading ${item.Name}…`);
      try {
        this.renderItemsPage(item.Name, await fetchEpisodes(item.Id), () => void this.showRoute('home'), item);
      } catch (error) {
        this.error(error);
      }
      return;
    }
    this.loading(`Loading ${item.Name}…`);
    try {
      const fullItem = await fetchItem(item.Id);
      this.renderDetails(fullItem);
    } catch (error) {
      this.error(error);
    }
  }

  private renderHome(featured: JellyfinItem[], shelves: BrowserShelf[]) {
    const lead = featured[0];
    let myMediaHtml = '';
    if (this.views.length > 0) {
      myMediaHtml = `
        <section class="browser-shelf my-media">
          <h2>My Media</h2>
          <div class="library-grid" style="margin-bottom: 2rem;">
            ${this.views.map(view => `<button class="library-tile" data-view-id="${view.Id}">
              <div class="library-art" aria-hidden="true"></div>
              <div class="library-content">
                <span class="library-icon">▣</span><strong>${this.escape(view.Name)}</strong><span>Browse library</span>
              </div>
            </button>`).join('')}
          </div>
        </section>
      `;
    }

    this.content.innerHTML = `
      <section class="browser-hero">
        <div class="browser-hero-art" aria-hidden="true"></div>
        <div class="browser-hero-copy">
          <p class="eyebrow">Recommended for you</p>
          <h1>${lead ? this.escape(lead.Name) : 'Your media, your way'}</h1>
          <p>${lead ? this.escape(lead.Overview ?? 'Ready to watch.') : 'Browse your Jellyfin library in the browser or place it around you in XR.'}</p>
          ${lead ? '<button class="primary-action" type="button">View details</button>' : ''}
        </div>
      </section>
      ${myMediaHtml}
      <div class="browser-shelves"></div>`;
    if (lead) {
      this.content.querySelector<HTMLButtonElement>('.primary-action')?.addEventListener('click', () => void this.showItem(lead));
      void this.setImage(this.content.querySelector('.browser-hero-art')!, lead, 'Backdrop');
    }
    if (this.views.length > 0) {
      this.content.querySelectorAll<HTMLButtonElement>('.my-media .library-tile').forEach((btn, index) => {
        btn.addEventListener('click', () => void this.showLibrary(this.views[index]));
        const art = btn.querySelector('.library-art');
        if (art) void this.setImage(art as HTMLElement, this.views[index], 'Primary');
      });
    }
    const shelfRoot = this.content.querySelector<HTMLElement>('.browser-shelves')!;
    shelves.filter((shelf) => shelf.items.length).forEach((shelf) => shelfRoot.append(this.createShelf(shelf)));
  }

  private renderItemsPage(title: string, items: JellyfinItem[], back: () => void, parent?: JellyfinItem) {
    this.revokeImages();
    this.content.innerHTML = `<section class="browser-page-heading"><button class="back-button" type="button">← Back</button><p class="eyebrow">${parent ? 'Episodes' : 'Library'}</p><h1>${this.escape(title)}</h1><p>${items.length} items</p></section><div class="media-grid"></div>`;
    this.content.querySelector<HTMLButtonElement>('.back-button')?.addEventListener('click', back);
    const grid = this.content.querySelector<HTMLElement>('.media-grid')!;
    items.forEach((item) => grid.append(this.createCard(item)));
  }

  private renderDetails(item: JellyfinItem) {
    this.revokeImages();
    const pills = extractMediaPills(item);
    const pillsHtml = pills.length > 0 ? `<div class="detail-pills">${pills.map(p => `<span class="pill">${this.escape(p)}</span>`).join('')}</div>` : '';
    
    let castHtml = '';
    if (item.People && item.People.length > 0) {
      castHtml = `
        <div class="detail-cast" style="margin-top: 2rem;">
          <h3 style="font-size: 1.2rem; margin-bottom: 1rem; color: #e1e3e8;">Cast & Crew</h3>
          <div class="cast-row" style="display: flex; gap: 16px; overflow-x: auto; padding-bottom: 8px;">
            ${item.People.slice(0, 12).map(person => `
              <div class="cast-card" style="flex: 0 0 auto; background: #1a222c; border-radius: 8px; padding: 12px; min-width: 140px;">
                <strong style="display: block; font-size: 1rem; color: #fff; margin-bottom: 4px;">${this.escape(person.Name ?? 'Unknown')}</strong>
                <span style="font-size: 0.85rem; color: #a4adc1;">${this.escape(person.Role || person.Type || '')}</span>
              </div>
            `).join('')}
          </div>
        </div>
      `;
    }

    this.content.innerHTML = `<section class="detail-page"><button class="back-button" type="button">← Back</button><div class="detail-backdrop"></div><div class="detail-copy"><p class="eyebrow">${this.escape(item.Type ?? 'Video')}</p><h1>${this.escape(item.Name)}</h1><p class="detail-meta">${this.escape(metadata(item))}</p>${pillsHtml}<p style="margin-top: 1rem;">${this.escape(item.Overview ?? 'No synopsis is available.')}</p><button class="primary-action" type="button" style="margin-top: 1.5rem;">▶ Play in browser</button>${castHtml}</div></section>`;
    this.content.querySelector<HTMLButtonElement>('.back-button')?.addEventListener('click', () => void this.showRoute('home'));
    this.content.querySelector<HTMLButtonElement>('.primary-action')?.addEventListener('click', () => void this.play(item));
    void this.setImage(this.content.querySelector('.detail-backdrop')!, item, 'Backdrop');
  }

  private createShelf(shelf: BrowserShelf): HTMLElement {
    const section = document.createElement('section');
    section.className = 'browser-shelf';
    section.innerHTML = `<h2>${this.escape(shelf.title)}</h2><div class="media-row"></div>`;
    const row = section.querySelector<HTMLElement>('.media-row')!;
    shelf.items.slice(0, 12).forEach((item) => row.append(this.createCard(item)));
    return section;
  }

  private createCard(item: JellyfinItem): HTMLButtonElement {
    const card = document.createElement('button');
    card.className = 'media-card';
    card.innerHTML = `<span class="media-art" aria-hidden="true"></span><strong>${this.escape(item.Name)}</strong><span class="media-meta">${this.escape(metadata(item))}</span>`;
    const watched = progress(item);
    if (watched !== null) card.querySelector('.media-art')!.insertAdjacentHTML('beforeend', `<i class="progress"><b style="width:${watched}%"></b></i>`);
    card.addEventListener('click', () => void this.showItem(item));
    void this.setImage(card.querySelector('.media-art')!, item, 'Primary');
    return card;
  }

  private async setImage(element: HTMLElement, item: { Id: string, ImageTags?: Record<string, string>, BackdropImageTags?: string[] }, type: JellyfinImageType = 'Primary') {
    // Browser automation exercises interaction and navigation separately from
    // XR texture upload; skipping remote artwork there keeps the headless
    // renderer within its GPU-memory budget.
    if (this.automationMode) return;
    try {
      const blob = await fetchItemImage(item, type);
      if (!blob || !this.visible || !element.isConnected) return;
      const url = URL.createObjectURL(blob);
      this.objectUrls.add(url);
      (element as HTMLElement).style.backgroundImage = `url("${url}")`;
    } catch {
      // The UI remains useful even when a server has no artwork or blocks it.
    }
  }

  private async play(item: JellyfinItem) {
    this.player.hidden = false;
    this.playerTitle.textContent = item.Name;
    this.playerStatus.textContent = 'Preparing stream…';
    try {
      const playback = await fetchPlaybackInfo(item.Id);
      this.hls?.destroy();
      this.hls = null;
      if (Hls.isSupported()) {
        this.hls = new Hls({xhrSetup: (request) => {
          for (const [name, value] of Object.entries(playback.requiredHeaders)) request.setRequestHeader(name, value);
        }});
        this.hls.loadSource(playback.streamUrl);
        this.hls.attachMedia(this.video);
      } else {
        this.video.src = playback.streamUrl;
      }
      this.playerStatus.textContent = '';
      await this.video.play().catch(() => undefined);
    } catch (error) {
      this.playerStatus.textContent = error instanceof Error ? error.message : 'Unable to start playback.';
    }
  }

  private closePlayer() {
    this.hls?.destroy();
    this.hls = null;
    this.video.pause();
    this.video.removeAttribute('src');
    this.video.load();
    this.player.hidden = true;
    this.playerStatus.textContent = '';
  }

  private loading(message: string) {
    this.content.innerHTML = `<div class="browser-state"><span class="loading-mark"></span><p>${this.escape(message)}</p></div>`;
  }

  private error(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unable to load your Jellyfin library.';
    this.content.innerHTML = `<div class="browser-state"><h1>Couldn’t load this page</h1><p>${this.escape(message)}</p><button class="primary-action" type="button">Try again</button></div>`;
    this.content.querySelector('button')?.addEventListener('click', () => void this.showRoute('home'));
  }

  private setActiveRoute(route: string) {
    document.querySelectorAll<HTMLButtonElement>('[data-browser-route]').forEach((button) => {
      button.classList.toggle('is-active', button.dataset.browserRoute === route);
    });
  }

  private revokeImages() {
    for (const url of this.objectUrls) URL.revokeObjectURL(url);
    this.objectUrls.clear();
  }

  private escape(value: string): string {
    const element = document.createElement('span');
    element.textContent = value;
    return element.innerHTML;
  }

  private requireElement<T extends HTMLElement = HTMLElement>(selector: string): T {
    const element = document.querySelector<T>(selector);
    if (!element) throw new Error(`Missing required browser UI element: ${selector}`);
    return element;
  }
}
