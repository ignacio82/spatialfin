#!/usr/bin/env bash
# Tags the current commit as a release, and refuses if the version has already
# been used.
#
# Release tagging had lapsed at v2.7.29 while the app was at 2.7.55, which is why
# "has Play already taken this version code?" was unanswerable and 153 was burned
# on a collision. A tag per release makes the answer checkable.
#
# Creates the tag locally and prints the push command rather than pushing:
# publishing a ref is the one step worth doing deliberately.
set -euo pipefail

cd "$(dirname "$0")/../.."
VERSIONS="buildSrc/src/main/kotlin/Versions.kt"

NAME="$(grep -oP 'APP_NAME = "\K[^"]+' "$VERSIONS")"
CODE="$(grep -oP 'APP_CODE = \K[0-9]+' "$VERSIONS")"
TAG="v${NAME}"

echo "Versions.kt: ${NAME} (${CODE})"

if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
  echo "ERROR: ${TAG} already exists locally. Bump the version before releasing." >&2
  exit 1
fi
if git ls-remote --exit-code --tags origin "refs/tags/${TAG}" >/dev/null 2>&1; then
  echo "ERROR: ${TAG} already exists on origin. Bump the version before releasing." >&2
  exit 1
fi

# The highest code we have ever tagged. A new release must exceed it; equal or
# lower means the bundle would be rejected on upload.
HIGHEST=0
for t in $(git tag -l 'v*'); do
  c="$(git show "${t}:${VERSIONS}" 2>/dev/null | grep -oP 'APP_CODE = \K[0-9]+' || true)"
  [[ -n "${c:-}" && "$c" -gt "$HIGHEST" ]] && HIGHEST="$c"
done

if [[ "$HIGHEST" -gt 0 && "$CODE" -le "$HIGHEST" ]]; then
  echo "ERROR: version code ${CODE} is not above the highest tagged code ${HIGHEST}." >&2
  echo "       Set APP_CODE to $((HIGHEST + 1)) or higher." >&2
  exit 1
fi

git tag -a "$TAG" -m "SpatialFin ${NAME} (${CODE}) — libre ${CODE}, tv $((CODE + 1000000))"
echo ""
echo "Tagged ${TAG} at $(git rev-parse --short HEAD)."
echo "Highest previously tagged code: ${HIGHEST:-none}"
echo ""
echo "Publish it with:"
echo "  git push origin ${TAG}"
