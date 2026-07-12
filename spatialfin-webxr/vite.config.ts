import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {defineConfig, loadEnv} from 'vite';
import { VitePWA } from 'vite-plugin-pwa';

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
      VitePWA({
        registerType: 'autoUpdate',
        workbox: {
          maximumFileSizeToCacheInBytes: 10 * 1024 * 1024
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
