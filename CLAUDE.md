# CLAUDE.md

This file is auto-loaded into Claude Code at the start of every session in this
repository. Keep it short; the real technical context lives in `GEMINI.md`.

## Read GEMINI.md first

`GEMINI.md` is the canonical AI context for SpatialFin — architecture, XR/JNI
pitfalls, voice/AI pipeline, flavor/signing/versioning conventions, known gaps.
**Read it with the Read tool before you do anything non-trivial.** For a tiny
edit (one-line string change, typo fix) you can skip it, but anything that
touches a build file, manifest, player, voice subsystem, XR composable, or the
preference layer needs that context or you will regress something subtle.

## Keep GEMINI.md in sync

GEMINI.md has a self-update mandate in its opening section: when any claim in
it becomes wrong (module renamed, version bumped, flavor added, build quirk
fixed, architectural decision changed), fix the affected line in the **same
commit** as the code change. Do not append errata sections. Stay under ~700
lines.

## Project quick reference

- Primary form factor: Android XR (Samsung Galaxy XR). Secondary: phone (Beam
  Pro) and TV. All ship from `:app:unified` with a runtime `DeviceClass` branch.
- Two Play Store bundles: `libre` (phone / XR / Beam Pro) and `tv` (Play TV
  track). Never unify them — see the "Play Track Bundles" section of GEMINI.md
  for the hard rule.
- Current version in `buildSrc/src/main/kotlin/Versions.kt` — re-read it every
  session rather than trusting any cached value in GEMINI.md.

## Local session artifacts

`.claude/` holds Claude Code config. The per-user bits (`settings.local.json`,
`*.lock`) are gitignored; never commit them. A shared `.claude/settings.json`
or `.claude/commands/*.md` *is* committable if we ever add one.
