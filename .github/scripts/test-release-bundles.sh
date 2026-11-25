#!/usr/bin/env bash
# Isolated smoke test for .github/scripts/release-bundles.sh and tag-release.sh.
# Runs in a disposable temp git repo with stubbed gradlew, keytool, and site tools.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP_WORK="$(mktemp -d)"
trap 'rm -rf "$TMP_WORK"' EXIT

pass() {
  echo "PASS: $1"
}

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

init_fixture() {
  local dir="$1"
  rm -rf "$dir"
  mkdir -p \
    "$dir/.github/scripts" \
    "$dir/buildSrc/src/main/kotlin" \
    "$dir/gradle" \
    "$dir/site" \
    "$dir/bin" \
    "$dir/app/unified/build/outputs/bundle/libreRelease" \
    "$dir/app/unified/build/outputs/bundle/tvRelease" \
    "$dir/companion/wear/build/outputs/bundle/release"

  cp "$REPO_ROOT/.github/scripts/tag-release.sh" "$dir/.github/scripts/"
  cp "$REPO_ROOT/.github/scripts/build-release-bundles.sh" "$dir/.github/scripts/"
  cp "$REPO_ROOT/.github/scripts/release-bundles.sh" "$dir/.github/scripts/"
  cp "$REPO_ROOT/.github/scripts/run-gradle-with-retry.sh" "$dir/.github/scripts/"
  chmod +x "$dir/.github/scripts/"*.sh

  cat > "$dir/gradle/libs.versions.toml" <<'EOF'
[versions]
r8 = "9.4.17"
EOF
  cat > "$dir/settings.gradle.kts" <<'EOF'
dependency {
    classpath("com.android.tools:r8:9.4.17")
}
EOF
  cat > "$dir/site/build.mjs" <<'EOF'
console.log("stub build.mjs");
EOF
  cat > "$dir/site/verify.mjs" <<'EOF'
console.log("stub verify.mjs");
EOF

  cat > "$dir/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> gradlew.calls
touch \
  app/unified/build/outputs/bundle/libreRelease/spatialfin-libre-release.aab \
  app/unified/build/outputs/bundle/tvRelease/spatialfin-tv-release.aab \
  companion/wear/build/outputs/bundle/release/wear-release.aab
EOF
  chmod +x "$dir/gradlew"

  cat > "$dir/bin/keytool" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
mode="${KEYTOOL_STUB_MODE:-valid}"
aab="${3:-}"
if [[ "$mode" == "debug" ]]; then
  echo "Owner: C=US, O=Android, CN=Android Debug"
  echo "SHA256: AA:AA:AA:AA"
elif [[ "$mode" == "mismatch" && "$aab" == *"wear-release.aab" ]]; then
  echo "Owner: CN=SpatialFin Release"
  echo "SHA256: BB:BB:BB:BB"
else
  echo "Owner: CN=SpatialFin Release"
  echo "SHA256: AA:AA:AA:AA"
fi
EOF
  chmod +x "$dir/bin/keytool"

  cat > "$dir/buildSrc/src/main/kotlin/Versions.kt" <<'EOF'
object Versions {
    const val APP_CODE = 171
    const val APP_NAME = "2.7.70"
}
EOF

  git -C "$dir" init -q
  git -C "$dir" config user.email "test@example.invalid"
  git -C "$dir" config user.name "Test"
  git -C "$dir" add -A
  git -C "$dir" commit -q -m "initial release v2.7.70"
  git -C "$dir" tag -a "v2.7.70" -m "Release 2.7.70"

  cat > "$dir/buildSrc/src/main/kotlin/Versions.kt" <<'EOF'
object Versions {
    const val APP_CODE = 172
    const val APP_NAME = "2.7.71"
}
EOF
  git -C "$dir" add buildSrc/src/main/kotlin/Versions.kt
  git -C "$dir" commit -q -m "bump version to 2.7.71"
}

run_in_fixture() {
  local dir="$1"
  shift
  (
    cd "$dir"
    unset JAVA_HOME || true
    PATH="$dir/bin:$PATH" "$@"
  )
}

FX="$TMP_WORK/fixture"

# 1. Conflicting flags exit 2
init_fixture "$FX"
set +e
run_in_fixture "$FX" bash .github/scripts/release-bundles.sh --site-only --skip-site >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" -eq 2 ]] || fail "Expected exit 2 for --site-only --skip-site, got $rc"
pass "Conflicting --site-only --skip-site exits 2"

# 2. R8 version mismatch fails Stage 1
init_fixture "$FX"
cat > "$FX/settings.gradle.kts" <<'EOF'
dependency {
    classpath("com.android.tools:r8:9.9.99")
}
EOF
set +e
out="$(run_in_fixture "$FX" bash .github/scripts/release-bundles.sh --check-only 2>&1)"
rc=$?
set -e
[[ "$rc" -ne 0 && "$out" == *"R8 version mismatch"* ]] || fail "Expected R8 mismatch failure, rc=$rc out=$out"
pass "R8 version mismatch rejected in Stage 1"

# 3. APP_CODE <= highest tag rejected
init_fixture "$FX"
cat > "$FX/buildSrc/src/main/kotlin/Versions.kt" <<'EOF'
object Versions {
    const val APP_CODE = 171
    const val APP_NAME = "2.7.71"
}
EOF
set +e
out="$(run_in_fixture "$FX" bash .github/scripts/release-bundles.sh --check-only 2>&1)"
rc=$?
set -e
[[ "$rc" -ne 0 && "$out" == *"not above the highest tagged code"* ]] || fail "Expected APP_CODE monotonicity failure, rc=$rc out=$out"
pass "APP_CODE <= highest tag rejected"

# 4. Existing v<APP_NAME> tag rejected
init_fixture "$FX"
git -C "$FX" tag -a "v2.7.71" -m "Already tagged"
set +e
out="$(run_in_fixture "$FX" bash .github/scripts/release-bundles.sh --check-only 2>&1)"
rc=$?
set -e
[[ "$rc" -ne 0 && "$out" == *"already exists locally"* ]] || fail "Expected existing tag failure, rc=$rc out=$out"
pass "Existing v<APP_NAME> tag rejected"

# 5. --site-only succeeds without running Stage 1 or gradlew even if tag exists
rm -f "$FX/gradlew.calls"
run_in_fixture "$FX" bash .github/scripts/release-bundles.sh --site-only >/dev/null
[[ ! -f "$FX/gradlew.calls" ]] || fail "--site-only must not invoke gradlew"
pass "--site-only runs Stages 2-3 without gradlew"

# 6. --check-only runs Stages 1-3 without gradlew or git tag creation
init_fixture "$FX"
rm -f "$FX/gradlew.calls"
run_in_fixture "$FX" bash .github/scripts/release-bundles.sh --check-only >/dev/null
[[ ! -f "$FX/gradlew.calls" ]] || fail "--check-only must not invoke gradlew"
if git -C "$FX" rev-parse -q --verify "refs/tags/v2.7.71" >/dev/null; then
  fail "--check-only must not create git tags"
fi
pass "--check-only validates Stages 1-3 read-only"

# 7. Debug cert fails without --allow-debug-signing and succeeds with --allow-debug-signing
init_fixture "$FX"
rm -f "$FX/gradlew.calls"
set +e
out="$(KEYTOOL_STUB_MODE=debug run_in_fixture "$FX" bash .github/scripts/release-bundles.sh 2>&1)"
rc=$?
set -e
[[ "$rc" -ne 0 && "$out" == *"CN=Android Debug"* ]] || fail "Expected debug cert rejection, rc=$rc out=$out"
grep -q -- "-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true" "$FX/gradlew.calls" || fail "Expected require-release-signing flag in default release mode"

rm -f "$FX/gradlew.calls"
KEYTOOL_STUB_MODE=debug run_in_fixture "$FX" bash .github/scripts/release-bundles.sh --allow-debug-signing >/dev/null
if grep -q -- "-PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true" "$FX/gradlew.calls"; then
  fail "--allow-debug-signing must omit -PSPATIALFIN_REQUIRE_RELEASE_SIGNING=true"
fi
pass "CN=Android Debug rejected by default and permitted with --allow-debug-signing"

# 8. Cert mismatch fails Stage 5; matching release cert passes full 6-stage flow
init_fixture "$FX"
set +e
out="$(KEYTOOL_STUB_MODE=mismatch run_in_fixture "$FX" bash .github/scripts/release-bundles.sh 2>&1)"
rc=$?
set -e
[[ "$rc" -ne 0 && "$out" == *"Signing certificate mismatch"* ]] || fail "Expected cert mismatch failure, rc=$rc out=$out"

rm -f "$FX/gradlew.calls"
out="$(KEYTOOL_STUB_MODE=valid run_in_fixture "$FX" bash .github/scripts/release-bundles.sh)"
grep -q -- "-Dcom.android.tools.r8.enableKeepAnnotations=true" "$FX/gradlew.calls" || fail "Missing enableKeepAnnotations flag"
grep -q -- ":app:unified:bundleLibreRelease" "$FX/gradlew.calls" || fail "Missing bundleLibreRelease call"
grep -q -- ":app:unified:bundleTvRelease" "$FX/gradlew.calls" || fail "Missing bundleTvRelease call"
grep -q -- ":companion:wear:bundleRelease" "$FX/gradlew.calls" || fail "Missing bundleRelease call"
grep -q -- "libre=172, tv=1000172, wear=2000172" <<< "$out" || fail "Missing version summary"
pass "Full 6-stage flow validates cert parity and exact Gradle flags"
