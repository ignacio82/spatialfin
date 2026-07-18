import { MusicAssistantManager } from './MusicAssistantManager';
import type {
  MAAlbum,
  MAArtist,
  MAPlaylist,
  MATrack,
} from './MusicAssistantTypes';

export class MusicView {
  private manager: MusicAssistantManager;
  private container: HTMLElement;
  private currentTab: 'albums' | 'artists' | 'playlists' | 'tracks' = 'albums';

  constructor(container: HTMLElement) {
    this.container = container;
    this.manager = MusicAssistantManager.getInstance();
  }

  public async render(): Promise<void> {
    this.container.innerHTML = '';

    const header = document.createElement('div');
    header.style.display = 'flex';
    header.style.justifyContent = 'space-between';
    header.style.alignItems = 'center';
    header.style.marginBottom = '20px';

    const titleBox = document.createElement('div');
    const title = document.createElement('h2');
    title.style.fontSize = '2rem';
    title.style.margin = '0';
    title.textContent = 'Music Assistant';

    const sub = document.createElement('p');
    sub.style.color = '#aeb4be';
    sub.style.margin = '4px 0 0 0';
    sub.textContent = this.manager.client.connected
      ? `Connected to ${this.manager.client.server?.server_id || 'Server'}`
      : 'Connect to Music Assistant in Settings to browse music.';

    titleBox.appendChild(title);
    titleBox.appendChild(sub);

    const btnBox = document.createElement('div');
    const settingsBtn = document.createElement('button');
    settingsBtn.className = 'ma-status-button';
    settingsBtn.textContent = 'MA Settings';
    settingsBtn.onclick = () => {
      const btn = document.querySelector<HTMLButtonElement>('#ma-status-button');
      btn?.click();
    };
    btnBox.appendChild(settingsBtn);

    header.appendChild(titleBox);
    header.appendChild(btnBox);
    this.container.appendChild(header);

    if (!this.manager.client.connected) {
      const emptyState = document.createElement('div');
      emptyState.style.padding = '60px 20px';
      emptyState.style.textAlign = 'center';
      emptyState.style.background = '#13161c';
      emptyState.style.borderRadius = '16px';
      emptyState.style.border = '1px solid rgba(255,255,255,0.08)';

      const emptyTitle = document.createElement('h3');
      emptyTitle.textContent = 'Music Assistant Not Connected';
      const emptyDesc = document.createElement('p');
      emptyDesc.style.color = '#aeb4be';
      emptyDesc.style.maxWidth = '460px';
      emptyDesc.style.margin = '12px auto 24px';
      emptyDesc.textContent =
        'Enter your Music Assistant server URL in settings to stream high-quality audio directly to your browser or SpatialFin XR space.';

      const connBtn = document.createElement('button');
      connBtn.className = 'connect-button';
      connBtn.textContent = 'Configure Music Assistant';
      connBtn.onclick = () => settingsBtn.click();

      emptyState.appendChild(emptyTitle);
      emptyState.appendChild(emptyDesc);
      emptyState.appendChild(connBtn);
      this.container.appendChild(emptyState);
      return;
    }

    // Tab Bar
    const nav = document.createElement('div');
    nav.className = 'ma-tab-nav';

    const tabs: Array<{ id: 'albums' | 'artists' | 'playlists' | 'tracks'; label: string }> = [
      { id: 'albums', label: 'Albums' },
      { id: 'artists', label: 'Artists' },
      { id: 'playlists', label: 'Playlists' },
      { id: 'tracks', label: 'Tracks' },
    ];

    tabs.forEach((tab) => {
      const btn = document.createElement('button');
      btn.className = `ma-tab-btn ${this.currentTab === tab.id ? 'is-active' : ''}`;
      btn.textContent = tab.label;
      btn.onclick = () => {
        this.currentTab = tab.id;
        void this.render();
      };
      nav.appendChild(btn);
    });

    this.container.appendChild(nav);

    const contentArea = document.createElement('div');
    contentArea.className = 'ma-content-area';
    this.container.appendChild(contentArea);

    contentArea.innerHTML = '<p style="color:#aeb4be;">Loading media library...</p>';

    try {
      if (this.currentTab === 'albums') {
        const albums = await this.manager.client.getLibraryItems<MAAlbum>('album');
        this.renderAlbumsGrid(contentArea, albums);
      } else if (this.currentTab === 'artists') {
        const artists = await this.manager.client.getLibraryItems<MAArtist>('artist');
        this.renderArtistsGrid(contentArea, artists);
      } else if (this.currentTab === 'playlists') {
        const playlists = await this.manager.client.getLibraryItems<MAPlaylist>('playlist');
        this.renderPlaylistsGrid(contentArea, playlists);
      } else if (this.currentTab === 'tracks') {
        const tracks = await this.manager.client.getLibraryItems<MATrack>('track');
        this.renderTracksList(contentArea, tracks);
      }
    } catch (e) {
      contentArea.innerHTML = `<p style="color:#ffb4ab;">Failed to load media: ${e}</p>`;
    }
  }

  private renderAlbumsGrid(container: HTMLElement, albums: MAAlbum[]) {
    container.innerHTML = '';
    if (albums.length === 0) {
      container.innerHTML = '<p style="color:#aeb4be;">No albums found in library.</p>';
      return;
    }

    const grid = document.createElement('div');
    grid.className = 'ma-media-grid';

    albums.forEach((album) => {
      const card = document.createElement('div');
      card.className = 'ma-media-card';

      const img = document.createElement('img');
      img.className = 'ma-card-art';
      const imgUrl = this.manager.client.getImageUrl(album);
      if (imgUrl) img.src = imgUrl;

      const title = document.createElement('p');
      title.className = 'ma-card-title';
      title.textContent = album.name;

      const sub = document.createElement('p');
      sub.className = 'ma-card-subtitle';
      sub.textContent = album.artists?.map((a) => a.name).join(', ') || (album.year ? `${album.year}` : 'Album');

      card.appendChild(img);
      card.appendChild(title);
      card.appendChild(sub);

      card.onclick = () => void this.manager.playMedia(album);

      grid.appendChild(card);
    });

    container.appendChild(grid);
  }

  private renderArtistsGrid(container: HTMLElement, artists: MAArtist[]) {
    container.innerHTML = '';
    if (artists.length === 0) {
      container.innerHTML = '<p style="color:#aeb4be;">No artists found in library.</p>';
      return;
    }

    const grid = document.createElement('div');
    grid.className = 'ma-media-grid';

    artists.forEach((artist) => {
      const card = document.createElement('div');
      card.className = 'ma-media-card';

      const img = document.createElement('img');
      img.className = 'ma-card-art';
      img.style.borderRadius = '50%';
      const imgUrl = this.manager.client.getImageUrl(artist);
      if (imgUrl) img.src = imgUrl;

      const title = document.createElement('p');
      title.className = 'ma-card-title';
      title.style.textAlign = 'center';
      title.textContent = artist.name;

      card.appendChild(img);
      card.appendChild(title);

      card.onclick = () => void this.manager.playMedia(artist);

      grid.appendChild(card);
    });

    container.appendChild(grid);
  }

  private renderPlaylistsGrid(container: HTMLElement, playlists: MAPlaylist[]) {
    container.innerHTML = '';
    if (playlists.length === 0) {
      container.innerHTML = '<p style="color:#aeb4be;">No playlists found in library.</p>';
      return;
    }

    const grid = document.createElement('div');
    grid.className = 'ma-media-grid';

    playlists.forEach((playlist) => {
      const card = document.createElement('div');
      card.className = 'ma-media-card';

      const img = document.createElement('img');
      img.className = 'ma-card-art';
      const imgUrl = this.manager.client.getImageUrl(playlist);
      if (imgUrl) img.src = imgUrl;

      const title = document.createElement('p');
      title.className = 'ma-card-title';
      title.textContent = playlist.name;

      const sub = document.createElement('p');
      sub.className = 'ma-card-subtitle';
      sub.textContent = playlist.owner || 'Playlist';

      card.appendChild(img);
      card.appendChild(title);
      card.appendChild(sub);

      card.onclick = () => void this.manager.playMedia(playlist);

      grid.appendChild(card);
    });

    container.appendChild(grid);
  }

  private renderTracksList(container: HTMLElement, tracks: MATrack[]) {
    container.innerHTML = '';
    if (tracks.length === 0) {
      container.innerHTML = '<p style="color:#aeb4be;">No tracks found in library.</p>';
      return;
    }

    const list = document.createElement('div');
    list.className = 'ma-track-list';

    tracks.forEach((track, idx) => {
      const row = document.createElement('div');
      row.className = 'ma-track-row';

      const num = document.createElement('span');
      num.style.color = '#aeb4be';
      num.textContent = `${idx + 1}`;

      const info = document.createElement('div');
      info.style.minWidth = '0';
      const name = document.createElement('div');
      name.style.fontWeight = '600';
      name.style.color = '#f7fafc';
      name.textContent = track.name;
      const sub = document.createElement('div');
      sub.style.fontSize = '0.8rem';
      sub.style.color = '#aeb4be';
      sub.textContent = track.artists?.map((a) => a.name).join(', ') || track.album?.name || '';
      info.appendChild(name);
      if (sub.textContent) info.appendChild(sub);

      const playBtn = document.createElement('button');
      playBtn.className = 'player-pill-btn';
      playBtn.textContent = 'Play';
      playBtn.onclick = (e) => {
        e.stopPropagation();
        void this.manager.playMedia(track);
      };

      const enqueueBtn = document.createElement('button');
      enqueueBtn.className = 'player-pill-btn';
      enqueueBtn.style.background = 'transparent';
      enqueueBtn.style.color = '#a4c9fe';
      enqueueBtn.textContent = '+ Queue';
      enqueueBtn.onclick = (e) => {
        e.stopPropagation();
        void this.manager.enqueueMedia(track);
      };

      row.appendChild(num);
      row.appendChild(info);
      row.appendChild(playBtn);
      row.appendChild(enqueueBtn);

      list.appendChild(row);
    });

    container.appendChild(list);
  }
}
