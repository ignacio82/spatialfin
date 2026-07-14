# FCast Web Implementation Plan

This document outlines the detailed architecture and step-by-step implementation plan for enabling Web Applications to act as FCast clients for SpatialFin, utilizing WebSockets for transport and the SpatialFin-Companion app for seamless discovery and authentication.

## 1. SpatialFin Modifications (FCast WebSocket Support)
Currently, `FCastReceiverServer` only accepts raw TCP connections. Browsers require WebSockets. We will multiplex WebSocket and raw TCP connections on the same port.

### 1.1 `FCastReceiverServer` Multiplexing
- **Detect HTTP Handshakes**: In `acceptLoop`, when a socket is accepted, read the first 4 bytes using a pushback input stream (`PushbackInputStream`).
- **Raw TCP vs WebSocket**:
  - If the bytes start with `GET `, it's a WebSocket handshake.
  - If the 4th byte is `0x00`, it's a standard FCast binary frame (Little-Endian size header).
- **WebSocket Upgrade**: If it's `GET `, process the standard HTTP `Upgrade: websocket` headers, compute the `Sec-WebSocket-Accept` hash, and send the HTTP 101 Switching Protocols response.
- **Session Routing**: Pass the upgraded socket to `FCastReceiverSession`. Modify the `FCastReceiverSession` and `FCastFrame` classes so that if the connection is a WebSocket, we do not require or expect the 4-byte size header before each JSON payload (because WebSockets already handle framing). 

### 1.2 Displaying the QR Code Fallback
- For users without the companion app, SpatialFin's UI (TV/XR) should provide an option to display a QR Code containing `fcast://<local-ip>:<port>`.

## 2. SpatialFin-Companion App Modifications
The companion app (`/home/ignacio/SpatialFin-Companion`) currently serves as a bridge for the Android app. It will be extended to act as an IP and Authentication broker for the Web App.

### 2.1 FCast Receiver Registry
- **Endpoint `POST /api/fcast/register`**: SpatialFin Android/XR clients will periodically call this to register their local IP addresses and FCast ports with the Companion App.
- **Endpoint `GET /api/fcast/receivers`**: The Web App will call this endpoint to retrieve a list of all active FCast receivers on the local network. The companion app can routinely prune stale entries.

### 2.2 Jellyfin Credential Brokering
- The Companion App already handles credentials for the Android app. We will expose this securely to the Web App so users do not have to manually log in if the Companion App is available.
- **Endpoint `GET /api/auth/credentials`**: The Web App will request Jellyfin server URLs and active auth tokens. 
- *Security Note*: Ensure this endpoint is restricted via CORS to the specific domains serving the Web App (or `localhost` for local dev) to prevent malicious sites on the network from stealing tokens.

## 3. Web App Implementation
The Web App will consume these APIs to provide a seamless "Android-like" experience in the browser.

### 3.1 Authentication Flow
1. **Companion App Check**: On startup, the Web App attempts to ping the Companion App at its default local address (e.g., `http://localhost:<companion-port>`).
2. **Auto-Login**: If the companion app is reachable, call `GET /api/auth/credentials`. If credentials exist, instantly log the user into the Jellyfin server.
3. **Manual Fallback**: If the companion app is unreachable or has no credentials, present the standard manual Jellyfin Server URL and Username/Password login form.

### 3.2 Discovery Flow
1. **Companion App Discovery**: The Web App calls `GET /api/fcast/receivers` on the Companion App to find all active SpatialFin instances on the network.
2. **QR Code Fallback**: Provide a "Scan QR Code" button. Using the HTML5 `MediaDevices.getUserMedia` API, open the device camera to scan the QR code displayed on the SpatialFin TV/XR screen to extract the IP and port.
3. **Manual Fallback**: Provide a text field for the user to manually enter the IP address of the SpatialFin device.

### 3.3 FCast WebSocket Client
- Create a Javascript `FCastClient` class.
- Connect using `new WebSocket("ws://<discovered-ip>:<port>")`.
- Since WebSockets have built-in message boundaries, the Web App will package messages as `[1-byte Opcode] + [UTF-8 JSON Body]` in an `ArrayBuffer` and send them as binary WebSocket frames, skipping the 4-byte size header that raw TCP uses.
- Listen for incoming binary frames and decode the opcode and JSON body to update the Web App's UI (e.g., syncing playback state).

## Summary of Execution Steps
1. Refactor `FCastReceiverServer.kt` to use `PushbackInputStream` and handle HTTP 101 WebSocket upgrades.
2. Update SpatialFin-Companion with `/api/fcast/register`, `/api/fcast/receivers`, and `/api/auth/credentials` endpoints.
3. Update SpatialFin Android to periodically call `/api/fcast/register`.
4. Build the Web App logic for auth/discovery fetching and WebSocket messaging.
