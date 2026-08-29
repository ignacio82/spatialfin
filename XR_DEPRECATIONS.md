<!-- Split out of GEMINI.md, which is capped at ~700 lines. GEMINI.md links here
     from its "Jetpack XR deprecations" pointer and from the XR user-agency
     guidance; keep both working if this file moves. -->

# Jetpack XR deprecations

On the newest published Jetpack XR libs — `scenecore`/`arcore`/`runtime` `1.0.0-beta02`, `compose` `1.0.0-alpha17` (Google Maven, 2026-08-29). The beta renamed APIs but kept the old names as deprecated aliases, so **the build stays green while sitting on APIs that vanish at 1.0.0 stable**. Ground truth is `./gradlew :player:xr:compileDebugKotlin :app:unified:compileLibreDebugKotlin --rerun-tasks` grepped for `^w:`, listed **unfiltered** — release notes describe renames as if breaking, the constant pool proves nothing (old *and* new classes carry a `kotlin/Deprecated` string), and a keyword-filtered grep silently undercounts (it hid `requestFullSpaceMode` and the `Session.create` overload). **The two Orbiter deprecation notices point at each other — do not follow either blindly.** `Orbiter(anchorPoint = …)` says use the SpatialAlignment/OrbiterEdgeOffsetType function; the `Orbiter(position = ContentEdge.…)` overload says use anchorPoint or a poseProvider. `ContentEdge` is *backwards*. The one live API is `Orbiter(alignment = OrbiterAlignment.…)`, whose nested types (`TopCenter`, `CenterStart`, `CenterEnd`, `TopEnd`, `BottomCenter`, …) each take `edgeOffsetType` + `DpVolumeOffset`, so the old offset carries across verbatim.

Migrated 2026-08-29 — every androidx.xr deprecation in `:player:xr` and `:app:unified` is clear except the one below:

| Was | Now |
|---|---|
| `Scene.requestHomeSpaceMode()` / `requestFullSpaceMode()` | `requestHomeSpace()` / `requestFullSpace()` |
| `SpatialPanel(resizePolicy = ResizePolicy())` | `SubspaceModifier.resizable()` |
| `AnchorEntity` (14 sites) | `AnchorSpace` — now extends `SpaceEntity`; reparenting is unchanged |
| `AnchorEntity.create(…, PlaneOrientation, PlaneSemanticType, …)` | `AnchorSpace.create(…, setOf(…), setOf(…), …)` |
| `Orbiter(anchorPoint = …, offset = DpVolumeOffset(…))` (9 sites) | `Orbiter(alignment = OrbiterAlignment.X(offset = DpVolumeOffset(…)))` |
| `SubspaceModifier.transformingMovable()` | `SubspaceModifier.movable()` |

Two equivalences were *proved* by diffing the `$default` synthetics rather than assumed: `ResizePolicy()` ≡ `resizable()`, and `transformingMovable()` ≡ `movable()` (both resolve to `MovePolicy.system(scaleWithDistance = true)`, no-op `onMove`). Do **not** "fix" the latter to `MovePolicy.custom()` — *custom* means the app applies the move itself, not the system.

**Deliberately NOT migrated: `Session.create(activity)` → the suspend overload** (3 sites: `UnifiedMainActivity`, `XrPlayerActivity`, `XrFCastInboundPlayerActivity`). All three create the session synchronously in `onCreate()` on purpose — per the comment at `UnifiedMainActivity.onCreate`, creating it from a coroutine lets a config change destroy the prior Activity first, the lifecycle observer misses `ON_DESTROY`, and the leaked session locks the next XR launch into a degraded state. The suspend overload reintroduces that race. Revisit only with a Galaxy XR in hand.
<!-- updated 2026-08-29: full beta02 deprecation sweep; only the Session.create lifecycle change deferred -->
