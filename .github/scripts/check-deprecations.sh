#!/usr/bin/env bash
# Fails when a *new kind* of deprecated API is introduced.
#
# 39 Jetpack XR deprecations accumulated unnoticed until someone happened to read
# a release announcement, because a green build says nothing about deprecated
# APIs. This is the same shape as the committed Compose stability baseline: a
# checked-in list of what we already tolerate, and a hard failure on anything new.
#
# It compares the *set of distinct deprecation messages*, not a count, so it is
# immune to line-number churn and to a call site being duplicated — and when it
# does fail it names the API rather than just a number. The message is kept
# verbatim: an earlier version stripped parenthesised text to "normalise"
# signatures and mangled every message that contained parentheses.
#
# Regenerate after an intentional change:  ./.github/scripts/check-deprecations.sh --update
set -uo pipefail

cd "$(dirname "$0")/../.."
BASELINE=".github/deprecation-baseline.txt"
ACTUAL="$(mktemp)"
trap 'rm -f "$ACTUAL"' EXIT

TASKS=(
  ":player:xr:compileDebugKotlin"
  ":app:unified:compileLibreDebugKotlin"
  ":modes:film:compileDebugKotlin"
  ":shell:beam:compileDebugKotlin"
  ":shell:tv:compileDebugKotlin"
  ":core:ui:compileDebugKotlin"
  ":settings:compileDebugKotlin"
)

echo "Collecting deprecation warnings..."
# --rerun-tasks: an up-to-date compile emits no warnings at all, which would
# otherwise read as "zero deprecations" and pass vacuously.
./gradlew "${TASKS[@]}" --rerun-tasks 2>&1 \
  | grep "^w:" \
  | grep "is deprecated" \
  | sed -E 's/^w: file:[^ ]+ //' \
  | LC_ALL=C sort -u > "$ACTUAL"

if [[ "${1:-}" == "--update" ]]; then
  cp "$ACTUAL" "$BASELINE"
  echo "Baseline updated: $(wc -l < "$BASELINE") distinct deprecations."
  exit 0
fi

if [[ ! -f "$BASELINE" ]]; then
  echo "No baseline at $BASELINE. Create one with: $0 --update" >&2
  exit 1
fi

# Only *additions* fail. Removing a deprecation is always welcome and must never
# break the build for whoever did the cleanup.
NEW="$(comm -13 <(LC_ALL=C sort -u "$BASELINE") "$ACTUAL")"
if [[ -n "$NEW" ]]; then
  echo "" >&2
  echo "New deprecated API usage introduced:" >&2
  echo "$NEW" | sed 's/^/  + /' >&2
  echo "" >&2
  echo "Migrate it, or accept it deliberately with:" >&2
  echo "  ./.github/scripts/check-deprecations.sh --update" >&2
  exit 1
fi

GONE="$(comm -23 <(LC_ALL=C sort -u "$BASELINE") "$ACTUAL" | wc -l)"
echo "OK — no new deprecations ($(wc -l < "$ACTUAL") known, $GONE cleared since the baseline)."
