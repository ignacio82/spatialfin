#!/usr/bin/env bash
# Shared serialized builder for all three Google Play release AABs
# (libre, tv, wear). Invoked by both .github/workflows/ci.yml and
# .github/scripts/release-bundles.sh so R8 and JVM memory flags stay aligned.
set -euo pipefail

cd "$(dirname "$0")/../.."

export GRADLE_RETRY_ATTEMPTS="${GRADLE_RETRY_ATTEMPTS:-1}"

common_gradle_args=(
  --no-daemon
  --no-parallel
  --max-workers=1
  "-Dorg.gradle.jvmargs=-Xmx5120m -Dfile.encoding=UTF-8 -Dcom.android.tools.r8.enableKeepAnnotations=true"
  -Pkotlin.compiler.execution.strategy=in-process
)

bash .github/scripts/run-gradle-with-retry.sh \
  "${common_gradle_args[@]}" \
  "$@" \
  :app:unified:bundleLibreRelease

bash .github/scripts/run-gradle-with-retry.sh \
  "${common_gradle_args[@]}" \
  "$@" \
  :app:unified:bundleTvRelease

# Third Play artifact (Wear OS). Shares dev.spatialfin applicationId and
# release signing certificate with :app:unified.
bash .github/scripts/run-gradle-with-retry.sh \
  "${common_gradle_args[@]}" \
  "$@" \
  :companion:wear:bundleRelease
