# Fastlane

This directory contains the Google Play release automation for SpatialFin.

Current scope:
- Build the signed `libreRelease` bundle from `:app:unified`
- Upload the bundle, text metadata, and changelogs to Google Play

Intentionally excluded for now:
- Image and screenshot sync

The old legacy store art was removed from the repo during the SpatialFin
migration. Add new SpatialFin-specific Play Store assets before re-enabling image
uploads in `fastlane/Fastfile`.

Required environment variables:
- `SPATIALFIN_KEYSTORE`
- `SPATIALFIN_KEYSTORE_PASSWORD`
- `SPATIALFIN_KEY_ALIAS`
- `SPATIALFIN_KEY_PASSWORD`
- `SPATIALFIN_PLAY_API_CREDENTIALS`

Usage:

```sh
bundle exec fastlane android publish
```
