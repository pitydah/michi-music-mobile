#!/usr/bin/env bash
# Michi Music Mobile audio-patch verification; run AFTER applying the patch in a worktree.
# All artifacts stay local. No git reset, checkout, clean, install of user data or repository writes.
set -Eeuo pipefail

usage() {
  cat <<'HELP'
Usage: bash scripts/verify_michi_mobile_audio.sh [--unit|--full|--instrumented|--skills|--help]
  --unit          default: JVM tests for every module and all three app flavors
  --full          JVM + AndroidTest compilation + lint + all debug APKs + Normal release APK
  --instrumented  requires one authorized Android device/emulator; runs connected Normal tests
  --skills        inventory OpenCode skills installed in project/global directories

Operator-only runner: set MICHI_DIRECT_GRADLE_OPERATOR=1. OpenCode agents MUST use Chris Banes gradle-run. Physical USB/RTP tests are MANUAL.
RESULTS are stored under MICHI_TEST_REPORT_DIR or build/verification/michi-audio/<UTC-run>.
No mode certifies bit-perfect, DAC hardware, USB hotplug or RELEASE_GO.
HELP
}
mode="${1:---unit}"
case "$mode" in
  --unit|--full|--instrumented|--skills) ;;
  --help|-h) usage; exit 0 ;;
  *) usage >&2; exit 64 ;;
esac

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$repo"
if [[ "$mode" == --skills ]]; then
  exec bash scripts/verify_michi_opencode_skills.sh --inventory
fi
# Chris Banes gradle-run requires every agent-initiated Gradle invocation through
# its own workflow wrapper. This legacy shell runner is human/operator-only.
if [[ "${MICHI_DIRECT_GRADLE_OPERATOR:-0}" != 1 ]]; then
  echo 'BLOCKED_GRADLE_SKILL: OpenCode must load gradle-run and execute its standalone create/run/status/finish workflow. Direct shell Gradle runner is reserved for operator use with MICHI_DIRECT_GRADLE_OPERATOR=1.' >&2
  exit 6
fi
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "FAIL: not a Git worktree. Use an isolated worktree from the audited SHA." >&2
  exit 2
fi
if [[ ! -f ./gradlew ]]; then
  echo 'FAIL: gradlew not found; run from the patched Michi Music Mobile repository' >&2
  exit 2
fi
if ! command -v java >/dev/null 2>&1; then
  echo 'FAIL: Java 17 is required (JAVA_HOME must point to JDK 17)' >&2
  exit 2
fi
if command -v git >/dev/null 2>&1; then
  sha="$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
else
  sha=unknown
fi
run_id="$(date -u +%Y%m%dT%H%M%SZ)"
report="${MICHI_TEST_REPORT_DIR:-$repo/build/verification/michi-audio/$run_id}"
mkdir -p "$report"
results="$report/results.tsv"
printf 'gate\tresult\tlog\n' > "$results"
{
  printf 'MODE=%s\nGIT_HEAD=%s\nUTC=%s\n' "$mode" "$sha" "$run_id"
  java -version 2>&1
  if command -v git >/dev/null 2>&1; then git status --short; fi
  if [[ -n "${MICHI_PATCH_PATH:-}" && -f "$MICHI_PATCH_PATH" ]]; then
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$MICHI_PATCH_PATH"; fi
  fi
} > "$report/environment.txt" 2>&1

run_gate() {
  local label="$1"
  shift
  local log="$report/${label}.log"
  echo "RUN $label"
  if "$@" >"$log" 2>&1; then
    printf '%s\tPASS\t%s\n' "$label" "$log" >> "$results"
    echo "PASS $label"
  else
    local code=$?
    printf '%s\tFAIL(%s)\t%s\n' "$label" "$code" "$log" >> "$results"
    echo "FAIL $label (exit=$code). See $log" >&2
    exit "$code"
  fi
}

if [[ "$mode" == --full || "$mode" == --instrumented ]]; then
  run_gate opencode_skills bash scripts/verify_michi_opencode_skills.sh --check
fi

# The tasks are grounded in app/core/player build.gradle.kts and .github/workflows/ci.yml
# from base a65fbdede006af898fc9d3c0e883098a6933a0e6.
# Nonzero Gradle failures are NEVER swallowed, skipped or reclassified as PASS.
if [[ "$mode" == '--unit' || "$mode" == '--full' ]]; then
  run_gate core_tests bash ./gradlew --no-daemon --stacktrace --console=plain :core:testDebugUnitTest
  run_gate data_tests bash ./gradlew --no-daemon --stacktrace --console=plain :data:testDebugUnitTest
  run_gate player_tests bash ./gradlew --no-daemon --stacktrace --console=plain :player:testDebugUnitTest
  run_gate link_tests bash ./gradlew --no-daemon --stacktrace --console=plain :michi-link-client:testDebugUnitTest
  run_gate normal_tests bash ./gradlew --no-daemon --stacktrace --console=plain :app:testNormalDebugUnitTest
  run_gate fdroid_tests bash ./gradlew --no-daemon --stacktrace --console=plain :app:testFdroidDebugUnitTest
  run_gate room_migration bash ./gradlew --no-daemon --stacktrace --console=plain :data:testDebugUnitTest --tests org.michimusic.data.cache.Migration3To4Test
fi
if [[ "$mode" == '--full' ]]; then
  run_gate android_test_compile bash ./gradlew --no-daemon --stacktrace --console=plain :app:compileNormalDebugAndroidTestKotlin :app:assembleNormalDebugAndroidTest :app:compileFdroidDebugAndroidTestKotlin
  run_gate lint bash ./gradlew --no-daemon --stacktrace --console=plain lint
  run_gate debug_apks bash ./gradlew --no-daemon --stacktrace --console=plain :app:assembleNormalDebug :app:assembleFdroidDebug :app:assemblePlaystoreDebug
  run_gate normal_release_compile bash ./gradlew --no-daemon --stacktrace --console=plain :app:assembleNormalRelease
fi
if [[ "$mode" == '--instrumented' ]]; then
  if ! command -v adb >/dev/null 2>&1; then
    echo 'FAIL: adb not available. Android instrumented tests not executed.' >&2; exit 3
  fi
  devices="$(adb devices | awk 'NR>1 && $2=="device" {n++} END {print n+0}')"
  if [[ "$devices" != 1 ]]; then
    echo "FAIL: expected exactly one authorized adb device, found $devices" >&2; exit 3
  fi
  run_gate connected_android_test bash ./gradlew --no-daemon --stacktrace --console=plain :app:connectedNormalDebugAndroidTest
fi
printf '\nAutomated checks completed for %s. Evidence: %s\n' "$mode" "$report"
echo 'NOT CERTIFIED: physical DAC USB, RTP/Michi servers, bit-perfect, thermal stability, RELEASE_GO.'
