# tools/

Developer-only helper scripts. None of these are part of the Gradle build or
CI — they are run by hand, from the repository root.

- `capture_screenshot.py` — pull a screenshot from a connected device/headset.
- `check_images.py`, `process_images.py`, `process_correct_icon.py` — store /
  launcher image validation and processing.
- `generate_store_banner.py`, `regen_store_icon.py`, `fix_tv_icon.py`,
  `fix_nano.py` — regenerate Play Store / launcher art.

These scripts assume they are invoked from the repo root (paths are
root-relative). Generated artifacts (e.g. `store_icon_512.png`) are gitignored.
