// Synchronous QuickJS plugin example for SpatialFin (no async/await or fetch).
let baseUrl = 'https://peertube.example';

function normalizeBaseUrl(rawUrl) {
  const trimmed = String(rawUrl || '').replace(/\/+$/, '');
  if (!trimmed.startsWith('https://')) {
    throw new ScriptException('PeerTube instanceUrl must use https://');
  }
  return trimmed;
}

function getJson(path) {
  const resp = http.GET(baseUrl + path, { Accept: 'application/json' });
  if (!resp || !resp.isOk || !resp.body) {
    throw new ScriptException('PeerTube HTTP request failed: ' + (resp ? resp.code : 'no response'));
  }
  return JSON.parse(resp.body);
}

function toAbsoluteUrl(maybeRelative) {
  if (!maybeRelative) return '';
  if (maybeRelative.startsWith('http://') || maybeRelative.startsWith('https://')) {
    return maybeRelative;
  }
  return baseUrl + (maybeRelative.startsWith('/') ? maybeRelative : '/' + maybeRelative);
}

function mapVideo(video) {
  const id = String(video.shortUUID || video.uuid || video.id || '');
  const watchUrl = toAbsoluteUrl(video.url || ('/w/' + id));
  const thumbPath = video.thumbnailPath || video.previewPath || '';
  const thumbUrl = thumbPath ? toAbsoluteUrl(thumbPath) : '';
  const authorName =
    (video.channel && (video.channel.displayName || video.channel.name)) ||
    (video.account && (video.account.displayName || video.account.name)) ||
    'PeerTube';

  return new PlatformVideo({
    id: new PlatformID('peertube', id, 'peertube-example'),
    name: String(video.name || 'Untitled'),
    description: String(video.description || ''),
    url: watchUrl,
    duration: Number(video.duration || 0),
    viewCount: Number(video.views || 0),
    isLive: Boolean(video.isLive),
    author: new PlatformAuthorLink({
      id: new PlatformID('peertube', authorName, 'peertube-example'),
      name: authorName,
      url: watchUrl,
    }),
    thumbnails: thumbUrl ? new Thumbnails([new Thumbnail(thumbUrl, 0, 0)]) : new Thumbnails([]),
  });
}

function extractVideoId(videoUrl) {
  const clean = String(videoUrl || '').split('?')[0].split('#')[0].replace(/\/+$/, '');
  const watchMatch = clean.match(/\/(?:w|videos\/watch)\/([^/]+)$/);
  if (watchMatch) return watchMatch[1];
  const parts = clean.split('/');
  return parts[parts.length - 1] || '';
}

source.enable = function (config, settings, savedState) {
  const configured = (settings && settings.instanceUrl) || 'https://peertube.example';
  baseUrl = normalizeBaseUrl(configured);
};

source.getHome = function (row) {
  const payload = getJson('/api/v1/videos?count=20&sort=-publishedAt');
  const rows = Array.isArray(payload.data) ? payload.data.map(mapVideo) : [];
  return new VideoPager(rows, false);
};

source.search = function (query) {
  const encoded = encodeURIComponent(String(query || ''));
  const payload = getJson('/api/v1/search/videos?search=' + encoded + '&count=20');
  const rows = Array.isArray(payload.data) ? payload.data.map(mapVideo) : [];
  return new VideoPager(rows, false);
};

source.resolveVideoUrl = function (videoUrl) {
  const videoId = extractVideoId(videoUrl);
  if (!videoId) {
    throw new ScriptException('Could not extract PeerTube video ID from URL');
  }
  const details = getJson('/api/v1/videos/' + encodeURIComponent(videoId));

  let streamUrl = '';
  let mimeType = 'application/x-mpegURL';

  if (Array.isArray(details.streamingPlaylists) && details.streamingPlaylists.length > 0) {
    const hls = details.streamingPlaylists[0];
    if (hls && hls.playlistUrl) {
      streamUrl = toAbsoluteUrl(hls.playlistUrl);
      mimeType = 'application/x-mpegURL';
    }
  }

  if (!streamUrl && Array.isArray(details.files) && details.files.length > 0) {
    const file = details.files[0];
    if (file && file.fileUrl) {
      streamUrl = toAbsoluteUrl(file.fileUrl);
      mimeType = 'video/mp4';
    }
  }

  if (!streamUrl || streamUrl === videoUrl) {
    throw new UnavailableException('No direct playable PeerTube stream URL found');
  }

  return {
    url: streamUrl,
    mimeType: mimeType,
  };
};
