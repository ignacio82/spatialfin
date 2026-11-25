#!/usr/bin/env bash
# End-to-end release preflight, synced website verification, 3-AAB build, and
# certificate parity verifier for SpatialFin (libre, tv, wear).
set -euo pipefail

SITE_ONLY=false
CHECK_ONLY=false
ALLOW_DEBUG_SIGNING=false
SKIP_SITE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --site-only)
      SITE_ONLY=true
      shift
      ;;
    --check-only)
      CHECK_ONLY=true
      shift
      ;;
    --allow-debug-signing)
      ALLOW_DEBUG_SIGNING=true
      shift
      ;;
    --skip-site)
      SKIP_SITE=true
      shift
      ;;
    -h|--help)
      cat <<'USAGE'
Usage: .github/scripts/release-bundles.sh [options]

Options:
  --site-only            Run Stages 2-3 only (git diff summary + site build/verify).
  --check-only           Run Stages 1-3 only (preflight + diff + site verify, no AAB build).
  --allow-debug-signing  Do not require release keystore or reject CN=Android Debug certs.
  --skip-site            Skip Stage 3 (website build & verify). Mutually exclusive with --site-only.
USAGE
      exit 0
      ;;
    *)
      echo "ERROR: Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "$SITE_ONLY" == "true" && "$SKIP_SITE" == "true" ]]; then
  echo "ERROR: --site-only and --skip-site are mutually exclusive." >&2
  exit 2
fi

cd "$(dirname "$0")/../.."

VERSIONS_FILE="buildSrc/src/main/kotlin/Versions.kt"
LIBS_TOML="gradle/libs.versions.toml"
SETTINGS_GRADLE="settings.gradle.kts"

# Stage 1: Preflight (tag monotonicity + R8 version parity)
if [[ "$SITE_ONLY" != "true" ]]; then
  echo "==> Stage 1: Release preflight"
  bash .github/scripts/tag-release.sh --check-only

  toml_r8="$(grep -oP '^r8\s*=\s*"\K[^"]+' "$LIBS_TOML" | head -n1 || true)"
  settings_r8="$(grep -oP 'com\.android\.tools:r8:\K[^")]+' "$SETTINGS_GRADLE" | head -n1 || true)"
  if [[ -z "$toml_r8" || -z "$settings_r8" || "$toml_r8" != "$settings_r8" ]]; then
    echo "ERROR: R8 version mismatch between ${LIBS_TOML} (${toml_r8:-missing}) and ${SETTINGS_GRADLE} (${settings_r8:-missing})." >&2
    exit 1
  fi
  echo "R8 version parity verified: ${toml_r8}"
fi

# Stage 2: User-facing diff surface since last v* tag
echo "==> Stage 2: Commits since last release tag"
LAST_TAG="$(git tag -l 'v*' --sort=-v:refname | head -n1 || true)"
if [[ -n "$LAST_TAG" ]]; then
  echo "Commits since ${LAST_TAG}:"
  git log "${LAST_TAG}..HEAD" --oneline || true
else
  echo "No previous v* tag found; showing recent 20 commits:"
  git log -n 20 --oneline || true
fi

# Stage 3: Website build & offline verification
if [[ "$SKIP_SITE" != "true" ]]; then
  echo "==> Stage 3: Website build and offline verification"
  node site/build.mjs
  node site/verify.mjs
fi

if [[ "$SITE_ONLY" == "true" || "$CHECK_ONLY" == "true" ]]; then
  echo "Preflight/website check completed (--site-only=${SITE_ONLY}, --check-only=${CHECK_ONLY})."
  exit 0
fi

# Stage 4: Serialized 3-AAB release build
echo "==> Stage 4: Building libre, tv, and wear Play bundles"
if [[ "$ALLOW_DEBUG_SIGNING" == "true" ]]; then
  bash .github/scripts/build-release-bundles.sh
else
  bash .github/scripts/build-release-bundles.sh -PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true
fi

# Stage 5: Certificate parity verification across all three AABs
echo "==> Stage 5: Verifying signing certificate parity across AABs"
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/keytool" ]]; then
  KEYTOOL="${JAVA_HOME}/bin/keytool"
elif command -v keytool >/dev/null 2>&1; then
  KEYTOOL="$(command -v keytool)"
else
  echo "ERROR: JDK keytool not found on JAVA_HOME or PATH." >&2
  exit 1
fi

AAB_PATHS=(
  "app/unified/build/outputs/bundle/libreRelease/spatialfin-libre-release.aab"
  "app/unified/build/outputs/bundle/tvRelease/spatialfin-tv-release.aab"
  "companion/wear/build/outputs/bundle/release/wear-release.aab"
)

expected_fp=""
for aab in "${AAB_PATHS[@]}"; do
  if [[ ! -f "$aab" ]]; then
    echo "ERROR: Expected release bundle missing: ${aab}" >&2
    exit 1
  fi
  cert_out="$(LC_ALL=C "$KEYTOOL" -printcert -jarfile "$aab")"
  fp="$(sed -nE 's/^[[:space:]]*SHA256:[[:space:]]*([0-9A-Fa-f:]+).*/\1/p' <<< "$cert_out" | head -n1)"
  owner="$(sed -nE 's/^[[:space:]]*Owner:[[:space:]]*(.*)/\1/p' <<< "$cert_out" | head -n1)"
  if [[ -z "$fp" ]]; then
    echo "ERROR: Could not extract SHA256 certificate fingerprint from ${aab}" >&2
    exit 1
  fi
  if [[ "$ALLOW_DEBUG_SIGNING" != "true" ]] && grep -q 'CN=Android Debug' <<< "$owner"; then
    echo "ERROR: ${aab} is signed with CN=Android Debug; release keystore required unless --allow-debug-signing is set." >&2
    exit 1
  fi
  if [[ -z "$expected_fp" ]]; then
    expected_fp="$fp"
  elif [[ "$fp" != "$expected_fp" ]]; then
    echo "ERROR: Signing certificate mismatch on ${aab} (${fp} != ${expected_fp})." >&2
    exit 1
  fi
  echo "Verified ${aab}: Owner=[${owner}] SHA256=${fp}"
done

# Stage 6: Release Summary & Manual Tag Handoff
echo "==> Stage 6: Release summary"
APP_NAME="$(grep -oP 'APP_NAME = "\K[^"]+' "$VERSIONS_FILE")"
APP_CODE="$(grep -oP 'APP_CODE = \K[0-9]+' "$VERSIONS_FILE")"
TV_CODE=$((APP_CODE + 1000000))
WEAR_CODE=$((APP_CODE + 2000000))

echo "Version: v${APP_NAME} (libre=${APP_CODE}, tv=${TV_CODE}, wear=${WEAR_CODE})"
echo "Signing SHA256: ${expected_fp}"
for aab in "${AAB_PATHS[@]}"; do
  bytes="$(wc -c < "$aab")"
  digest="$(sha256sum "$aab" | awk '{print $1}')"
  echo "  - ${aab} (${bytes} bytes, sha256=${digest})"
done
echo ""
echo "Next steps (manual — nothing was committed or tagged automatically):"
echo "  1. Review git status and commit any release/doc updates."
echo "  2. Create release tag: bash .github/scripts/tag-release.sh"
echo "  3. Push branch + tag: git push origin main --follow-tags"
