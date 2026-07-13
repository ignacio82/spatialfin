import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {defineConfig, loadEnv} from 'vite';
import { VitePWA } from 'vite-plugin-pwa';

const NOTO_SANS_JP_PATH = resolve(
  process.cwd(),
  'node_modules/@fontsource/noto-sans-jp/files/noto-sans-jp-japanese-400-normal.woff2',
);

function subtitleFallbackFontPlugin() {
  const route = '/web/libass/noto-sans-jp.woff2';
  const middleware = () => (
    request: {url?: string},
    response: {setHeader(name: string, value: string): void; end(body: Buffer): void},
    next: () => void,
  ) => {
    if (request.url?.split('?')[0] !== route) {
      next();
      return;
    }
    response.setHeader('Content-Type', 'font/woff2');
    response.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
    response.end(readFileSync(NOTO_SANS_JP_PATH));
  };
  return {
    name: 'spatialfin-subtitle-fallback-font',
    configureServer(server: {middlewares: {use(handler: ReturnType<typeof middleware>): void}}) {
      server.middlewares.use(middleware());
    },
    configurePreviewServer(server: {middlewares: {use(handler: ReturnType<typeof middleware>): void}}) {
      server.middlewares.use(middleware());
    },
    buildStart() {
      this.emitFile({
        type: 'asset',
        fileName: 'libass/noto-sans-jp.woff2',
        source: readFileSync(NOTO_SANS_JP_PATH),
      });
    },
  };
}

function readBoolean(value: string | undefined, name: string): boolean {
  if (value === undefined || value === '' || value === 'false') {
    return false;
  }
  if (value === 'true') {
    return true;
  }
  throw new Error(`${name} must be either "true" or "false".`);
}

export default defineConfig(({mode}) => {
  const environment = loadEnv(mode, process.cwd(), '');
  const target = environment.JELLYFIN_PROXY_TARGET?.replace(/\/$/, '');
  const allowSelfSigned = readBoolean(
    environment.JELLYFIN_PROXY_ALLOW_SELF_SIGNED,
    'JELLYFIN_PROXY_ALLOW_SELF_SIGNED',
  );
  const certificatePath = environment.SPATIALFIN_HTTPS_CERT;
  const keyPath = environment.SPATIALFIN_HTTPS_KEY;

  if (Boolean(certificatePath) !== Boolean(keyPath)) {
    throw new Error(
      'SPATIALFIN_HTTPS_CERT and SPATIALFIN_HTTPS_KEY must be set together.',
    );
  }

  const https = certificatePath && keyPath
    ? {
        cert: readFileSync(resolve(certificatePath)),
        key: readFileSync(resolve(keyPath)),
      }
    : undefined;
  const proxy = target
    ? {
        '/jellyfin-proxy': {
          target,
          changeOrigin: true,
          secure: !allowSelfSigned,
          ws: true,
          rewrite: (path: string) => path.replace(/^\/jellyfin-proxy/, ''),
        },
      }
    : undefined;

  return {
    base: '/web/',
    plugins: [
      subtitleFallbackFontPlugin(),
      VitePWA({
        registerType: 'autoUpdate',
        workbox: {
          maximumFileSizeToCacheInBytes: 10 * 1024 * 1024,
          // The libass fallback font is needed just as early as its worker and
          // WASM binary. Workbox's default extension set does not include
          // fonts, which made offline/PWA subtitle startup fail.
          globPatterns: ['**/*.{js,css,html,png,svg,webp,woff,woff2,ttf,otf,wasm,webmanifest}']
        },
        manifest: {
          name: 'SpatialFin WebXR',
          short_name: 'SpatialFin',
          description: 'SpatialFin WebXR Client',
          theme_color: '#000000',
          background_color: '#000000',
          display: 'standalone',
          icons: [
            {
              src: 'app-icon.png',
              sizes: '512x512',
              type: 'image/png',
              purpose: 'any maskable'
            }
          ]
        }
      })
    ],
    build: {
      outDir: '../docs/web',
      emptyOutDir: true,
    },
    server: {https, proxy},
    preview: {https, proxy},
  };
});
