# SpatialFin WebXR

SpatialFin WebXR is a browser-based Jellyfin client built on XRBlocks 0.17 and
Three.js. It presents the user's Jellyfin libraries, posters, item details, and
playback controls as movable `xb.SpatialPanel` surfaces.

## Run on desktop

Requirements: Node.js 20.19 or newer and a browser with WebGL 2.

```bash
npm install
npm run dev -- --host 0.0.0.0
```

Open the URL printed by Vite. Desktop browsers use the XRBlocks simulator. On a
headset, the page must be delivered from a secure context before immersive WebXR
will be available.

Use the Jellyfin base URL in the connection form, including a configured base
path when applicable:

```text
https://jellyfin.example.com
https://media.example.com/jellyfin
```

Copied Jellyfin `/web` and `/web/index.html` URLs are normalized automatically.

## Run on a LAN headset

An HTTPS WebXR page cannot fetch `http://192.168.x.x:8096` directly. For local
development, give Vite a certificate valid for the hostname or IP address used
by the headset and proxy Jellyfin through the app's own origin.

The following example uses
[mkcert](https://github.com/FiloSottile/mkcert). Replace the hostname and IP with
values that resolve to the development computer from the headset:

```bash
mkdir -p .certs
mkcert -cert-file .certs/spatialfin.pem \
  -key-file .certs/spatialfin-key.pem \
  localhost 127.0.0.1 ::1 spatialfin.local 192.168.1.20
```

Install mkcert's root CA on the headset as a trusted CA before opening the app.
`mkcert -CAROOT` prints the directory containing `rootCA.pem`. A certificate
warning is not sufficient: the browser must trust the issuer for WebXR to see a
secure context.

Start Vite with that certificate and the private Jellyfin address:

```bash
SPATIALFIN_HTTPS_CERT="$PWD/.certs/spatialfin.pem" \
SPATIALFIN_HTTPS_KEY="$PWD/.certs/spatialfin-key.pem" \
JELLYFIN_PROXY_TARGET="http://192.168.1.5:8096" \
npm run dev -- --host 0.0.0.0
```

Open `https://192.168.1.20:5173` on the headset and enter
`/jellyfin-proxy` as the server URL in SpatialFin. The browser then talks to the
Vite origin over HTTPS, and Vite forwards API, image, playback, and WebSocket
requests to Jellyfin without cross-origin or mixed-content requests.

If `JELLYFIN_PROXY_TARGET` is itself HTTPS, its certificate is verified by
default. For an isolated development server with a self-signed certificate,
prefer its private HTTP port or install its CA on the development computer. The
last-resort opt-out is explicit:

```bash
SPATIALFIN_HTTPS_CERT="$PWD/.certs/spatialfin.pem" \
SPATIALFIN_HTTPS_KEY="$PWD/.certs/spatialfin-key.pem" \
JELLYFIN_PROXY_TARGET="https://192.168.1.5:8920" \
JELLYFIN_PROXY_ALLOW_SELF_SIGNED=true \
npm run dev -- --host 0.0.0.0
```

Do not use `JELLYFIN_PROXY_ALLOW_SELF_SIGNED=true` on an untrusted network or in
a deployment.

## Deploy with Caddy

For a durable installation, build the static app and let Caddy serve it over
HTTPS while proxying Jellyfin at the same `/jellyfin-proxy` path:

```bash
npm run build
SPATIALFIN_SITE_ADDRESS="spatialfin.example.com" \
SPATIALFIN_WEB_ROOT="$PWD/dist" \
JELLYFIN_UPSTREAM="http://192.168.1.5:8096" \
caddy run --config deploy/Caddyfile.example
```

The deployable output is `dist/`. Point the hostname's DNS at the Caddy machine
and allow TCP ports 80 and 443 so Caddy can obtain a publicly trusted
certificate. Then open `https://spatialfin.example.com` and enter
`/jellyfin-proxy` in the connection form. Caddy preserves the Jellyfin
authorization header and handles WebSocket upgrades automatically.

For LAN-only DNS, change the Caddy site address to a hostname resolvable on the
LAN and add `tls internal` inside the site block. Install Caddy's root CA on the
headset; `caddy trust` installs it on the Caddy host only. A public certificate
is preferable when the headset cannot be configured to trust a private CA.

The example assumes Jellyfin is mounted at `/`. If Jellyfin has a configured
base URL such as `/jellyfin`, replace `uri strip_prefix /jellyfin-proxy` with
`uri replace /jellyfin-proxy /jellyfin` in the Caddyfile. Configure the Caddy
machine as a Known Proxy in Jellyfin's Networking settings so forwarded client
addresses and remote-access rules are interpreted correctly.

HTTPS Jellyfin upstream certificates are verified by Caddy by default. The
Caddyfile includes a commented private-CA example. Use a trusted private CA (or
the private HTTP port on a protected LAN) instead of disabling verification.
See Jellyfin's
[reverse-proxy guidance](https://jellyfin.org/docs/general/post-install/networking/reverse-proxy/)
and [Caddy example](https://jellyfin.org/docs/general/post-install/networking/reverse-proxy/caddy/).

If the app and Jellyfin use different HTTPS origins, add the app's exact origin
to Jellyfin's CORS hosts. Browser requests use the standard `Authorization:
MediaBrowser` header, so cross-origin requests will preflight.

## Verify

```bash
npm run verify
```

This builds the production bundle and runs Puppeteer against a mocked Jellyfin
server using the real XRBlocks renderer. The check covers the Android XR-sized
home panel, navigation rail, server/header actions, three-across backdrop hero
cards, Continue Watching / Next Up shelves, detail and episode navigation,
flat-player geometry, the bottom transport plus stage/track/session orbiters,
180°/360° recentering, and the controller select-start/update/end path for
fixed-depth video movement. It also verifies Jellyfin home/playback requests,
desktop/mobile login layout, cleanup, and rendered-pixel output.

## XRBlocks UI choice

This app intentionally uses XRBlocks' core `SpatialPanel` grid API. Core panels
need `options.enableUI()` and the XRBlocks-owned animation loop; they do not need
`options.uikit.enable(...)`. The `@pmndrs/uikit` initialization is only for the
separate XRBlocks `uiblocks` addon, and the two panel systems should not be mixed
inside the same surface.

After changing spatial UI code, leave the immersive session and hard-refresh the
page in the headset (or close and reopen the tab). Vite HMR does not reliably
dispose and reconstruct an already-active immersive WebXR scene.
