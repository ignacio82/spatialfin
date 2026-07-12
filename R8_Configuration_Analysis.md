# R8 Configuration Analysis

Analysis date: 2026-07-12

## Configuration

- `:app:unified` release enables code minification and resource shrinking for both
  shipped flavors. The release-derived staging build uses the same optimization path.
- The app uses `proguard-android-optimize.txt` plus its local rules.
- Android Gradle Plugin 9.2.1 provides R8 full mode and optimized resource shrinking by
  default. The build pins R8 9.3.19 and enables its keep-annotation API. No property disables
  full mode.
- Library modules intentionally publish unminified bytecode. The application R8 task
  performs whole-program optimization over that bytecode.
- Jetpack XR uses its documented compile-only `extensions-xr:1.3.0` API so R8 can
  analyze the device-provided XR boundary.

## Keep-rule actions

### `app/unified/proguard-rules.pro:5`

`-keep class com.android.extensions.xr.** { *; }`

Action: remove or replace after optimized full-space transition and XR player coverage. Cold
startup now passes, but the rule targets device-provided library types rather than the SceneCore
callback implementations that cross that boundary, so it does not protect those implementations
from optimization. If a remaining XR path needs protection, derive a rule from that concrete
device failure.

### `app/unified/proguard-rules.pro:6`

`-keep interface com.android.extensions.xr.** { *; }`

Action: remove. The preceding `class` pattern already matches interfaces, making this rule
subsumed as well as package-wide.

### `data/proguard-rules.pro:4-24`

The conditional companion, serializer, and serializable-object rules duplicate the rules
embedded in `kotlinx-serialization-core` 1.11.0.

Action: remove the duplicated blocks and rely on the library's consumer rules. Because
`:data` exports this file, its `class **` conditions currently apply to the entire app.

### `data/proguard-rules.pro:27`

`-keepattributes RuntimeVisibleAnnotations,AnnotationDefault`

Action: remove the duplicate. Kotlin serialization already supplies its required attribute
retention through its consumer configuration.

### `data/proguard-rules.pro:42`

`-keepnames class dev.jdtech.jellyfin.models.CollectionType`

Action: remove. Keeping only the class name does not preserve enum entry names.
Beam navigation now persists the stable `CollectionType.type` wire value and restores it with
`fromString`, so navigation state no longer depends on enum names retained by R8.

### Java-WebSocket timer setup

The Galaxy XR verifier rejected `WebSocketServer.run` when R8 inlined
`AbstractWebSocket.startConnectionLostTimer` and split its monitor catch-all range. A source-level
R8 keep annotation applies only the `NEVER_INLINE` constraint to that one method. The method and
its declaring class remain eligible for shrinking and renaming; no package-wide rule is needed.

## Reflection-sensitive code

- The former NFS integration reflected into four private nfs4j members and reached desktop
  APIs unavailable on Android. It now uses an Android-owned client built from public NFS/RPC
  operations and an explicit AUTH_UNIX wire credential.
- The Sendspin receiver formerly reflected into `SendSpinServerHost.activeConn` and twelve
  private `ClockSync` fields. It now enumerates public server connections and rebuilds the
  client to reset clock state through its supported lifecycle.
- The WebRTC signaling wire models used reflection-based Moshi adapters. Their small wire
  shapes are now encoded and decoded directly so the schema does not depend on retained
  Kotlin metadata or field names.

## Verification

- `:app:unified:assembleLibreStaging` completed with R8 9.3.19 on 2026-07-12 and
  produced both ABI APKs plus mapping, usage, configuration, seeds, and resource-shrinker
  reports.
- The first run exposed absent XR and desktop API surfaces before artifact packaging. The XR
  analysis classpath and optional desktop dependency roots were corrected, and the subsequent
  optimized `libreStaging` build passed.
- The optimized arm64 APK is about 89.4 MB versus 179.1 MB for the prior unoptimized release,
  while retaining two DEX files instead of ten.
- The emitted `WebSocketServer.run` keeps a direct call to `startConnectionLostTimer`; the
  synchronized timer body is no longer inlined into the server loop.
- Three clean Galaxy XR cold launches, including the final staging artifact, remained alive,
  reached OpenXR `FOCUSED`, and executed the Sendspin server loop to its expected bind attempt
  without `VerifyError`, `AbstractMethodError`, or class-loading failure. The staging listener
  could not own its default port concurrently with the installed production app, so live Sendspin
  peer traffic was not part of this smoke test.
- CI now gates the two shipped optimized bundle tasks, `bundleLibreRelease` and
  `bundleTvRelease`, in separate memory-bounded Gradle invocations. Both exact release bundle
  tasks also completed locally with R8 9.3.19; their mappings were emitted, no
  `missing_rules.txt` was generated, and both signed AABs passed JAR signature verification.

## Runtime test focus

Use UI Automator on optimized builds, concentrating on:

- Galaxy XR cold startup, Home Space/full-space transitions, entity move/resize/hit-test
  callbacks, and XR player startup;
- serialized navigation restoration for collection and audio-detail routes;
- SMB and NFS browse/open/read paths;
- Sendspin connection discovery and clock realignment; and
- WebRTC offer, answer, ICE candidate, and reconnect signaling.
