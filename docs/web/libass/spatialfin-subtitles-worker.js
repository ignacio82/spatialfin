/*
 * SpatialFin bootstrap for @jellyfin/libass-wasm 4.2.4.
 *
 * The upstream worker schedules every font through concurrent Emscripten
 * createPreloadedFile calls during preRun. Chromium can leave that dependency
 * group unresolved as soon as an MKV attachment is added, so the worker emits
 * its early `ready` event but never initializes libass or renders a frame.
 *
 * Workers may use synchronous I/O without blocking playback's main thread.
 * Load each font deterministically into Emscripten's /fonts directory before
 * libass initializes, preserving the upstream renderer and WASM unchanged.
 */
importScripts('./subtitles-octopus-worker.js');

const upstreamLoadFontFile = self.loadFontFile;

self.loadFontFile = function spatialFinLoadFontFile(fontId, path) {
  if (self.lazyFileLoading) {
    upstreamLoadFontFile(fontId, path);
    return;
  }

  try {
    const parsed = new URL(path, self.location.href);
    const fileName = parsed.pathname.split('/').pop() || 'font.bin';
    const bytes = readBinary(parsed.toString());
    Module.FS.writeFile(`/fonts/${fontId}${fileName}`, bytes);
  } catch (error) {
    // A missing attachment must not prevent the subtitle script or remaining
    // fonts from loading. Fontconfig/libass will fall back to the fonts that
    // did arrive successfully.
    console.warn(`SpatialFin could not preload subtitle font ${path}:`, error);
  }
};
