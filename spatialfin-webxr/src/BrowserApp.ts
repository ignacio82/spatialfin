import Hls, {FetchLoader} from 'hls.js';
import {
  fetchItemImage,
  fetchItems,
  fetchLatestMedia,
  fetchNextUp,
  fetchPlaybackInfo,
  fetchResumeItems,
  fetchSuggestions,
  fetchViews,
  fetchItem,
  fetchSeasons,
  fetchSeasonEpisodes,
  searchItems,
  fetchItemsByPerson,
  searchSeerr,
  fetchPreferences,
  movieVersionGroupKey,
  extractMediaPills,
  resolveJellyfinRequestUrl,
  toggleFavorite,
  getDownloadUrl,
  getVersionChipLabel,
  type JellyfinImageType,
  type JellyfinItem,
  type SeerrResult,
  type JellyfinPlaybackInfo,
  type JellyfinView,
} from './api';
import {mediaUrlWithAccessToken} from './auth';
import {createJellyfinRequest, streamingFetchSupported} from './network';
import {
  AnimeSubtitleRenderer,
  chooseInitialAudioStreamIndex,
  chooseInitialSubtitleTrack,
  rememberAudioSelection,
  rememberSubtitleSelection,
  subtitleLanguageMatches,
} from './AnimeSubtitleRenderer';

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
  private readonly audioSelect: HTMLSelectElement;
  private readonly subtitleSelect: HTMLSelectElement;
  private readonly searchInput: HTMLInputElement;
  private readonly objectUrls = new Set<string>();
  
  public onPlayRequest: ((item: JellyfinItem) => boolean) | null = null;
  
  private hls: Hls | null = null;
  private subtitleRenderer: AnimeSubtitleRenderer | null = null;
  private subtitleAbortController: AbortController | null = null;
  private playback: JellyfinPlaybackInfo | null = null;
  private playingItem: JellyfinItem | null = null;
  private subtitleGeneration = 0;
  private playbackGeneration = 0;
  private playbackAbortController: AbortController | null = null;
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
    this.audioSelect = this.requireElement<HTMLSelectElement>('#browser-audio-select');
    this.subtitleSelect = this.requireElement<HTMLSelectElement>('#browser-subtitle-select');
    this.searchInput = this.requireElement<HTMLInputElement>('#browser-search-input');
    document.querySelectorAll<HTMLButtonElement>('[data-browser-route]').forEach((button) => {
      button.addEventListener('click', () => void this.showRoute(button.dataset.browserRoute ?? 'home'));
    });
    document.querySelector('#browser-player-close')?.addEventListener('click', () => this.closePlayer());
    this.subtitleSelect.addEventListener('change', () => {
      void this.selectSubtitle(Number(this.subtitleSelect.value), true);
    });
    this.audioSelect.addEventListener('change', () => {
      void this.selectAudio(Number(this.audioSelect.value), true);
    });

    let searchTimeout: number;
    this.searchInput?.addEventListener('input', () => {
      window.clearTimeout(searchTimeout);
      searchTimeout = window.setTimeout(() => {
        const query = this.searchInput.value.trim();
        if (query.length > 1) {
          void this.performSearch(query);
        } else if (query.length === 0) {
          void this.showRoute('home');
        }
      }, 500);
    });
  }

  async show() {
    this.visible = true;
    this.root.hidden = false;
    await fetchPreferences();
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

  private async performSearch(query: string) {
    this.loading(`Searching for "${query}"…`);
    try {
      const [jellyfinResults, seerrResults] = await Promise.all([
        searchItems(query),
        searchSeerr(query)
      ]);
      this.renderSearchPage(query, jellyfinResults, seerrResults);
    } catch (error) {
      this.error(error);
    }
  }

  private async performPersonSearch(personId: string, personName: string) {
    this.loading(`Searching for "${personName}"…`);
    try {
      const [jellyfinResults, seerrResults] = await Promise.all([
        fetchItemsByPerson(personId),
        searchSeerr(personName)
      ]);
      this.renderSearchPage(personName, jellyfinResults, seerrResults);
    } catch (error) {
      this.error(error);
    }
  }

  private async showItem(item: JellyfinItem) {
    if (item.Type === 'Season') {
      this.loading(`Loading ${item.Name}…`);
      try {
        const episodes = await fetchSeasonEpisodes(item.SeriesId || '', item.Id);
        this.renderItemsPage(item.Name, episodes, () => {
          if (item.SeriesId) void this.showItem({Id: item.SeriesId, Type: 'Series'} as JellyfinItem);
          else void this.showRoute('home');
        }, { Id: item.SeriesId, Type: 'Series'} as JellyfinItem);
      } catch (error) {
        this.error(error);
      }
      return;
    }

    this.loading(`Loading ${item.Name}…`);
    try {
      const fullItem = await fetchItem(item.Id);
      let versions: JellyfinItem[] = [];
      let seasons: JellyfinItem[] = [];
      let nextUp: JellyfinItem | undefined;

      if (fullItem.Type === 'Movie') {
        const key = movieVersionGroupKey(fullItem);
        if (key) {
           const searchResults = await searchItems(fullItem.Name);
           versions = searchResults.filter(r => movieVersionGroupKey(r) === key).sort((a,b) => a.Id.localeCompare(b.Id));
        }
      } else if (fullItem.Type === 'Series') {
        seasons = await fetchSeasons(fullItem.Id);
        const nextUpItems = await fetchNextUp(1, fullItem.Id);
        if (nextUpItems.length > 0) nextUp = nextUpItems[0];
      }
      
      this.renderDetails(fullItem, versions, seasons, nextUp);
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
          <div class="browser-hero-actions">
            ${lead ? '<button class="primary-action" type="button">View details</button>' : ''}
            ${lead ? `<button class="secondary-action hero-favorite-action" type="button" data-id="${lead.Id}" data-favorite="${lead.UserData?.IsFavorite ? 'true' : 'false'}">${lead.UserData?.IsFavorite ? '♥' : '♡'} Favorite</button>` : ''}
            ${lead ? `<a class="secondary-action hero-download-action" href="${getDownloadUrl(lead.Id)}" download>Download</a>` : ''}
          </div>
        </div>
      </section>
      ${myMediaHtml}
      <div class="browser-shelves"></div>`;
    if (lead) {
      this.content.querySelector<HTMLButtonElement>('.primary-action')?.addEventListener('click', () => void this.showItem(lead));
      this.content.querySelector<HTMLButtonElement>('.hero-favorite-action')?.addEventListener('click', async (e) => {
        const btn = e.currentTarget as HTMLButtonElement;
        const isFav = btn.dataset.favorite === 'true';
        btn.disabled = true;
        try {
          const updated = await toggleFavorite(lead.Id, !isFav);
          lead.UserData = updated.UserData;
          btn.dataset.favorite = updated.UserData?.IsFavorite ? 'true' : 'false';
          btn.textContent = `${updated.UserData?.IsFavorite ? '♥' : '♡'} Favorite`;
        } catch (err) {
          console.error('Failed to toggle favorite:', err);
        } finally {
          btn.disabled = false;
        }
      });
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

  private renderSearchPage(query: string, jellyfinItems: JellyfinItem[], seerrItems: SeerrResult[]) {
    this.revokeImages();
    this.content.innerHTML = `<div class="browser-page-heading"><h1>Search: ${this.escape(query)}</h1><p>Library</p></div><div class="media-grid" id="jellyfin-results"></div>`;
    const jellyfinGrid = this.content.querySelector<HTMLElement>('#jellyfin-results')!;
    
    if (jellyfinItems.length > 0) {
      jellyfinItems.forEach((item) => jellyfinGrid.append(this.createCard(item)));
    } else {
      jellyfinGrid.innerHTML = `<p style="color: #9eacb9;">No results in your library.</p>`;
    }

    if (seerrItems.length > 0) {
      this.content.insertAdjacentHTML('beforeend', `<div class="browser-page-heading"><h1 style="margin-top: 24px;">Discover</h1><p>Powered by Jellyseerr</p></div><div class="media-grid" id="seerr-results"></div>`);
      const seerrGrid = this.content.querySelector<HTMLElement>('#seerr-results')!;
      seerrItems.forEach((item) => seerrGrid.append(this.createSeerrCard(item)));
    }
  }

  private renderDetails(item: JellyfinItem, versions: JellyfinItem[] = [], seasons: JellyfinItem[] = [], nextUp?: JellyfinItem) {
    this.revokeImages();
    const pills = extractMediaPills(item);
    const pillsHtml = pills.length > 0 ? `<div class="detail-pills">${pills.map(p => `<span class="pill">${this.escape(p)}</span>`).join('')}</div>` : '';
    
    let castHtml = '';
    if (item.People && item.People.length > 0) {
      castHtml = `
        <div class="detail-cast" style="margin-top: 2rem;">
          <h3 style="font-size: 1.2rem; margin-bottom: 1rem; color: #e1e3e8;">Cast & Crew</h3>
          <div class="media-row cast-row"></div>
        </div>
      `;
    }

    let versionSelectHtml = '';
    if (versions.length > 1) {
       versionSelectHtml = `
         <div style="margin-top: 1rem;">
           <label for="version-select" style="font-size: 0.85rem; color: #a4adc1; display: block; margin-bottom: 4px;">Select Version</label>
           <select id="version-select" style="background: rgba(255,255,255,0.1); border: 1px solid rgba(255,255,255,0.2); color: white; padding: 8px 12px; border-radius: 6px; font-size: 1rem; cursor: pointer;">
             ${versions.map((v) => `<option value="${v.Id}" style="background: #111318; color: white;" ${v.Id === item.Id ? 'selected' : ''}>${this.escape(getVersionChipLabel(v))}</option>`).join('')}
           </select>
         </div>
       `;
    }

    let nextUpHtml = '';
    if (nextUp) {
       nextUpHtml = `
         <div class="detail-next-up" style="margin-top: 2rem;">
           <h3 style="font-size: 1.2rem; margin-bottom: 1rem; color: #e1e3e8;">Next Up</h3>
           <div class="next-up-container"></div>
         </div>
       `;
    }

    let seasonsHtml = '';
    if (seasons.length > 0) {
       seasonsHtml = `
         <div class="detail-seasons" style="margin-top: 2rem;">
           <h3 style="font-size: 1.2rem; margin-bottom: 1rem; color: #e1e3e8;">Seasons</h3>
           <div class="media-row seasons-row"></div>
         </div>
       `;
    }

    this.content.innerHTML = `<section class="detail-page"><button class="back-button" type="button">← Back</button><div class="detail-backdrop"></div><div class="detail-copy"><p class="eyebrow">${this.escape(item.Type ?? 'Video')}</p><h1>${this.escape(item.Name)}</h1><p class="detail-meta">${this.escape(metadata(item))}</p>${pillsHtml}<p style="margin-top: 1rem;">${this.escape(item.Overview ?? 'No synopsis is available.')}</p>${versionSelectHtml}<div class="browser-hero-actions"><button class="primary-action" type="button">▶ Play in browser</button><button class="secondary-action detail-favorite-action" type="button" data-id="${item.Id}" data-favorite="${item.UserData?.IsFavorite ? 'true' : 'false'}">${item.UserData?.IsFavorite ? '♥' : '♡'} Favorite</button><a class="secondary-action detail-download-action" href="${getDownloadUrl(item.Id)}" download>Download</a></div></div><div class="detail-extra-rows" style="position: relative; z-index: 1; padding: 0 clamp(32px, 6vw, 88px) 40px;">${nextUpHtml}${seasonsHtml}${castHtml}</div></section>`;
    
    let playItem = item;
    
    this.content.querySelector<HTMLButtonElement>('.back-button')?.addEventListener('click', () => void this.showRoute('home'));
    this.content.querySelector<HTMLButtonElement>('.primary-action')?.addEventListener('click', () => void this.play(playItem));
    
    this.content.querySelector<HTMLButtonElement>('.detail-favorite-action')?.addEventListener('click', async (e) => {
      const btn = e.currentTarget as HTMLButtonElement;
      const isFav = btn.dataset.favorite === 'true';
      btn.disabled = true;
      try {
        const updated = await toggleFavorite(item.Id, !isFav);
        item.UserData = updated.UserData;
        btn.dataset.favorite = updated.UserData?.IsFavorite ? 'true' : 'false';
        btn.textContent = `${updated.UserData?.IsFavorite ? '♥' : '♡'} Favorite`;
      } catch (err) {
        console.error('Failed to toggle favorite:', err);
      } finally {
        btn.disabled = false;
      }
    });
    
    const versionSelect = this.content.querySelector<HTMLSelectElement>('#version-select');
    if (versionSelect) {
      versionSelect.addEventListener('change', () => {
        const selectedId = versionSelect.value;
        const selectedItem = versions.find(v => v.Id === selectedId);
        if (selectedItem) playItem = selectedItem;
      });
    }

    if (nextUp) {
      const container = this.content.querySelector<HTMLElement>('.next-up-container')!;
      container.append(this.createCard(nextUp));
    }

    if (seasons.length > 0) {
      const row = this.content.querySelector<HTMLElement>('.seasons-row')!;
      seasons.forEach(s => row.append(this.createCard(s)));
    }

    if (item.People && item.People.length > 0) {
      const row = this.content.querySelector<HTMLElement>('.cast-row')!;
      item.People.slice(0, 12).forEach(p => row.append(this.createCastCard(p)));
    }

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

  private createCastCard(person: any): HTMLButtonElement {
    const card = document.createElement('button');
    card.className = 'media-card cast-card';
    card.innerHTML = `<span class="media-art" aria-hidden="true" style="border-radius: 50%; aspect-ratio: 1/1; background-color: #1a222c; border: 1px solid rgba(255,255,255,0.1); flex-shrink: 0;"></span><strong style="margin-top: 8px;">${this.escape(person.Name ?? 'Unknown')}</strong><span class="media-meta">${this.escape(person.Role || person.Type || '')}</span>`;
    
    if (person.PrimaryImageTag && person.Id) {
      const art = card.querySelector<HTMLElement>('.media-art')!;
      art.style.backgroundImage = `url(${resolveJellyfinRequestUrl(`/Items/${person.Id}/Images/Primary?tag=${person.PrimaryImageTag}&maxWidth=240`)})`;
    }
    
    card.addEventListener('click', () => {
      if (person.Id) {
        void this.performPersonSearch(person.Id, person.Name ?? 'Unknown');
      } else {
        void this.performSearch(person.Name ?? 'Unknown');
      }
    });
    return card;
  }

  private createSeerrCard(item: SeerrResult): HTMLButtonElement {
    const card = document.createElement('button');
    card.className = 'media-card';
    const title = item.title || item.name || 'Unknown';
    const year = (item.releaseDate || item.firstAirDate || '').substring(0, 4);
    const meta = [year, item.mediaType === 'tv' ? 'Series' : 'Movie'].filter(Boolean).join(' · ');
    
    card.innerHTML = `<span class="media-art" aria-hidden="true"></span><strong>${this.escape(title)}</strong><span class="media-meta">${this.escape(meta)}</span>`;
    
    if (item.posterPath) {
      const art = card.querySelector<HTMLElement>('.media-art')!;
      art.style.backgroundImage = `url(https://image.tmdb.org/t/p/w500${item.posterPath})`;
    }
    
    // In a full implementation we would let the user request the item via Seerr API here.
    card.addEventListener('click', () => {
      alert('This item is available via Jellyseerr. In the Android app this would open the request screen.');
    });
    
    return card;
  }

  private createCard(item: JellyfinItem): HTMLButtonElement {
    const card = document.createElement('button');
    card.className = 'media-card';
    card.innerHTML = `<span class="media-art" aria-hidden="true"></span><strong>${this.escape(item.Name)}</strong><span class="media-meta">${this.escape(metadata(item))}</span>`;
    const watched = progress(item);
    if (watched !== null) card.querySelector('.media-art')!.insertAdjacentHTML('beforeend', `<i class="progress"><b style="width:${watched}%"></b></i>`);
    if (item.UserData?.Played) {
      card.querySelector('.media-art')!.insertAdjacentHTML('beforeend', `<i class="played-badge">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>
      </i>`);
    }
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
    if (this.onPlayRequest && this.onPlayRequest(item)) {
      return;
    }
    this.resetPlayback();
    const generation = ++this.playbackGeneration;
    const playbackController = new AbortController();
    this.playbackAbortController = playbackController;
    this.player.hidden = false;
    this.playerTitle.textContent = item.Name;
    this.playerStatus.textContent = 'Preparing stream…';
    try {
      const discoveredPlayback = await fetchPlaybackInfo(item.Id, playbackController.signal);
      let playback = discoveredPlayback;
      if (generation !== this.playbackGeneration || playbackController.signal.aborted) return;
      const preferredAudioIndex = chooseInitialAudioStreamIndex(
        item,
        playback.subtitleTracks,
        playback.audioStreams,
        playback.defaultAudioStreamIndex,
      );
      if (
        Number.isInteger(preferredAudioIndex) &&
        preferredAudioIndex !== playback.defaultAudioStreamIndex
      ) {
        const negotiatedPlayback = await fetchPlaybackInfo(item.Id, playbackController.signal, {
          mediaSourceId: playback.mediaSourceId,
          audioStreamIndex: preferredAudioIndex,
        });
        if (generation !== this.playbackGeneration || playbackController.signal.aborted) return;
        playback = {
          ...negotiatedPlayback,
          audioStreams: discoveredPlayback.audioStreams.length > 0
            ? discoveredPlayback.audioStreams
            : negotiatedPlayback.audioStreams,
          subtitleTracks: discoveredPlayback.subtitleTracks.length > 0
            ? discoveredPlayback.subtitleTracks
            : negotiatedPlayback.subtitleTracks,
          fontUrls: discoveredPlayback.fontUrls.length > 0
            ? discoveredPlayback.fontUrls
            : negotiatedPlayback.fontUrls,
        };
      }
      this.playback = playback;
      this.playingItem = item;
      this.populateAudioSelect(playback);
      this.populateSubtitleSelect(playback);
      this.attachPlaybackStream(playback, generation);
      this.playerStatus.textContent = '';
      const initialSubtitle = chooseInitialSubtitleTrack(
        item,
        playback.subtitleTracks,
        playback.audioStreams,
        playback.defaultAudioStreamIndex,
      );
      this.subtitleSelect.value = String(initialSubtitle.index);
      if (initialSubtitle.index >= 0) void this.selectSubtitle(initialSubtitle.index, false);
      await this.video.play().catch(() => undefined);
    } catch (error) {
      if (playbackController.signal.aborted || generation !== this.playbackGeneration) return;
      this.playerStatus.textContent = error instanceof Error ? error.message : 'Unable to start playback.';
    } finally {
      if (this.playbackAbortController === playbackController) {
        this.playbackAbortController = null;
      }
    }
  }

  private closePlayer() {
    this.resetPlayback();
    this.player.hidden = true;
    this.playerStatus.textContent = '';
  }

  private resetPlayback() {
    this.playbackGeneration++;
    this.playbackAbortController?.abort();
    this.playbackAbortController = null;
    this.subtitleGeneration++;
    this.subtitleAbortController?.abort();
    this.subtitleAbortController = null;
    this.subtitleRenderer?.dispose();
    this.subtitleRenderer = null;
    this.hls?.destroy();
    this.hls = null;
    this.video.pause();
    this.video.removeAttribute('src');
    this.video.load();
    this.playback = null;
    this.playingItem = null;
    this.audioSelect.replaceChildren(new Option('Default', '-1'));
    this.audioSelect.value = '-1';
    this.audioSelect.disabled = true;
    this.subtitleSelect.replaceChildren(new Option('Off', '-1'));
    this.subtitleSelect.value = '-1';
    this.subtitleSelect.disabled = true;
  }

  private populateSubtitleSelect(playback: JellyfinPlaybackInfo) {
    const options = [new Option('Off', '-1')];
    playback.subtitleTracks.forEach((track, index) => {
      const roles = [
        track.codec.toUpperCase(),
        track.isForced ? 'Forced / signs' : null,
        track.isHearingImpaired ? 'SDH' : null,
      ].filter(Boolean).join(' · ');
      options.push(new Option(`${track.label}${roles ? ` — ${roles}` : ''}`, String(index)));
    });
    this.subtitleSelect.replaceChildren(...options);
    this.subtitleSelect.value = '-1';
    this.subtitleSelect.disabled = playback.subtitleTracks.length === 0;
  }

  private populateAudioSelect(playback: JellyfinPlaybackInfo) {
    const options = playback.audioStreams.map((stream, ordinal) => {
      const index = Number.isInteger(stream.Index) ? stream.Index! : ordinal;
      const label = stream.DisplayTitle?.trim()
        || stream.Title?.trim()
        || stream.Language?.trim()
        || `Audio ${ordinal + 1}`;
      const details = [stream.Codec?.toUpperCase(), stream.ChannelLayout]
        .filter(Boolean)
        .join(' · ');
      return new Option(`${label}${details ? ` — ${details}` : ''}`, String(index));
    });
    this.audioSelect.replaceChildren(...options);
    this.audioSelect.value = String(playback.defaultAudioStreamIndex ?? options[0]?.value ?? -1);
    this.audioSelect.disabled = options.length < 2;
  }

  private attachPlaybackStream(
    playback: JellyfinPlaybackInfo,
    generation: number,
    resume?: {position: number; paused: boolean},
  ) {
    this.hls?.destroy();
    this.hls = null;

    const restorePlayback = () => {
      if (generation !== this.playbackGeneration || !resume) return;
      if (Number.isFinite(resume.position) && resume.position > 0) {
        this.video.currentTime = resume.position;
      }
      if (!resume.paused) void this.video.play().catch(() => undefined);
    };

    if (!Hls.isSupported()) {
      this.video.src = mediaUrlWithAccessToken(playback.streamUrl);
      if (resume) this.video.addEventListener('loadedmetadata', restorePlayback, {once: true});
      return;
    }

    const hls = streamingFetchSupported()
      ? new Hls({
          loader: FetchLoader,
          fetchSetup: (context, init) => {
            const headers = new Headers(init.headers);
            for (const [name, value] of Object.entries(playback.requiredHeaders)) {
              headers.set(name, value);
            }
            return createJellyfinRequest(
              resolveJellyfinRequestUrl(context.url),
              {...init, headers},
            );
          },
        })
      : new Hls({
          xhrSetup: (request, requestUrl) => {
            request.open('GET', resolveJellyfinRequestUrl(requestUrl), true);
            for (const [name, value] of Object.entries(playback.requiredHeaders)) {
              request.setRequestHeader(name, value);
            }
          },
        });
    this.hls = hls;
    hls.loadSource(playback.streamUrl);
    hls.attachMedia(this.video);
    if (playback.subtitleTracks.length > 0) {
      hls.subtitleTrack = -1;
      hls.subtitleDisplay = false;
    }
    const applyNegotiatedAudio = () => {
      if (this.hls !== hls || generation !== this.playbackGeneration) return;
      const selected = playback.audioStreams.find((stream) =>
        stream.Index === playback.defaultAudioStreamIndex);
      const preferredLanguage = selected?.Language;
      const preferredLabel = (selected?.DisplayTitle || selected?.Title || '').toLowerCase();
      const exactIndex = hls.audioTracks.findIndex((track) =>
        subtitleLanguageMatches(track.lang, preferredLanguage) &&
        (!preferredLabel || track.name?.toLowerCase().includes(preferredLabel)));
      const languageIndex = hls.audioTracks.findIndex((track) =>
        subtitleLanguageMatches(track.lang, preferredLanguage));
      const index = exactIndex >= 0 ? exactIndex : languageIndex;
      if (index >= 0) hls.audioTrack = index;
    };
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      applyNegotiatedAudio();
      restorePlayback();
    });
    hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, applyNegotiatedAudio);
  }

  private async selectAudio(index: number, remember: boolean) {
    const currentPlayback = this.playback;
    const item = this.playingItem;
    const stream = currentPlayback?.audioStreams.find((candidate) => candidate.Index === index);
    if (!currentPlayback || !item || !stream || !Number.isInteger(stream.Index)) return;
    if (stream.Index === currentPlayback.defaultAudioStreamIndex) {
      if (remember) rememberAudioSelection(item, stream);
      return;
    }

    const resume = {position: this.video.currentTime, paused: this.video.paused};
    const previousIndex = currentPlayback.defaultAudioStreamIndex;
    const generation = ++this.playbackGeneration;
    this.playbackAbortController?.abort();
    const controller = new AbortController();
    this.playbackAbortController = controller;
    this.audioSelect.disabled = true;
    this.playerStatus.textContent = `Switching to ${stream.DisplayTitle || stream.Title || stream.Language || 'audio track'}…`;
    try {
      const negotiatedPlayback = await fetchPlaybackInfo(item.Id, controller.signal, {
        mediaSourceId: currentPlayback.mediaSourceId,
        audioStreamIndex: stream.Index,
      });
      if (controller.signal.aborted || generation !== this.playbackGeneration) return;
      // Audio-pinned Jellyfin responses may describe only the active rendition.
      // Preserve the discovery inventory so the user can switch back again.
      const playback: JellyfinPlaybackInfo = {
        ...negotiatedPlayback,
        audioStreams: currentPlayback.audioStreams.length > 0
          ? currentPlayback.audioStreams
          : negotiatedPlayback.audioStreams,
        subtitleTracks: currentPlayback.subtitleTracks.length > 0
          ? currentPlayback.subtitleTracks
          : negotiatedPlayback.subtitleTracks,
        fontUrls: currentPlayback.fontUrls.length > 0
          ? currentPlayback.fontUrls
          : negotiatedPlayback.fontUrls,
      };
      this.playback = playback;
      this.populateAudioSelect(playback);
      this.attachPlaybackStream(playback, generation, resume);
      if (remember) rememberAudioSelection(item, stream);
      this.playerStatus.textContent = '';
    } catch (error) {
      if (controller.signal.aborted || generation !== this.playbackGeneration) return;
      this.audioSelect.value = String(previousIndex ?? -1);
      this.audioSelect.disabled = currentPlayback.audioStreams.length < 2;
      this.playerStatus.textContent = error instanceof Error
        ? error.message
        : 'Could not switch audio tracks.';
    } finally {
      if (this.playbackAbortController === controller) this.playbackAbortController = null;
    }
  }

  private async selectSubtitle(index: number, remember: boolean) {
    const playback = this.playback;
    const item = this.playingItem;
    if (!playback || !item) return;
    const track = playback.subtitleTracks[index] ?? null;
    const generation = ++this.subtitleGeneration;
    this.subtitleAbortController?.abort();
    this.subtitleRenderer?.dispose();
    this.subtitleRenderer = null;
    this.subtitleSelect.value = track ? String(index) : '-1';
    if (!track) {
      if (remember) rememberSubtitleSelection(item, null);
      this.playerStatus.textContent = '';
      return;
    }

    const controller = new AbortController();
    this.subtitleAbortController = controller;
    this.playerStatus.textContent = `Loading ${track.label}…`;
    try {
      const renderer = await AnimeSubtitleRenderer.create({
        track,
        fontUrls: playback.fontUrls,
        video: this.video,
        signal: controller.signal,
        onReady: () => {
          if (generation === this.subtitleGeneration) this.playerStatus.textContent = '';
        },
        onError: (error) => {
          if (generation !== this.subtitleGeneration) return;
          console.error('Styled subtitle renderer failed:', error);
          this.subtitleRenderer = null;
          this.subtitleSelect.value = '-1';
          this.playerStatus.textContent = 'Styled subtitles could not be rendered; video playback is continuing.';
        },
      });
      if (generation !== this.subtitleGeneration || controller.signal.aborted) {
        renderer.dispose();
        return;
      }
      this.subtitleRenderer = renderer;
      if (remember) rememberSubtitleSelection(item, track);
      this.playerStatus.textContent = '';
    } catch (error) {
      if (controller.signal.aborted || generation !== this.subtitleGeneration) return;
      console.error('Could not load styled subtitles:', error);
      this.playerStatus.textContent = error instanceof Error
        ? error.message
        : 'Could not load the selected subtitle track.';
      this.subtitleSelect.value = '-1';
    } finally {
      if (this.subtitleAbortController === controller) this.subtitleAbortController = null;
    }
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
