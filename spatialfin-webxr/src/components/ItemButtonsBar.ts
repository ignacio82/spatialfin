import {
  getDownloadUrl,
  refreshItemMetadata,
  toggleFavorite,
  toggleItemPlayed,
  updateItemExternalIds,
  type JellyfinItem,
} from '../api';
import { offlineMediaRepository } from '../OfflineMediaRepository';

export interface ItemButtonsBarOptions {
  item: JellyfinItem;
  versions?: JellyfinItem[];
  onPlay: (item: JellyfinItem, startFromBeginning?: boolean) => void;
  onShowDetails?: () => void;
  onVersionChange?: (selectedVersion: JellyfinItem) => void;
  onMetadataUpdated?: () => void;
}

export function createItemButtonsBar(options: ItemButtonsBarOptions): HTMLElement {
  const { item, versions = [] } = options;

  let currentItem = item;
  let isPlayed = Boolean(item.UserData?.Played);
  let isFavorite = Boolean(item.UserData?.IsFavorite);
  const positionTicks = item.UserData?.PlaybackPositionTicks ?? 0;
  const hasResume = positionTicks > 0;

  const container = document.createElement('div');
  container.className = 'browser-hero-actions';

  // 1. Play Button
  const playBtn = document.createElement('button');
  playBtn.className = 'primary-action hero-play-btn';
  playBtn.type = 'button';
  playBtn.textContent = '▶ Play in browser';
  playBtn.onclick = () => options.onPlay(currentItem, false);
  container.appendChild(playBtn);

  // 2. Restart Button (if resume position exists)
  if (hasResume) {
    const restartBtn = document.createElement('button');
    restartBtn.className = 'secondary-action hero-restart-btn';
    restartBtn.type = 'button';
    restartBtn.textContent = '↻ Restart';
    restartBtn.onclick = () => options.onPlay(currentItem, true);
    container.appendChild(restartBtn);
  }

  // 3. Watched Toggle Button
  const watchedBtn = document.createElement('button');
  watchedBtn.className = `secondary-action hero-watched-action ${isPlayed ? 'hero-action-active' : ''}`;
  watchedBtn.type = 'button';
  watchedBtn.textContent = isPlayed ? '✓ Watched' : '○ Watched';
  watchedBtn.onclick = async () => {
    watchedBtn.disabled = true;
    try {
      await toggleItemPlayed(currentItem.Id, !isPlayed);
      isPlayed = !isPlayed;
      if (!currentItem.UserData) currentItem.UserData = {};
      currentItem.UserData.Played = isPlayed;
      watchedBtn.textContent = isPlayed ? '✓ Watched' : '○ Watched';
      watchedBtn.classList.toggle('hero-action-active', isPlayed);
    } catch (e) {
      console.error('Failed to toggle watched state:', e);
    } finally {
      watchedBtn.disabled = false;
    }
  };
  container.appendChild(watchedBtn);

  // 5. Favorite Toggle Button
  const favBtn = document.createElement('button');
  favBtn.className = `secondary-action hero-favorite-action ${isFavorite ? 'hero-action-active' : ''}`;
  favBtn.type = 'button';
  favBtn.textContent = isFavorite ? '♥ Favorite' : '♡ Favorite';
  favBtn.onclick = async () => {
    favBtn.disabled = true;
    try {
      const updated = await toggleFavorite(currentItem.Id, !isFavorite);
      isFavorite = Boolean(updated.UserData?.IsFavorite);
      currentItem.UserData = updated.UserData;
      favBtn.textContent = isFavorite ? '♥ Favorite' : '♡ Favorite';
      favBtn.classList.toggle('hero-action-active', isFavorite);
    } catch (e) {
      console.error('Failed to toggle favorite:', e);
    } finally {
      favBtn.disabled = false;
    }
  };
  container.appendChild(favBtn);

  // 6. Cast Button
  const castBtn = document.createElement('button');
  castBtn.className = 'secondary-action hero-cast-btn';
  castBtn.type = 'button';
  castBtn.title = 'Cast to device';
  castBtn.textContent = '📡 Cast';
  castBtn.onclick = () => {
    const browserCastBtn = document.querySelector<HTMLButtonElement>('#browser-cast-button');
    if (browserCastBtn) {
      browserCastBtn.click();
    }
  };
  container.appendChild(castBtn);

  // 7. Overflow Menu Wrapper (3-dots ...)
  const overflowWrapper = document.createElement('div');
  overflowWrapper.className = 'hero-overflow-wrapper';

  const overflowToggle = document.createElement('button');
  overflowToggle.className = 'secondary-action hero-overflow-toggle-btn';
  overflowToggle.type = 'button';
  overflowToggle.textContent = '⋮ More';

  const dropdown = document.createElement('div');
  dropdown.className = 'hero-overflow-dropdown';
  dropdown.hidden = true;

  overflowToggle.onclick = (e) => {
    e.stopPropagation();
    dropdown.hidden = !dropdown.hidden;
    if (!dropdown.hidden) {
      const firstOpt = dropdown.querySelector<HTMLElement>('button');
      firstOpt?.focus();
    }
  };

  document.addEventListener('click', () => {
    dropdown.hidden = true;
  });

  // Overflow item: Trailer
  const trailerUrl = item.RemoteTrailers?.[0]?.Url;
  if (trailerUrl) {
    const trailerBtn = document.createElement('button');
    trailerBtn.className = 'overflow-item hero-trailer-btn';
    trailerBtn.type = 'button';
    trailerBtn.textContent = '🎬 Watch Trailer';
    trailerBtn.onclick = () => {
      dropdown.hidden = true;
      window.open(trailerUrl, '_blank', 'noopener');
    };
    dropdown.appendChild(trailerBtn);
  }

  // Overflow item: SyncPlay
  const syncplayBtn = document.createElement('button');
  syncplayBtn.className = 'overflow-item hero-syncplay-btn';
  syncplayBtn.type = 'button';
  syncplayBtn.textContent = '📺 SyncPlay Room';
  syncplayBtn.onclick = () => {
    dropdown.hidden = true;
    const playerSyncBtn = document.querySelector<HTMLButtonElement>('#browser-player-syncplay-btn');
    if (playerSyncBtn) {
      playerSyncBtn.click();
    } else {
      alert('Start playback or open player to manage SyncPlay sessions.');
    }
  };
  dropdown.appendChild(syncplayBtn);

  // Overflow item: Version (if multiple versions)
  if (versions.length > 1) {
    const versionBtn = document.createElement('button');
    versionBtn.className = 'overflow-item hero-version-btn';
    versionBtn.type = 'button';
    versionBtn.textContent = `🗄 Select Version (${versions.length})`;
    versionBtn.onclick = () => {
      dropdown.hidden = true;
      showVersionDialog(versions, currentItem, (selected) => {
        currentItem = selected;
        if (options.onVersionChange) options.onVersionChange(selected);
      });
    };
    dropdown.appendChild(versionBtn);
  }

  // Overflow item: Quality / Max Bitrate
  const qualityBtn = document.createElement('button');
  qualityBtn.className = 'overflow-item hero-quality-btn';
  qualityBtn.type = 'button';
  qualityBtn.textContent = '✨ Playback Quality';
  qualityBtn.onclick = () => {
    dropdown.hidden = true;
    showQualityDialog();
  };
  dropdown.appendChild(qualityBtn);

  // Overflow item: Download / Offline Storage
  const downloadBtn = document.createElement('button');
  downloadBtn.className = 'overflow-item hero-download-btn';
  downloadBtn.type = 'button';

  const updateDownloadState = async () => {
    const isDownloaded = await offlineMediaRepository.isItemDownloaded(currentItem.Id);
    if (isDownloaded) {
      downloadBtn.textContent = '🗑 Delete Offline Copy';
    } else {
      downloadBtn.textContent = '⬇ Download for Offline Playback';
    }
  };
  void updateDownloadState();

  downloadBtn.onclick = async () => {
    dropdown.hidden = true;
    const isDownloaded = await offlineMediaRepository.isItemDownloaded(currentItem.Id);
    if (isDownloaded) {
      if (confirm(`Remove offline copy of "${currentItem.Name}"?`)) {
        await offlineMediaRepository.deleteDownloadedItem(currentItem.Id);
        alert(`Deleted offline copy of "${currentItem.Name}".`);
        void updateDownloadState();
      }
    } else {
      downloadBtn.disabled = true;
      downloadBtn.textContent = '⏳ Downloading (0%)…';
      try {
        const downloadUrl = getDownloadUrl(currentItem.Id);
        const resp = await fetch(downloadUrl);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);

        await offlineMediaRepository.saveMediaFileWithProgress(currentItem.Id, resp, (percent) => {
          downloadBtn.textContent = `⏳ Downloading (${percent}%)…`;
        });
        await offlineMediaRepository.saveItemMetadata(currentItem);
        alert(`"${currentItem.Name}" downloaded successfully for offline playback!`);
      } catch (err) {
        alert(`Download failed: ${err}`);
      } finally {
        downloadBtn.disabled = false;
        void updateDownloadState();
      }
    }
  };
  dropdown.appendChild(downloadBtn);

  // Overflow item: Edit External IDs
  const editIdsBtn = document.createElement('button');
  editIdsBtn.className = 'overflow-item hero-edit-ids-btn';
  editIdsBtn.type = 'button';
  editIdsBtn.textContent = '✏ Edit external IDs';
  editIdsBtn.onclick = () => {
    dropdown.hidden = true;
    showEditExternalIdsDialog(currentItem, () => {
      if (options.onMetadataUpdated) options.onMetadataUpdated();
    });
  };
  dropdown.appendChild(editIdsBtn);

  // Overflow item: Refresh Metadata
  const refreshBtn = document.createElement('button');
  refreshBtn.className = 'overflow-item hero-refresh-metadata-btn';
  refreshBtn.type = 'button';
  refreshBtn.textContent = '🔄 Refresh Metadata';
  refreshBtn.onclick = async () => {
    dropdown.hidden = true;
    refreshBtn.disabled = true;
    try {
      await refreshItemMetadata(currentItem.Id);
      alert('Metadata refresh requested from Jellyfin server.');
      if (options.onMetadataUpdated) options.onMetadataUpdated();
    } catch (e) {
      alert(`Failed to refresh metadata: ${e}`);
    } finally {
      refreshBtn.disabled = false;
    }
  };
  dropdown.appendChild(refreshBtn);

  overflowWrapper.appendChild(overflowToggle);
  overflowWrapper.appendChild(dropdown);
  container.appendChild(overflowWrapper);

  return container;
}

function showVersionDialog(
  versions: JellyfinItem[],
  current: JellyfinItem,
  onSelect: (item: JellyfinItem) => void
) {
  const dialog = document.querySelector<HTMLDialogElement>('#version-selection-dialog');
  const list = document.querySelector<HTMLElement>('#version-options-list');
  const cancelBtn = document.querySelector<HTMLButtonElement>('#version-cancel-btn');

  if (!dialog || !list) return;

  list.innerHTML = '';
  versions.forEach((v) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `connect-button ${v.Id === current.Id ? 'hero-action-active' : ''}`;
    btn.style.marginTop = '8px';
    btn.textContent = `${v.Name} ${v.MediaSources?.[0]?.VideoCodec ? `(${v.MediaSources[0].VideoCodec.toUpperCase()})` : ''}`;
    btn.onclick = () => {
      onSelect(v);
      dialog.close();
    };
    list.appendChild(btn);
  });

  if (cancelBtn) {
    cancelBtn.onclick = () => dialog.close();
  }

  dialog.showModal();
}

function showQualityDialog() {
  const dialog = document.querySelector<HTMLDialogElement>('#quality-selection-dialog');
  const list = document.querySelector<HTMLElement>('#quality-options-list');
  const cancelBtn = document.querySelector<HTMLButtonElement>('#quality-cancel-btn');

  if (!dialog || !list) return;

  const currentBitrate = parseInt(localStorage.getItem('spatialfin_max_bitrate') || '0', 10);

  const buttons = list.querySelectorAll<HTMLButtonElement>('button[data-bitrate]');
  buttons.forEach((btn) => {
    const val = parseInt(btn.dataset.bitrate || '0', 10);
    btn.classList.toggle('hero-action-active', val === currentBitrate);
    btn.onclick = () => {
      localStorage.setItem('spatialfin_max_bitrate', val.toString());
      dialog.close();
    };
  });

  if (cancelBtn) {
    cancelBtn.onclick = () => dialog.close();
  }

  dialog.showModal();
}

function showEditExternalIdsDialog(item: JellyfinItem, onSaved: () => void) {
  const dialog = document.querySelector<HTMLDialogElement>('#edit-external-ids-dialog');
  const form = document.querySelector<HTMLFormElement>('#edit-external-ids-form');
  const imdbInput = document.querySelector<HTMLInputElement>('#ext-imdb-id');
  const tmdbInput = document.querySelector<HTMLInputElement>('#ext-tmdb-id');
  const tvdbInput = document.querySelector<HTMLInputElement>('#ext-tvdb-id');
  const cancelBtn = document.querySelector<HTMLButtonElement>('#edit-ext-ids-cancel');

  if (!dialog || !form) return;

  const providerIds = item.ProviderIds || {};
  if (imdbInput) imdbInput.value = providerIds.Imdb || providerIds.imdb || '';
  if (tmdbInput) tmdbInput.value = providerIds.Tmdb || providerIds.tmdb || '';
  if (tvdbInput) tvdbInput.value = providerIds.Tvdb || providerIds.tvdb || '';

  if (cancelBtn) {
    cancelBtn.onclick = () => dialog.close();
  }

  form.onsubmit = async (e) => {
    e.preventDefault();
    const imdb = imdbInput?.value.trim() || '';
    const tmdb = tmdbInput?.value.trim() || '';
    const tvdb = tvdbInput?.value.trim() || '';

    try {
      await updateItemExternalIds(item.Id, {
        Imdb: imdb,
        Tmdb: tmdb,
        Tvdb: tvdb,
      });
      alert('External IDs updated successfully!');
      dialog.close();
      onSaved();
    } catch (err) {
      alert(`Failed to save external IDs: ${err}`);
    }
  };

  dialog.showModal();
}
