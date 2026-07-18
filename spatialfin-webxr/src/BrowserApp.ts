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
  getVersionChipLabel,
  type JellyfinImageType,
  type JellyfinItem,
  type SeerrResult,
  type JellyfinPlaybackInfo,
  type JellyfinView,
  type JellyfinChapter,
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
import { syncPlayCoordinator } from './SyncPlayCoordinator';
import { MusicView } from './musicassistant/MusicView';
import { createItemButtonsBar } from './components/ItemButtonsBar';
import { offlineMediaRepository } from './OfflineMediaRepository';

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

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00';
  const totalSec = Math.floor(seconds);
  const hrs = Math.floor(totalSec / 3600);
  const mins = Math.floor((totalSec % 3600) / 60);
  const secs = totalSec % 60;
  if (hrs > 0) {
    return `${hrs}:${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  }
  return `${mins}:${secs.toString().padStart(2, '0')}`;
}

/** A browser-first Jellyfin client. XR is deliberately a separate opt-in mode. */
export class BrowserApp {
  private readonly root: HTMLElement;
  private readonly content: HTMLElement;
  private readonly player: HTMLElement;
  private readonly video: HTMLVideoElement;
  private readonly playerBackBtn: HTMLButtonElement;
  private readonly playerItemTitle: HTMLElement;
  private readonly playerItemSubtitle: HTMLElement;
  private readonly playerMetaPill: HTMLElement;
  private readonly playerCastBtn: HTMLButtonElement;
  private readonly playerAudioBtn: HTMLButtonElement;
  private readonly playerSubtitlesBtn: HTMLButtonElement;
  private readonly playerChaptersBtn: HTMLButtonElement;
  private readonly playerSourceBtn: HTMLButtonElement;
  private readonly playerSyncplayBtn: HTMLButtonElement;
  private readonly playerQualityBtn: HTMLButtonElement;
  private readonly playerPrevChapterBtn: HTMLButtonElement;
  private readonly playerRewindBtn: HTMLButtonElement;
  private readonly playerPlaypauseBtn: HTMLButtonElement;
  private readonly playIcon: SVGElement;
  private readonly pauseIcon: SVGElement;
  private readonly playerFfwdBtn: HTMLButtonElement;
  private readonly playerNextChapterBtn: HTMLButtonElement;
  private readonly playerSkipSegmentBtn: HTMLButtonElement;
  private readonly playerPlayNextBtn: HTMLButtonElement;
  private readonly playerPlayNextLabel: HTMLElement;
  private readonly playerChapterTitle: HTMLElement;
  private readonly playerTimeCurrent: HTMLElement;
  private readonly playerTimeDuration: HTMLElement;
  private readonly playerScrubberInput: HTMLInputElement;
  private readonly playerScrubberFill: HTMLElement;
  private readonly playerScrubberThumb: HTMLElement;
  private readonly playerScrubberTicks: HTMLElement;
  private readonly playerControlsOverlay: HTMLElement;
  private readonly playerTouchInterceptor: HTMLElement;
  private readonly playerPauseOverlay: HTMLElement;
  private readonly pauseOverlayTitle: HTMLElement;
  private readonly pauseOverlaySubtitle: HTMLElement;
  private readonly pauseOverlayClock: HTMLElement;
  private readonly pauseOverlayEta: HTMLElement;
  private readonly playerLoadingSpinner: HTMLElement;
  private readonly playerStatus: HTMLElement;
  private readonly playerDialogBackdrop: HTMLElement;
  private readonly playerDialogTitle: HTMLElement;
  private readonly playerDialogBody: HTMLElement;
  private readonly playerDialogCloseBtn: HTMLButtonElement;
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

  private controlsVisible = true;
  private autoHideTimer: number | null = null;
  private clockTimer: number | null = null;
  private isDraggingScrubber = false;
  private activeDialogType: string | null = null;
  private currentChapters: JellyfinChapter[] = [];
  private nextEpisodeItem: JellyfinItem | null = null;
  private selectedSubtitleIndex = -1;
  private currentMaxBitrate = 0;

  constructor() {
    this.root = this.requireElement('#browser-app');
    this.content = this.requireElement('#browser-content');
    this.player = this.requireElement('#browser-player');
    this.video = this.requireElement<HTMLVideoElement>('#browser-video');
    this.playerBackBtn = this.requireElement<HTMLButtonElement>('#browser-player-back');
    this.playerItemTitle = this.requireElement('#browser-player-title');
    this.playerItemSubtitle = this.requireElement('#browser-player-subtitle');
    this.playerMetaPill = this.requireElement('#browser-player-meta-pill');
    this.playerCastBtn = this.requireElement<HTMLButtonElement>('#browser-player-cast-btn');
    this.playerAudioBtn = this.requireElement<HTMLButtonElement>('#browser-player-audio-btn');
    this.playerSubtitlesBtn = this.requireElement<HTMLButtonElement>('#browser-player-subtitles-btn');
    this.playerChaptersBtn = this.requireElement<HTMLButtonElement>('#browser-player-chapters-btn');
    this.playerSourceBtn = this.requireElement<HTMLButtonElement>('#browser-player-source-btn');
    this.playerSyncplayBtn = this.requireElement<HTMLButtonElement>('#browser-player-syncplay-btn');
    this.playerQualityBtn = this.requireElement<HTMLButtonElement>('#browser-player-quality-btn');
    this.playerPrevChapterBtn = this.requireElement<HTMLButtonElement>('#browser-player-prev-chapter');
    this.playerRewindBtn = this.requireElement<HTMLButtonElement>('#browser-player-rewind');
    this.playerPlaypauseBtn = this.requireElement<HTMLButtonElement>('#browser-player-playpause');
    this.playIcon = this.requireElement<SVGElement>('#playpause-icon-play');
    this.pauseIcon = this.requireElement<SVGElement>('#playpause-icon-pause');
    this.playerFfwdBtn = this.requireElement<HTMLButtonElement>('#browser-player-ffwd');
    this.playerNextChapterBtn = this.requireElement<HTMLButtonElement>('#browser-player-next-chapter');
    this.playerSkipSegmentBtn = this.requireElement<HTMLButtonElement>('#browser-player-skip-segment');
    this.playerPlayNextBtn = this.requireElement<HTMLButtonElement>('#browser-player-play-next');
    this.playerPlayNextLabel = this.requireElement('#player-play-next-label');
    this.playerChapterTitle = this.requireElement('#browser-player-chapter-title');
    this.playerTimeCurrent = this.requireElement('#browser-player-time-current');
    this.playerTimeDuration = this.requireElement('#browser-player-time-duration');
    this.playerScrubberInput = this.requireElement<HTMLInputElement>('#browser-player-scrubber-input');
    this.playerScrubberFill = this.requireElement('#player-scrubber-fill');
    this.playerScrubberThumb = this.requireElement('#player-scrubber-thumb');
    this.playerScrubberTicks = this.requireElement('#player-scrubber-ticks');
    this.playerControlsOverlay = this.requireElement('#browser-player-controls');
    this.playerTouchInterceptor = this.requireElement('#browser-player-touch-interceptor');
    this.playerPauseOverlay = this.requireElement('#browser-player-pause-overlay');
    this.pauseOverlayTitle = this.requireElement('#pause-overlay-title');
    this.pauseOverlaySubtitle = this.requireElement('#pause-overlay-subtitle');
    this.pauseOverlayClock = this.requireElement('#pause-overlay-clock');
    this.pauseOverlayEta = this.requireElement('#pause-overlay-eta');
    this.playerLoadingSpinner = this.requireElement('#browser-player-loading-spinner');
    this.playerStatus = this.requireElement('#browser-player-status');
    this.playerDialogBackdrop = this.requireElement('#browser-player-dialog-backdrop');
    this.playerDialogTitle = this.requireElement('#player-dialog-title');
    this.playerDialogBody = this.requireElement('#player-dialog-body');
    this.playerDialogCloseBtn = this.requireElement<HTMLButtonElement>('#player-dialog-close');
    this.searchInput = this.requireElement<HTMLInputElement>('#browser-search-input');

    document.querySelectorAll<HTMLButtonElement>('[data-browser-route]').forEach((button) => {
      button.addEventListener('click', () => void this.showRoute(button.dataset.browserRoute ?? 'home'));
    });

    this.bindPlayerEvents();

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

  public async showOfflineMode() {
    this.visible = true;
    this.root.hidden = false;
    await this.showRoute('offline');
  }

  private async showRoute(route: string) {
    this.setActiveRoute(route);
    this.revokeImages();
    if (route === 'libraries') {
      await this.showLibraries();
      return;
    }
    if (route === 'music') {
      await this.showMusic();
      return;
    }
    if (route === 'offline') {
      await this.showOffline();
      return;
    }
    await this.showHome();
  }

  private async showMusic() {
    const musicView = new MusicView(this.content);
    await musicView.render();
  }

  private async showOffline() {
    this.loading('Loading local and downloaded media…');
    try {
      const downloadedItems = await offlineMediaRepository.getAllDownloadedItems();
      this.renderOfflinePage(downloadedItems);
    } catch (error) {
      this.error(error);
    }
  }

  private renderOfflinePage(downloadedItems: JellyfinItem[]) {
    this.content.innerHTML = `
      <section class="browser-page-heading">
        <p class="eyebrow">Offline Media</p>
        <h1>Local Files & Downloaded Media</h1>
        <p class="section-description">Play videos downloaded from Jellyfin or select any local video file from your device.</p>
      </section>

      <div class="offline-actions-card" style="background: rgba(255,255,255,0.05); border: 1px dashed rgba(255,255,255,0.2); border-radius: 12px; padding: 24px; text-align: center; margin-bottom: 32px;">
        <h3 style="margin-top:0; margin-bottom: 8px; font-size: 20px; color: #fff;">Open Local Video File</h3>
        <p style="color: #aeb4be; margin-bottom: 16px;">Play any video file (MP4, MKV, WebM) stored on your local disk in 2D or WebXR mode.</p>
        <button type="button" id="btn-pick-local-file" class="primary-action hero-play-btn" style="display: inline-flex; align-items: center; gap: 8px; font-size: 16px; padding: 12px 24px;">
          📂 Select Local Video File
        </button>
        <input type="file" id="local-file-input" accept="video/*,.mp4,.mkv,.webm,.avi,.mov" style="display: none;" />
      </div>

      <section class="home-shelf">
        <h2>Downloaded Jellyfin Items (${downloadedItems.length})</h2>
        <div class="shelf-grid offline-downloaded-grid"></div>
      </section>
    `;

    const pickBtn = this.content.querySelector<HTMLButtonElement>('#btn-pick-local-file');
    const fileInput = this.content.querySelector<HTMLInputElement>('#local-file-input');

    if (pickBtn && fileInput) {
      pickBtn.onclick = () => fileInput.click();
      fileInput.onchange = () => {
        const file = fileInput.files?.[0];
        if (file) {
          const objectUrl = URL.createObjectURL(file);
          this.objectUrls.add(objectUrl);
          const localItem: JellyfinItem = {
            Id: `local_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
            Name: file.name.replace(/\.[^/.]+$/, ''),
            Type: 'Movie',
            RunTimeTicks: 0,
            MediaSources: [{
              Id: `local_source_${Date.now()}`,
              Path: file.name,
              Container: file.name.split('.').pop() || 'mp4',
              DirectStreamUrl: objectUrl,
            }]
          };
          void this.openPlayer(localItem);
        }
      };
    }

    const grid = this.content.querySelector<HTMLElement>('.offline-downloaded-grid')!;
    if (downloadedItems.length === 0) {
      grid.innerHTML = `<p style="color: #aeb4be; grid-column: 1 / -1;">No downloaded Jellyfin media found. Download videos while connected to your server to play them offline.</p>`;
    } else {
      downloadedItems.forEach((item) => {
        const card = this.createCard(item);
        grid.appendChild(card);
      });
    }
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
          <div class="browser-hero-actions-slot"></div>
        </div>
      </section>
      ${myMediaHtml}
      <div class="browser-shelves"></div>`;
    if (lead) {
      const heroSlot = this.content.querySelector<HTMLElement>('.browser-hero-actions-slot');
      if (heroSlot) {
        const buttonsBar = createItemButtonsBar({
          item: lead,
          onPlay: (itemToPlay) => void this.play(itemToPlay),
          onShowDetails: () => void this.showItem(lead),
          onMetadataUpdated: () => void this.showHome(),
        });
        heroSlot.replaceWith(buttonsBar);
      }
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

    this.content.innerHTML = `<section class="detail-page"><button class="back-button" type="button">← Back</button><div class="detail-backdrop"></div><div class="detail-copy"><p class="eyebrow">${this.escape(item.Type ?? 'Video')}</p><h1>${this.escape(item.Name)}</h1><p class="detail-meta">${this.escape(metadata(item))}</p>${pillsHtml}<p style="margin-top: 1rem;">${this.escape(item.Overview ?? 'No synopsis is available.')}</p>${versionSelectHtml}<div class="browser-hero-actions-slot"></div></div><div class="detail-extra-rows" style="position: relative; z-index: 1; padding: 0 clamp(32px, 6vw, 88px) 40px;">${nextUpHtml}${seasonsHtml}${castHtml}</div></section>`;
    
    let playItem = item;
    
    this.content.querySelector<HTMLButtonElement>('.back-button')?.addEventListener('click', () => void this.showRoute('home'));

    const heroSlot = this.content.querySelector<HTMLElement>('.browser-hero-actions-slot');
    if (heroSlot) {
      const buttonsBar = createItemButtonsBar({
        item,
        versions,
        onPlay: (itemToPlay, startFromBeginning) => {
          const targetItem = itemToPlay || playItem;
          if (startFromBeginning && targetItem.UserData) {
            targetItem.UserData.PlaybackPositionTicks = 0;
          }
          void this.play(targetItem);
        },
        onVersionChange: (selectedVersion) => {
          playItem = selectedVersion;
        },
        onMetadataUpdated: () => void this.showItem(item),
      });
      heroSlot.replaceWith(buttonsBar);
    }
    
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

  private bindPlayerEvents() {
    this.playerTouchInterceptor.addEventListener('click', () => this.toggleControls());
    this.player.addEventListener('mousemove', () => this.resetAutoHideTimer());
    this.playerBackBtn.addEventListener('click', () => this.closePlayer());
    this.playerPlaypauseBtn.addEventListener('click', () => this.togglePlayPause());
    this.playerRewindBtn.addEventListener('click', () => this.seekBy(-10));
    this.playerFfwdBtn.addEventListener('click', () => this.seekBy(10));
    this.playerPrevChapterBtn.addEventListener('click', () => this.seekChapter(-1));
    this.playerNextChapterBtn.addEventListener('click', () => this.seekChapter(1));
    this.playerPlayNextBtn.addEventListener('click', () => {
      if (this.nextEpisodeItem) void this.openPlayer(this.nextEpisodeItem);
    });
    this.playerAudioBtn.addEventListener('click', () => this.openDialog('audio'));
    this.playerSubtitlesBtn.addEventListener('click', () => this.openDialog('subtitles'));
    this.playerQualityBtn.addEventListener('click', () => this.openDialog('quality'));
    this.playerChaptersBtn.addEventListener('click', () => this.openDialog('chapters'));
    this.playerSourceBtn.addEventListener('click', () => this.openDialog('source'));
    this.playerSyncplayBtn.addEventListener('click', () => this.openDialog('syncplay'));
    this.playerCastBtn.addEventListener('click', () => this.openDialog('cast'));
    this.playerDialogCloseBtn.addEventListener('click', () => this.closeDialog());
    this.playerDialogBackdrop.addEventListener('click', (e) => {
      if (e.target === this.playerDialogBackdrop) this.closeDialog();
    });

    this.playerScrubberInput.addEventListener('input', () => {
      this.isDraggingScrubber = true;
      this.resetAutoHideTimer();
      const pct = parseFloat(this.playerScrubberInput.value);
      this.playerScrubberFill.style.width = `${pct}%`;
      this.playerScrubberThumb.style.left = `${pct}%`;
      const duration = this.video.duration || 0;
      const scrubTime = (pct / 100) * duration;
      this.playerTimeCurrent.textContent = formatTime(scrubTime);
      this.updateChapterHeadline(scrubTime);
    });

    this.playerScrubberInput.addEventListener('change', () => {
      const pct = parseFloat(this.playerScrubberInput.value);
      const duration = this.video.duration || 0;
      this.video.currentTime = (pct / 100) * duration;
      this.isDraggingScrubber = false;
      this.resetAutoHideTimer();
    });

    this.video.addEventListener('timeupdate', () => this.updatePlayerProgress());
    this.video.addEventListener('play', () => {
      this.playIcon.style.display = 'none';
      this.pauseIcon.style.display = 'block';
      this.playerLoadingSpinner.hidden = true;
      this.resetAutoHideTimer();
      this.updatePauseOverlay();
    });
    this.video.addEventListener('pause', () => {
      this.playIcon.style.display = 'block';
      this.pauseIcon.style.display = 'none';
      this.clearAutoHideTimer();
      this.setControlsVisible(true);
      this.updatePauseOverlay();
    });
    this.video.addEventListener('waiting', () => {
      this.playerLoadingSpinner.hidden = false;
    });
    this.video.addEventListener('playing', () => {
      this.playerLoadingSpinner.hidden = true;
    });
    this.video.addEventListener('ended', () => {
      this.playIcon.style.display = 'block';
      this.pauseIcon.style.display = 'none';
      if (this.nextEpisodeItem) {
        void this.openPlayer(this.nextEpisodeItem);
      }
    });

    window.addEventListener('keydown', (e) => {
      if (this.player.hidden) return;
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      switch (e.key) {
        case ' ':
        case 'k':
        case 'K':
          e.preventDefault();
          this.togglePlayPause();
          break;
        case 'ArrowLeft':
        case 'j':
        case 'J':
          e.preventDefault();
          this.seekBy(-10);
          break;
        case 'ArrowRight':
        case 'l':
        case 'L':
          e.preventDefault();
          this.seekBy(10);
          break;
        case 'f':
        case 'F':
          e.preventDefault();
          this.toggleFullscreen();
          break;
        case 'm':
        case 'M':
          e.preventDefault();
          this.video.muted = !this.video.muted;
          this.playerStatus.textContent = this.video.muted ? 'Muted' : 'Unmuted';
          window.setTimeout(() => { if (this.playerStatus.textContent === 'Muted' || this.playerStatus.textContent === 'Unmuted') this.playerStatus.textContent = ''; }, 2000);
          break;
        case 'Escape':
          if (this.activeDialogType) {
            this.closeDialog();
          } else {
            this.closePlayer();
          }
          break;
      }
    });
  }

  private async play(item: JellyfinItem) {
    await this.openPlayer(item);
  }

  async openPlayer(item: JellyfinItem) {
    if (this.onPlayRequest && this.onPlayRequest(item)) {
      return;
    }
    this.resetPlayback();
    const generation = ++this.playbackGeneration;
    const playbackController = new AbortController();
    this.playbackAbortController = playbackController;
    this.player.hidden = false;

    this.playerItemTitle.textContent = item.Name;
    if (item.Type === 'Episode') {
      const parts: string[] = [];
      if (item.SeriesName) parts.push(item.SeriesName);
      if (item.ParentIndexNumber != null && item.IndexNumber != null) {
        parts.push(`S${item.ParentIndexNumber}:E${item.IndexNumber}`);
      } else if (item.IndexNumber != null) {
        parts.push(`Episode ${item.IndexNumber}`);
      }
      this.playerItemSubtitle.textContent = parts.join(' · ');
    } else {
      const parts: string[] = [];
      if (item.ProductionYear) parts.push(String(item.ProductionYear));
      if (item.OfficialRating) parts.push(item.OfficialRating);
      this.playerItemSubtitle.textContent = parts.join(' · ');
    }

    const pills = extractMediaPills(item);
    if (pills.length > 0) {
      this.playerMetaPill.textContent = pills.join(' · ');
      this.playerMetaPill.hidden = false;
    } else {
      this.playerMetaPill.hidden = true;
    }

    this.playerStatus.textContent = 'Preparing stream…';
    this.playerLoadingSpinner.hidden = false;
    this.setControlsVisible(true);
    this.resetAutoHideTimer();

    if (!this.clockTimer) {
      this.clockTimer = window.setInterval(() => this.updatePauseOverlay(), 1000);
    }

    try {
      if (item.MediaSources?.[0]?.DirectStreamUrl?.startsWith('blob:')) {
        this.playingItem = item;
        this.video.src = item.MediaSources[0].DirectStreamUrl;
        this.playerStatus.textContent = '';
        this.playerLoadingSpinner.hidden = true;
        this.video.play().catch((e) => console.error('Local play error', e));
        return;
      }

      if (await offlineMediaRepository.isItemDownloaded(item.Id)) {
        const offlineUrl = await offlineMediaRepository.getMediaFileUrl(item.Id);
        if (offlineUrl) {
          this.playingItem = item;
          this.video.src = offlineUrl;
          this.playerStatus.textContent = '';
          this.playerLoadingSpinner.hidden = true;
          this.video.play().catch((e) => console.error('Offline play error', e));
          return;
        }
      }

      void fetchItem(item.Id, playbackController.signal).then((fullItem) => {
        if (generation !== this.playbackGeneration || playbackController.signal.aborted) return;
        this.currentChapters = fullItem.Chapters || [];
        this.renderChapterTicks();
        if (this.currentChapters.length > 0) {
          this.playerChaptersBtn.hidden = false;
          this.playerPrevChapterBtn.hidden = false;
          this.playerNextChapterBtn.hidden = false;
        } else {
          this.playerChaptersBtn.hidden = true;
          this.playerPrevChapterBtn.hidden = true;
          this.playerNextChapterBtn.hidden = true;
        }

        const seriesId = fullItem.SeriesId || (fullItem.Type === 'Episode' ? item.SeriesId : undefined);
        if (seriesId) {
          void fetchNextUp(5, seriesId, playbackController.signal).then((nextItems) => {
            if (generation !== this.playbackGeneration) return;
            const candidate = nextItems.find((n) => n.Id !== item.Id);
            if (candidate) {
              this.nextEpisodeItem = candidate;
              this.playerPlayNextLabel.textContent = `Play Next: ${candidate.Name}`;
              this.playerPlayNextBtn.hidden = false;
            } else {
              this.playerPlayNextBtn.hidden = true;
            }
          }).catch(() => undefined);
        }
      }).catch(() => undefined);

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

      const sources = item.MediaSources || [];
      this.playerSourceBtn.hidden = sources.length <= 1;

      this.attachPlaybackStream(playback, generation);
      this.playerStatus.textContent = '';
      const initialSubtitle = chooseInitialSubtitleTrack(
        item,
        playback.subtitleTracks,
        playback.audioStreams,
        playback.defaultAudioStreamIndex,
      );
      this.selectedSubtitleIndex = initialSubtitle.index;
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
    if (this.clockTimer != null) {
      window.clearInterval(this.clockTimer);
      this.clockTimer = null;
    }
    this.closeDialog();
    if (document.fullscreenElement) {
      void document.exitFullscreen().catch(() => undefined);
    }
  }

  private resetPlayback() {
    this.clearAutoHideTimer();
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
    this.currentChapters = [];
    this.nextEpisodeItem = null;
    this.selectedSubtitleIndex = -1;
    this.isDraggingScrubber = false;
    this.playerScrubberFill.style.width = '0%';
    this.playerScrubberThumb.style.left = '0%';
    this.playerScrubberInput.value = '0';
    this.playerScrubberTicks.replaceChildren();
    this.playerChapterTitle.hidden = true;
    this.playerSkipSegmentBtn.hidden = true;
    this.playerPlayNextBtn.hidden = true;
    this.playerChaptersBtn.hidden = true;
    this.playerPrevChapterBtn.hidden = true;
    this.playerNextChapterBtn.hidden = true;
    this.playerPauseOverlay.hidden = true;
  }

  private updatePlayerProgress() {
    const duration = this.video.duration || 0;
    const current = this.video.currentTime || 0;
    this.playerTimeCurrent.textContent = formatTime(current);
    this.playerTimeDuration.textContent = formatTime(duration);

    if (!this.isDraggingScrubber && duration > 0) {
      const pct = (current / duration) * 100;
      this.playerScrubberInput.value = pct.toFixed(1);
      this.playerScrubberFill.style.width = `${pct}%`;
      this.playerScrubberThumb.style.left = `${pct}%`;
      this.updateChapterHeadline(current);
    }
  }

  private renderChapterTicks() {
    this.playerScrubberTicks.replaceChildren();
    const duration = this.video.duration || (this.playingItem?.RunTimeTicks ? this.playingItem.RunTimeTicks / 10_000_000 : 0);
    if (!duration || this.currentChapters.length === 0) return;
    this.currentChapters.forEach((chap) => {
      const startSec = chap.StartPositionTicks / 10_000_000;
      const pct = (startSec / duration) * 100;
      if (pct >= 0 && pct <= 100) {
        const tick = document.createElement('div');
        tick.className = 'player-chapter-tick';
        tick.style.left = `${pct}%`;
        this.playerScrubberTicks.appendChild(tick);
      }
    });
  }

  private updateChapterHeadline(currentTimeSec: number) {
    if (this.currentChapters.length === 0) {
      this.playerChapterTitle.hidden = true;
      return;
    }
    const chapter = this.currentChapters.slice().reverse().find((c) => (c.StartPositionTicks / 10_000_000) <= currentTimeSec);
    if (chapter && chapter.Name) {
      this.playerChapterTitle.textContent = chapter.Name;
      this.playerChapterTitle.hidden = false;
    } else {
      this.playerChapterTitle.hidden = true;
    }
  }

  private updatePauseOverlay() {
    if (!this.playingItem) return;
    const title = this.playingItem.Name;
    let subtitle = '';
    if (this.playingItem.Type === 'Episode' && this.playingItem.SeriesName) {
      subtitle = this.playingItem.SeriesName;
    } else if (this.playingItem.ProductionYear) {
      subtitle = String(this.playingItem.ProductionYear);
    }
    this.pauseOverlayTitle.textContent = title;
    this.pauseOverlaySubtitle.textContent = subtitle;

    const now = new Date();
    this.pauseOverlayClock.textContent = now.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

    const duration = this.video.duration || 0;
    const current = this.video.currentTime || 0;
    const remainingSec = Math.max(0, duration - current);
    const etaDate = new Date(Date.now() + remainingSec * 1000);
    const etaClockStr = etaDate.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    const remHours = Math.floor(remainingSec / 3600);
    const remMins = Math.floor((remainingSec % 3600) / 60);
    const remStr = remHours > 0 ? `${remHours}h ${remMins}m` : `${remMins}m`;

    this.pauseOverlayEta.textContent = `Ends at ${etaClockStr} · ${remStr} left`;
  }

  private toggleControls() {
    this.setControlsVisible(!this.controlsVisible);
  }

  private setControlsVisible(visible: boolean) {
    this.controlsVisible = visible;
    if (visible) {
      this.playerControlsOverlay.classList.remove('is-hidden');
      this.playerPauseOverlay.hidden = true;
      this.resetAutoHideTimer();
    } else {
      this.playerControlsOverlay.classList.add('is-hidden');
      if (this.video.paused) {
        this.playerPauseOverlay.hidden = false;
      }
    }
  }

  private resetAutoHideTimer() {
    this.clearAutoHideTimer();
    if (!this.controlsVisible) {
      this.setControlsVisible(true);
    }
    if (!this.video.paused) {
      this.autoHideTimer = window.setTimeout(() => {
        if (!this.video.paused && !this.isDraggingScrubber && !this.activeDialogType) {
          this.setControlsVisible(false);
        }
      }, 5000);
    }
  }

  private clearAutoHideTimer() {
    if (this.autoHideTimer != null) {
      window.clearTimeout(this.autoHideTimer);
      this.autoHideTimer = null;
    }
  }

  private seekChapter(direction: number) {
    if (this.currentChapters.length === 0) return;
    const currentTime = this.video.currentTime || 0;
    const currentIndex = this.currentChapters.findIndex((c) => (c.StartPositionTicks / 10_000_000) > currentTime + 1);
    let targetIndex = 0;
    if (direction > 0) {
      targetIndex = currentIndex >= 0 ? currentIndex : this.currentChapters.length - 1;
    } else {
      const prevIndex = (currentIndex >= 0 ? currentIndex : this.currentChapters.length) - 2;
      targetIndex = Math.max(0, prevIndex);
    }
    const targetTime = this.currentChapters[targetIndex].StartPositionTicks / 10_000_000;
    this.video.currentTime = targetTime;
    this.resetAutoHideTimer();
  }

  private seekBy(seconds: number) {
    const duration = this.video.duration || 0;
    const newTime = Math.min(Math.max(0, (this.video.currentTime || 0) + seconds), duration);
    this.video.currentTime = newTime;
    this.resetAutoHideTimer();
  }

  private togglePlayPause() {
    if (this.video.paused) {
      void this.video.play().catch(() => undefined);
    } else {
      this.video.pause();
    }
    this.resetAutoHideTimer();
  }

  private toggleFullscreen() {
    if (!document.fullscreenElement) {
      void this.player.requestFullscreen().catch(() => undefined);
    } else {
      void document.exitFullscreen().catch(() => undefined);
    }
  }

  private openDialog(type: string) {
    this.activeDialogType = type;
    this.playerDialogBody.replaceChildren();

    switch (type) {
      case 'audio': {
        this.playerDialogTitle.textContent = 'Select Audio Track';
        const streams = this.playback?.audioStreams || [];
        if (streams.length === 0) {
          this.playerDialogBody.innerHTML = '<div class="player-dialog-item">No audio tracks available</div>';
        } else {
          streams.forEach((stream) => {
            const index = stream.Index ?? -1;
            const isSelected = index === this.playback?.defaultAudioStreamIndex;
            const itemEl = document.createElement('div');
            itemEl.className = `player-dialog-item${isSelected ? ' is-selected' : ''}`;
            const title = stream.DisplayTitle || stream.Title || stream.Language || `Audio ${index}`;
            const details = [stream.Codec?.toUpperCase(), stream.ChannelLayout].filter(Boolean).join(' · ');
            itemEl.innerHTML = `<span>${this.escape(title)}${details ? ` — <small>${this.escape(details)}</small>` : ''}</span>${isSelected ? '✓' : ''}`;
            itemEl.addEventListener('click', () => {
              void this.selectAudio(index, true);
              this.closeDialog();
            });
            this.playerDialogBody.appendChild(itemEl);
          });
        }
        break;
      }
      case 'subtitles': {
        this.playerDialogTitle.textContent = 'Select Subtitles';
        const tracks = this.playback?.subtitleTracks || [];
        
        const offEl = document.createElement('div');
        offEl.className = `player-dialog-item${this.selectedSubtitleIndex === -1 ? ' is-selected' : ''}`;
        offEl.innerHTML = `<span>Off</span>${this.selectedSubtitleIndex === -1 ? '✓' : ''}`;
        offEl.addEventListener('click', () => {
          void this.selectSubtitle(-1, true);
          this.closeDialog();
        });
        this.playerDialogBody.appendChild(offEl);

        tracks.forEach((track, idx) => {
          const isSelected = idx === this.selectedSubtitleIndex;
          const itemEl = document.createElement('div');
          itemEl.className = `player-dialog-item${isSelected ? ' is-selected' : ''}`;
          const roles = [
            track.codec.toUpperCase(),
            track.isForced ? 'Forced' : null,
            track.isHearingImpaired ? 'SDH' : null,
          ].filter(Boolean).join(' · ');
          itemEl.innerHTML = `<span>${this.escape(track.label)}${roles ? ` — <small>${this.escape(roles)}</small>` : ''}</span>${isSelected ? '✓' : ''}`;
          itemEl.addEventListener('click', () => {
            void this.selectSubtitle(idx, true);
            this.closeDialog();
          });
          this.playerDialogBody.appendChild(itemEl);
        });
        break;
      }
      case 'quality': {
        this.playerDialogTitle.textContent = 'Streaming Quality';
        const options = [
          { label: 'Auto (Recommended)', bitrate: 0 },
          { label: '4K Ultra HD (120 Mbps)', bitrate: 120_000_000 },
          { label: '1080p (20 Mbps)', bitrate: 20_000_000 },
          { label: '720p (4 Mbps)', bitrate: 4_000_000 },
          { label: '480p (1.5 Mbps)', bitrate: 1_500_000 },
          { label: '360p (750 kbps)', bitrate: 750_000 },
        ];
        options.forEach((opt) => {
          const isSelected = this.currentMaxBitrate === opt.bitrate;
          const itemEl = document.createElement('div');
          itemEl.className = `player-dialog-item${isSelected ? ' is-selected' : ''}`;
          itemEl.innerHTML = `<span>${opt.label}</span>${isSelected ? '✓' : ''}`;
          itemEl.addEventListener('click', () => {
            void this.changeQuality(opt.bitrate);
            this.closeDialog();
          });
          this.playerDialogBody.appendChild(itemEl);
        });
        break;
      }
      case 'chapters': {
        this.playerDialogTitle.textContent = 'Chapters';
        if (this.currentChapters.length === 0) {
          this.playerDialogBody.innerHTML = '<div class="player-dialog-item">No chapters available</div>';
        } else {
          const duration = this.video.duration || 1;
          const currentTime = this.video.currentTime || 0;
          this.currentChapters.forEach((chap, idx) => {
            const chapTime = chap.StartPositionTicks / 10_000_000;
            const nextChapTime = this.currentChapters[idx + 1] ? (this.currentChapters[idx + 1].StartPositionTicks / 10_000_000) : duration;
            const isSelected = currentTime >= chapTime && currentTime < nextChapTime;
            const itemEl = document.createElement('div');
            itemEl.className = `player-dialog-item${isSelected ? ' is-selected' : ''}`;
            const timeStr = formatTime(chapTime);
            const title = chap.Name || `Chapter ${idx + 1}`;
            itemEl.innerHTML = `<span><small style="color: #4fc3f7; margin-right: 8px;">${timeStr}</small> ${this.escape(title)}</span>${isSelected ? '✓' : ''}`;
            itemEl.addEventListener('click', () => {
              this.video.currentTime = chapTime;
              this.closeDialog();
            });
            this.playerDialogBody.appendChild(itemEl);
          });
        }
        break;
      }
      case 'source': {
        this.playerDialogTitle.textContent = 'Media Source';
        const sources = this.playingItem?.MediaSources || [];
        sources.forEach((src, idx) => {
          const isSelected = idx === 0;
          const itemEl = document.createElement('div');
          itemEl.className = `player-dialog-item${isSelected ? ' is-selected' : ''}`;
          itemEl.innerHTML = `<span>${this.escape(src.Name || `Source ${idx + 1}`)}</span>${isSelected ? '✓' : ''}`;
          itemEl.addEventListener('click', () => {
            this.closeDialog();
          });
          this.playerDialogBody.appendChild(itemEl);
        });
        break;
      }
      case 'syncplay': {
        this.playerDialogTitle.textContent = 'SyncPlay';
        const isSyncActive = syncPlayCoordinator.isActive();
        const itemEl = document.createElement('div');
        itemEl.className = `player-dialog-item${isSyncActive ? ' is-selected' : ''}`;
        itemEl.innerHTML = `<span>${isSyncActive ? 'Leave SyncPlay Group' : 'Create SyncPlay Group'}</span>`;
        itemEl.addEventListener('click', () => {
          if (isSyncActive) {
            void syncPlayCoordinator.leaveGroup();
          } else {
            void syncPlayCoordinator.createGroup();
          }
          this.closeDialog();
        });
        this.playerDialogBody.appendChild(itemEl);
        break;
      }
      case 'cast': {
        this.playerDialogTitle.textContent = 'Cast to Device';
        const castBtn = document.querySelector<HTMLButtonElement>('#browser-cast-button');
        if (castBtn) {
          castBtn.click();
        }
        this.closeDialog();
        return;
      }
    }

    this.playerDialogBackdrop.hidden = false;
  }

  private closeDialog() {
    this.activeDialogType = null;
    this.playerDialogBackdrop.hidden = true;
  }

  private async changeQuality(bitrate: number) {
    if (!this.playingItem) return;
    this.currentMaxBitrate = bitrate;
    const resume = { position: this.video.currentTime, paused: this.video.paused };
    const generation = ++this.playbackGeneration;
    this.playbackAbortController?.abort();
    const controller = new AbortController();
    this.playbackAbortController = controller;
    this.playerStatus.textContent = 'Changing quality…';
    try {
      const negotiatedPlayback = await fetchPlaybackInfo(this.playingItem.Id, controller.signal, {
        maxBitrate: bitrate > 0 ? bitrate : undefined,
      });
      if (controller.signal.aborted || generation !== this.playbackGeneration) return;
      this.playback = negotiatedPlayback;
      this.attachPlaybackStream(negotiatedPlayback, generation, resume);
      this.playerStatus.textContent = '';
      this.playerQualityBtn.classList.toggle('is-active', bitrate > 0);
    } catch (err) {
      if (controller.signal.aborted || generation !== this.playbackGeneration) return;
      this.playerStatus.textContent = err instanceof Error ? err.message : 'Could not change quality.';
    } finally {
      if (this.playbackAbortController === controller) this.playbackAbortController = null;
    }
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
    const generation = ++this.playbackGeneration;
    this.playbackAbortController?.abort();
    const controller = new AbortController();
    this.playbackAbortController = controller;
    this.playerStatus.textContent = `Switching to ${stream.DisplayTitle || stream.Title || stream.Language || 'audio track'}…`;
    try {
      const negotiatedPlayback = await fetchPlaybackInfo(item.Id, controller.signal, {
        mediaSourceId: currentPlayback.mediaSourceId,
        audioStreamIndex: stream.Index,
      });
      if (controller.signal.aborted || generation !== this.playbackGeneration) return;
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
      this.attachPlaybackStream(playback, generation, resume);
      if (remember) rememberAudioSelection(item, stream);
      this.playerStatus.textContent = '';
    } catch (error) {
      if (controller.signal.aborted || generation !== this.playbackGeneration) return;
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
    this.selectedSubtitleIndex = track ? index : -1;
    this.playerSubtitlesBtn.classList.toggle('is-active', track != null);
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
          this.selectedSubtitleIndex = -1;
          this.playerSubtitlesBtn.classList.remove('is-active');
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
      this.selectedSubtitleIndex = -1;
      this.playerSubtitlesBtn.classList.remove('is-active');
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

  private requireElement<T extends Element = HTMLElement>(selector: string): T {
    const element = document.querySelector<T>(selector);
    if (!element) throw new Error(`Missing required browser UI element: ${selector}`);
    return element;
  }
}
