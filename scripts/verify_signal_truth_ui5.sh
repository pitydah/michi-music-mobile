#!/usr/bin/env bash
# R12: verify the integration that is already applied on a Git checkout.
# Usage: bash scripts/verify_signal_truth_ui5.sh [repo_checkout]
set -Eeuo pipefail
if [[ $# -gt 1 ]]; then echo 'FAIL: expected [repo_checkout]' >&2; exit 2; fi
repo="$(realpath "${1:-.}")"
if ! git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo 'FAIL: no full Git checkout' >&2; exit 2
fi
if [[ ! -f "$repo/gradlew" ]] || [[ ! -f "$repo/app/build.gradle.kts" ]]; then
  echo 'FAIL: missing Gradle wrapper or Android app module' >&2; exit 2
fi
# Neither an old UI5 patch nor a signal patch is reapplied: both must already exist.
for marker in \
  'app/src/main/java/org/michimusic/mobile/ui/seeker/LocalWaveformDecoder.kt' \
  'app/src/main/java/org/michimusic/mobile/ui/components/SignalTruthMobileSheet.kt' \
  'app/src/main/java/org/michimusic/mobile/playback/SignalTruthGraphProjector.kt'; do
  if [[ ! -f "$repo/$marker" ]]; then echo "FAIL: integration marker absent: $marker" >&2; exit 3; fi
done
command -v java >/dev/null || { echo 'FAIL: Java missing' >&2; exit 4; }
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" || ! -d "$sdk/platforms" ]]; then
  echo 'FAIL: Android SDK missing; Android verification NOT_RUN' >&2; exit 4
fi
cd "$repo"
git diff --check
bash ./gradlew --no-daemon :player:testDebugUnitTest :app:testNormalDebugUnitTest :app:assembleNormalDebug
apk='app/build/outputs/apk/normal/debug/app-normal-debug.apk'
if [[ ! -s "$apk" ]]; then echo "FAIL: APK missing: $apk" >&2; exit 5; fi
if [[ "${RUN_ANDROID_DEVICE_TESTS:-0}" == '1' ]]; then
  bash ./gradlew --no-daemon :app:connectedNormalDebugAndroidTest
fi
printf 'PASS: UI5 + Signal Truth tested and APK built at %s\n' "$(git rev-parse --short HEAD)"
