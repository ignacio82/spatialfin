# Music Assistant Remote Access (WebRTC) — Implementation Plan

**Status:** Phases 1 & 2 implemented AND validated on-device (Pixel 10 Pro XL, MA 2.9.0rc7, 2026-06-19). Phase 3 (hardening) partially done (network-type auto-switch). 
**Author:** research spike, 2026-06-19

> **On-device validation (2026-06-19):** over WebRTC, the `ma-api` `server_info`→`auth`
> handshake authenticated, `players/all` returned the player list, and the `sendspin` audio
> handshake completed (`server hello` + `server/state` track title) — full pipeline confirmed
> end-to-end on a real MA server. **~10–13 s** from app launch to remote audio.
> Fixes made during testing: (1) **network-type switch** — `isOnLocalNetwork()` chooses LAN on
> Wi-Fi/Ethernet and WebRTC on cellular, because MA 2.9+ rejects the LAN `/sendspin` *outbound*
> dial with `4001 first message must be auth`, making dial-failure useless as a reachability
> probe; (2) 4 s dial connect timeout so off-LAN dials fail fast; (3) fixed `ensureAdvertising`
> being skipped when a Remote ID was set (LAN inbound now works at home regardless).
> Still pending: hard-NAT TURN relay, and `ordered=false` tuning on lossy cellular.

> **Phase 2 landed** — remote MA *control* over the `ma-api` data channel. `MaApiChannelClient`
> (`receiver/remote/`) speaks MA's WS JSON-RPC (server_info-gated `auth`, `message_id`
> correlation, `partial` batch merge) and exposes a blocking `MaRpcTransport`.
> `MusicAssistantGroupClient` gained a pluggable `rpcTransport`; the service points it at the
> channel when up and relaxes the REST URL/token gates so player-list / set_members / play_media
> work off-LAN (`login` + `/info` discovery stay REST/LAN-only). Reuses the per-user MA token for
> channel auth. `:sendspin` compiles clean. Still **not run against a live MA server** — §8.

> **Phase 1 landed in `:sendspin`** — `receiver/remote/` (`RemoteId`, `SignalingMessage`,
> `MaSignalingClient`, `MaWebRtcConnection`, `SendspinLoopbackRelay`), WebRTC topology in
> `SendspinReceiverService`, scoped `remote_id` prefs + `SET_REMOTE_ID` intent, and the shared
> Remote ID field in `MusicAssistantAuthFields` (XR/Beam/onboarding). `stream-webrtc-android`
> `1.3.10` added. Loopback-relay approach (§2 variant A) — `sendspin-jvm` unforked. Both
> `:sendspin` and `:app:unified` compile clean. **Not yet run against a live MA server** — see
> the on-device validation points in §8.
**Goal:** Let SpatialFin's SendSpin receiver reach a Music Assistant (MA) server when the
device is *off* the home LAN, the same way the official MA mobile app does — by tunnelling
the SendSpin protocol over a WebRTC data channel brokered by MA's cloud signaling server.

This plan is grounded in three codebases read during the spike:

- **SpatialFin** `:sendspin` (`SendspinReceiverService`, `sendspin-jvm` usage).
- **`OnFreund/sendspin-jvm` v0.2.3** — the protocol library SpatialFin depends on.
- **`music-assistant/mobile-app`** — the official Compose-Multiplatform app, whose
  `webrtc/` package and `.claude/*.md` design notes are the reference implementation.

---

## 1. How the official app does it (reference architecture)

Off-LAN, the official client tunnels SendSpin over WebRTC. Connection brokering:

1. Client opens a WebSocket to the **MA cloud signaling server**
   `wss://signaling.music-assistant.io/ws` (free, no Nabu Casa account required).
2. Client sends `{"type":"connect-request","remoteId":"<26-char>"}`.
   The **Remote ID** is a 26-char Base32 string derived from the server's DTLS cert
   fingerprint, shown in **MA → Settings → Remote Access** (server-side toggle must be ON).
3. Server replies `connected` with a `sessionId` and a list of **ICE servers**
   (STUN always; **TURN** relay creds when Nabu Casa is configured — needed only for
   double-NAT / symmetric-NAT / strict carrier networks).
4. Client builds an `RTCPeerConnection`, creates two data channels (below), generates an
   **SDP offer**, sends it via signaling (`type:"offer"`); gateway returns `answer`.
5. **Trickle ICE**: both sides exchange `ice-candidate` messages through the signaling
   socket until the P2P (or TURN-relayed) path is live. DTLS gives end-to-end encryption.
6. Signaling socket then only carries keepalive `ping`/`pong` (client must answer pings).

**Two data channels** (created before the offer so they're in the SDP):

| Label | Purpose | Delivery | Frame type |
|---|---|---|---|
| `ma-api` | MA WebSocket JSON-RPC API (all control) | `ordered=true, maxRetransmits=-1` (reliable/TCP-like) | TEXT |
| `sendspin` | SendSpin audio + protocol | `ordered=true` in current code¹ | TEXT (protocol JSON) + BINARY (audio chunks) |

¹ The completion doc says they shipped `ordered=false, maxRetransmits=0` (UDP-like) to fix
cellular audio stalls, but the **current** `WebRTCConnectionManager.kt` has the sendspin
channel back on `ordered=true`. Treat the reliable-vs-unreliable trade-off as a tuning knob
to validate on-device (see §7), not a settled value.

**Auth nuance:** authentication is performed once at connection level over `ma-api`. The
`sendspin` channel **skips per-channel auth** — it sends `client/hello` directly. (The
official app sets `requiresAuth=false` for the WebRTC sendspin transport.)

**Library:** the official app abstracts platforms behind its own `PeerConnectionWrapper` /
`DataChannelWrapper`; on Android the concrete impl is Google's native `org.webrtc.*`
(historically via `com.shepeliev:webrtc-kmp`, recently migrated to `io.ktor:ktor-client-webrtc`).
A hard-won gotcha: the KMP wrappers send everything as BINARY frames, but the MA gateway's
Python handler needs **TEXT** frames for protocol JSON — they drop to the native
`org.webrtc.DataChannel.send(Buffer(buf, binary=false))` API for text.

### Signaling message shapes (Moshi-portable)

```
connect-request  C→S  { type, remoteId }
connected        S→C  { type, sessionId, remoteId, iceServers:[{urls,username,credential}] }
offer            C→S  { type, remoteId, sessionId, data:{sdp,type} }
answer           S→C  { type, sessionId, data:{sdp,type} }
ice-candidate    C↔S  { type, remoteId?, sessionId, data:{candidate,sdpMid,sdpMLineIndex} }
error            S→C  { type, error, sessionId? }
peer-disconnected S→C { type, sessionId? }
ping / pong      S↔C  { type }
```

Note `iceServers[].urls` is **sometimes a string, sometimes an array** (public server vs
Nabu Casa) — needs a lenient list deserializer.

---

## 2. Inserting the WebRTC transport **without forking** `sendspin-jvm`

SpatialFin uses `com.github.OnFreund:sendspin-jvm:v0.2.3`, which **is not transport-pluggable**:
`SendSpinClient.connect(wsUrl)` → `doConnect()` hardwires `okHttpClient.newWebSocket(...)`
(`SendSpinClient.kt:207,272-279`), and the inbound feed methods `handleTextMessage()` /
`handleBinaryMessage()` are **`internal`**. There is no public seam to hand it a WebRTC channel.

**Important framing:** the official app does *not* avoid a fork by being clever — it **never
uses this library**. It ships its *own* SendSpin client built from day one around a
`SendspinTransport` interface. So "do what the official app does" literally means "reimplement
the SendSpin protocol," which is *more* work than forking, not less.

The right move is neither forking nor reimplementing: **insert the WebRTC transport underneath
the stock library, at the socket boundary**, leaving `sendspin-jvm` an unmodified dependency.
The client never knows it isn't talking to a real WebSocket server. Two viable variants:

- **A. Loopback WebSocket relay (recommended — zero internal-API risk).** Stand up a tiny
  in-process `org.java_websocket` server on `127.0.0.1:<ephemeral>`. Call the stock
  `SendSpinClient.connect("ws://127.0.0.1:<port>")`. A relay forwards every frame both ways
  between that loopback connection and the WebRTC `sendspin` data channel:
  - `SendSpinClient` → loopback WS → `DataChannel.send(Buffer(buf, binary=false))` (text)
    / `binary=true` (rare; client is text-only outbound)
  - `DataChannel.onMessage(text|binary)` → loopback WS frame → `SendSpinClient`'s normal
    `WebSocketListener.onMessage` → `handleTextMessage` / `handleBinaryMessage`
  - **`:sendspin` already depends on `org.java-websocket` directly** (`build.gradle.kts:26`)
    and already binds a Java-WebSocket server via `SendSpinServerHost` — so this is proven,
    in-module, **no new dependency**, and rides entirely on *public* API of both libraries.
    Survives `sendspin-jvm` and OkHttp upgrades. Cost: one in-memory localhost hop (µs-scale,
    negligible at audio bitrate). Bind to `127.0.0.1` only — never externally reachable.

- **B. Fake `okhttp3.WebSocket` (leaner, small internal-API dependency).** `SendSpinClient`
  takes an injected `OkHttpClient` and calls `okHttpClient.newWebSocket(...)`. Subclass
  `OkHttpClient` (public no-arg ctor) and override `newWebSocket` to return an
  `okhttp3.WebSocket` implementation backed by the data channel; drive the client's listener
  from channel callbacks (defer `onOpen` until the channel opens, since the client assigns
  `activeOkHttpWs` *after* `newWebSocket` returns and guards callbacks on identity). No extra
  socket. Risk: depends on `OkHttpClient.newWebSocket` remaining overridable — pin the OkHttp
  version and cover with a test.

**Recommendation: variant A.** Forking/vendoring `sendspin-jvm` (add a public
`connectViaTransport`/`feedText`/`feedBinary` seam) remains a *fallback* only if a relay edge
case forces it — but the spike found no such blocker, so it should not be needed.

---

## 3. WebRTC library choice for SpatialFin

SpatialFin is **Android-only Kotlin** (not KMP), so skip the KMP/Ktor wrappers and use the
prebuilt Google libwebrtc directly:

- **`io.getstream:stream-webrtc-android`** — the maintained `org.webrtc.*` prebuilt (what
  webrtc-kmp wraps on Android). Direct access to `PeerConnectionFactory`, `PeerConnection`,
  `DataChannel`, and crucially `DataChannel.Buffer(data, binary=false)` for the TEXT-frame
  requirement.
- Ships one native `.so` per ABI (`libjingle_peerconnection_so.so`), ~2–4 MB/ABI.
  - ABI splits are already configured for APKs and disabled for bundles — no new build wiring.
  - **Verify 16 KB page-size alignment** of the chosen version for Play bundles (same hard
    requirement we satisfy for `libass_jni.so`). Recent stream-webrtc-android releases are
    aligned; pin a known-good version in `libs.versions.toml`.
  - Release is `isMinifyEnabled = false`, so no R8 keep rules needed now; if minify ever
    returns, add `-keep class org.webrtc.** { *; }`.

---

## 4. New components (all in `:sendspin`, JVM-testable where possible)

```
sendspin/.../remote/
  RemoteId.kt                 — parse/validate 26-char Base32 (strip hyphens/space, upper)
  SignalingMessage.kt         — Moshi sealed types from §1 + lenient urls deserializer
  MaSignalingClient.kt        — OkHttp WebSocket to wss://signaling.music-assistant.io/ws;
                                exposes connect/sendMessage + Flow<SignalingMessage>;
                                auto-answers ping; reconnect/backoff
  MaWebRtcConnection.kt       — owns PeerConnectionFactory + PeerConnection; creates ma-api
                                + sendspin channels; offer/answer + trickle ICE through the
                                signaling client; exposes the two DataChannels
  SendspinLoopbackRelay.kt    — §2 variant A: org.java_websocket server on 127.0.0.1:<eph>;
                                bridges the stock SendSpinClient's loopback connection ↔ the
                                `sendspin` DataChannel (text↔text binary=false, binary↔binary).
                                Stock SendSpinClient.connect("ws://127.0.0.1:<port>") — no
                                library fork. Generously-buffered inbound channel so WebRTC
                                native callbacks never block (see §8 backpressure).
```

**Native PeerConnectionFactory** must be initialized once per process
(`PeerConnectionFactory.initialize(...)`) — do it lazily in the receiver service, guarded,
off the main thread.

---

## 5. Integration into `SendspinReceiverService`

The service already runs a **topology loop** that re-resolves the dial URL every tick and
chooses *outbound dial* vs *legacy advertise* (`SendspinReceiverService.kt` ~180-300, dial
loop ~440-500). Add a **third topology: WebRTC remote**.

- **Mode selection policy** (mirror official app's "local → WebSocket, else WebRTC"):
  1. If a LAN dial URL resolves and connects → **direct WS** (unchanged, fastest).
  2. Else if a **Remote ID** is configured → **WebRTC remote** via signaling.
  3. Else → legacy advertise (unchanged).
  Fall back direct→WebRTC when the direct dial fails for N seconds while a Remote ID exists.
- **Client construction:** reuse the *exact, unmodified* `SendSpinClient` built at `:222-253`.
  In WebRTC mode, start `SendspinLoopbackRelay` (bridged to `maWebRtc.sendspinChannel`) and
  call the stock `client.connect("ws://127.0.0.1:<relayPort>")` instead of the LAN URL — no
  library fork (§2 variant A). Keep `reconnectEnabled = false`: the service owns reconnection
  and the WebRTC/peer layer drives transport recovery, not the SendSpin client.
- **No `SendSpinServerHost` / port bind** in WebRTC mode (it's a pure outbound transport —
  same shape as the existing "outbound-only, host bind failed" branch at `:291-296`).
- **Stall watchdog / clock-recovery** (`recoverSendspinClockIfNeeded`, `audioStalled` UI)
  carries over unchanged — it operates on rendered-PCM health, transport-agnostic.

### Config / onboarding (intents + prefs)

- New pref key alongside the existing per-user MA keys (`u:<userId>/last_url`, token keys):
  `u:<userId>/remote_id` (the 26-char Remote ID).
- New intent `ACTION_MUSIC_ASSISTANT_SET_REMOTE_ID` next to the existing
  `ACTION_MUSIC_ASSISTANT_SET_SERVER_URL` family (`:1873`, `:693`, `:2002`).
- Optional toggle `remote_access_enabled` so users on metered links can opt out.

---

## 6. UX — all three form factors (mandatory: XR + Beam + TV in the same PR)

Per the project rule that player/cast features must be plumbed through every form factor in
one PR, the Remote ID entry + enable toggle must land on phone (Beam/Pixel), Galaxy XR Home
Space, and TV together. Surface area is tiny — one text field + one switch — so build **one
shared composable** (`MaRemoteAccessSetting`) and host it in each form factor's MA/SendSpin
settings panel, exactly as `RemoteControlMiniPlayerHost()` is reused across form factors.

- Validate input live with `RemoteId.parse` (accept hyphenated display form).
- Show connection state (Signaling… / Connecting P2P… / Remote / TURN-relayed) reusing the
  existing `SendspinReceiverUiState` surface; reuse the "Reconnecting…" affordance.
- Help text: "Enable Remote Access in Music Assistant → Settings → Remote Access and paste
  the Remote ID here." Note Nabu Casa is optional (only for hard NAT).

---

## 7. Phasing

**Phase 1 — Remote audio receive (headline feature).**
Signaling + peer connection + `sendspin` data channel + loopback relay → device registers as
a SendSpin player and **receives audio off-LAN**. "What to play on this device" is driven by
MA's own UI or any other controller. Ships the user's literal request. Deliverables: §2
loopback relay (no fork), §3 lib, §4 components, §5 WebRTC topology, §6 settings.

**Phase 2 — Remote control parity (`ma-api` channel).**
SpatialFin's in-app MA control (`MusicAssistantGroupClient`) currently uses MA **REST over
HTTP**, which can't reach the server off-LAN. The `ma-api` data channel carries MA's
**WebSocket JSON-RPC** API, not REST — so full remote control means porting those calls onto
the `ma-api` channel (or MA's WS API generally). Larger, separable surface; do it after Phase
1 proves the transport.

**Phase 3 — hardening.** Auto local↔remote detection, connection-quality indicator,
TURN-relay surfacing, battery/background tuning, optional QR for Remote ID.

---

## 8. Risks & open questions

- **No library fork needed:** the §2 loopback relay rides only on public API, so there's no
  `sendspin-jvm` upstream/vendoring dependency on the critical path. Watch one edge: the relay
  must cleanly tear down and re-accept on WebRTC reconnect so the stock client's own
  socket-replacement logic doesn't fight it (it expects a normal WS reconnect).
- **Reliable vs unreliable sendspin channel:** official docs and current code disagree. Must
  validate on real cellular (the app's #8 production blocker was audio stalls from
  `ordered=true` over lossy links). Make it a config constant and A/B on-device.
- **SharedFlow backpressure:** the official app had to raise its audio-chunk buffer to ~2000
  (~40 s) to stop WebRTC native callbacks blocking. Our relay's inbound pump must not block the
  `org.webrtc` callback thread — hand frames to a generously-buffered channel before the
  loopback write.
- **Reconnection/job lifecycle:** the official app fixed *ten* race conditions here
  (self-cancelling reconnection, overlapping attempts, monitor-job leaks). Budget time;
  reuse their patterns (cache connection info; relaunch reconnection in the service scope, not
  a child of the monitor job; "already connected? bail" guard after each backoff).
- **Native init cost on Galaxy XR:** `PeerConnectionFactory.initialize` + the `.so` load adds
  startup/memory cost. Keep it lazy and off the hot path (we already defer LLM/GPU warmup at
  launch for the same reason).
- **Server prerequisites (document, don't build):** MA "Remote Access" toggle ON; Nabu Casa
  only for complex NAT. Public signaling server is free.

---

## 9. Effort estimate

| Item | Rough size |
|---|---|
| Loopback relay bridging stock SendSpinClient ↔ data channel (no fork) | S |
| Signaling client + message types + RemoteId | S–M |
| PeerConnection + data channels + relay wiring | M |
| Service topology integration + mode selection | M |
| Settings UI ×3 form factors (shared composable) | S |
| On-device tuning (reliability, backpressure, reconnection) | M–L |
| **Phase 1 total** | **~multi-day, one real hardware tuning pass** |
| Phase 2 (`ma-api` → port MusicAssistantGroupClient to MA WS) | separate M–L |

---

## 10. Reference files (in the cloned repos during the spike)

- `mobile-app/.claude/sendspin-transport-architecture.md` — the `SendspinTransport` seam.
- `mobile-app/.claude/webrtc-completion-summary.md` — signaling URL, channel configs, the
  10 reconnection bugs, TEXT-vs-BINARY gotcha.
- `mobile-app/composeApp/src/commonMain/.../webrtc/model/{SignalingMessage,RemoteId}.kt`
- `mobile-app/composeApp/src/commonMain/.../webrtc/{SignalingClient,WebRTCConnectionManager}.kt`
- `sendspin-jvm/sendspin-protocol/src/main/kotlin/com/sendspin/protocol/{SendSpinClient,SendSpinWebSocket}.kt`
- SpatialFin `sendspin/.../receiver/SendspinReceiverService.kt` (client build + topology loop).
