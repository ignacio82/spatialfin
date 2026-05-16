# Split-A/V Sync — FCast protocol v4 extensions

Status: implemented 2026-05-16. Two SpatialFin-only, fully back-compatible
extensions that tighten split-A/V lipsync. Both are **optional bodies on
opcodes that are body-less in v2/v3**, so a v4 message with no body is
byte-identical to today's wire and any non-SpatialFin peer is unaffected.

`FCAST_PROTOCOL_VERSION` bumped 3 → 4. Negotiated version =
`min(ours, peer)`; a v4 body is only ever *emitted* when the negotiated
version ≥ 4 (i.e. the peer is a v4 SpatialFin build). Receivers always
*accept* an absent body (→ legacy behaviour) regardless of version.

## 1. NTP-style clock-offset (Ping/Pong)

Problem: `expectedXrPositionMs` assumed the beacon path delay equals `RTT/2`
(symmetric) and that "beacon received" ≈ "beacon generated". Wi-Fi is
asymmetric and beacons queue, so both assumptions inject error the
calibration step can only partly hide.

Fix: a standard NTP four-timestamp exchange over Ping/Pong.

```
PingMessage { t1: Long }                       // sender monotonic at Ping send
PongMessage { t1: Long, t2: Long, t3: Long }   // t1 echoed; t2,t3 = receiver monotonic
```

* `t1` — sender `SystemClock.elapsedRealtime()` when Ping is sent.
* `t2` — receiver `SystemClock.elapsedRealtime()` when Ping is read.
* `t3` — receiver `SystemClock.elapsedRealtime()` when Pong is written.
* `t4` — sender `SystemClock.elapsedRealtime()` when Pong is read.

Offset (receiver_clock ≈ sender_clock + θ) and round-trip delay:

```
θ = ((t2 − t1) + (t3 − t4)) / 2
δ = (t4 − t1) − (t3 − t2)
```

`ClockOffsetEstimator` keeps the θ from the **lowest-δ** sample in a sliding
window (NTP clock filter — the least-delayed round trip is the least biased),
rejecting δ < 0 / absurd outliers.

Beacons additionally carry the receiver's monotonic sample clock:

```
PlaybackUpdateMessage { …, monotonicSampleMs: Long? }   // SpatialFin extension
```

When θ and `monotonicSampleMs` are both available, the policy maps the beacon
precisely: the sample happened at sender time `monotonicSampleMs − θ`, so

```
audioBeingHeardNow = beaconStreamPos
                   + (senderNow − (monotonicSampleMs − θ))
                   − audioLatency
```

replacing the `+ RTT/2 − (now − received)` approximation. If θ or
`monotonicSampleMs` is missing (pre-v4 peer, θ not yet converged) the policy
falls back to the existing `networkOneWayMs` path unchanged.

## 2. Commanded synchronized resume (Resume)

Problem: split-A/V start is "receiver loads paused → master sends a blind
`Resume` when buffered". The two sides start whenever each finishes buffering,
producing a multi-second initial gap the 8 s warmup-grace papers over.

Fix: an optional body on `Resume`:

```
ResumeMessage { atReceiverMonotonicMs: Long }
```

When the master is buffered **and** θ has converged, the sender:

1. picks `T0 = senderNow + START_LEAD_MS` (small lead so both can prime),
2. schedules its own `resumeFromMaster()` at sender-time `T0`,
3. sends `Resume(atReceiverMonotonicMs = T0 + θ)`.

The receiver, instead of `exo.play()` immediately, schedules play for that
monotonic instant. Both render the same media position at the same wall
instant → near-zero initial drift; the drift loop engages immediately.

Safety / fallback (a flaky scheduled start must never be worse than today):

* θ not converged at first-play → send a **plain** `Resume` (today's path);
  warmup-grace still protects.
* `atReceiverMonotonicMs` already in the past, or further out than
  `MAX_SCHEDULED_WAIT_MS` → receiver plays immediately.
* Warmup-grace is **retained** as a backstop; with an aligned start the first
  beacon's drift is tiny so the policy simply Holds.

## Back-compat matrix

| Sender | Receiver | Behaviour |
|---|---|---|
| v4 | v4 | NTP θ + aligned resume |
| v4 | v2/v3 | negotiated=3 → bare Ping/Pong/Resume; RTT/2 + blind resume (today) |
| v2/v3 | v4 | receiver gets bodyless Ping/Resume → replies/acts legacy |
| any | non-SpatialFin | unaffected — bodyless opcodes, `ignoreUnknownKeys` |

## Touch points (all form factors, one PR)

* Protocol: `FCastProtocolVersion`, `FCastPayloads`, `FCastMessage`,
  `FCastFrame`.
* Receiver (shared by XR/Beam/TV): `FCastReceiverSession`,
  `FCastIngressRouter`, `FCastInboundPlayerActivity`.
* Sender: `FCastSenderClient`, `FCastCastingController`, plus
  `SplitAvController` / `SplitAvDrift` / `SplitAvPolicy` (XR video master;
  Beam uses the same controller path).
* Tests: `FCastFrameTest`, `FCastReceiverServerTest`, `SplitAvPolicyTest`,
  `SplitAvSyncReplayTest`.
