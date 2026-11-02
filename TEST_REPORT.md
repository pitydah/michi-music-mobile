# Michi Music Mobile — Test & Verification Report

> **M22 Sprint 2, 25 October 2026:** The below build/test logs belong to the prior repository revision (21 October 2026). They were **not rerun** after the M22 code modifications. New evidence: 10/10 isolated Kotlin route and transition checks passed; full Gradle, Android instrumentation and physical DAC verification remain pending. The previous "ALL GATES PASS" statement must not be interpreted as certification of the current code. See `M22_SPRINT2_REPORT.md`.


## Environment Details
* **OS**: Linux x86_64
* **JDK**: OpenJDK 21 (JetBrains Runtime)
* **Android SDK**: API 35 (compileSdk), API 35 (targetSdk), API 31 (minSdk)
* **Gradle**: 8.13
* **AGP**: 8.9.1
* **Kotlin**: 2.1.10
* **Media3**: 1.6.1

---

## Executed Commands & Actual Results

### 1. Global Lint Verification
```bash
./gradlew lint
```
* **Exit Code**: `0`
* **Output**: `BUILD SUCCESSFUL` (0 errors across all modules).
* **Reports**:
  * `:core:lintReportDebug` -> `core/build/reports/lint-results-debug.html`
  * `:data:lintReportDebug` -> `data/build/reports/lint-results-debug.html`
  * `:player:lintReportDebug` -> `player/build/reports/lint-results-debug.html`
  * `:michi-link-client:lintReportDebug` -> `michi-link-client/build/reports/lint-results-debug.html`
  * `:app:lintReportFdroidDebug` -> `app/build/reports/lint-results-fdroidDebug.html`

---

### 2. Full Unit Test Suite Across All Modules
```bash
./gradlew test
```
* **Exit Code**: `0`
* **Output**: `BUILD SUCCESSFUL` (100% test execution pass).
* **Tasks Executed & Verified**:
  * `:core:testDebugUnitTest` -> **PASS**
  * `:data:testDebugUnitTest` -> **PASS**
  * `:player:testDebugUnitTest` -> **PASS** (includes `MichiAudioEffectsTest`, `UsbDacManagerTest`, `ReplayGainAudioProcessorTest`)
  * `:michi-link-client:testDebugUnitTest` -> **PASS**
  * `:app:testNormalDebugUnitTest` -> **PASS**
  * `:app:testNormalReleaseUnitTest` -> **PASS**
  * `:app:testFdroidDebugUnitTest` -> **PASS**
  * `:app:testFdroidReleaseUnitTest` -> **PASS**
  * `:app:testPlaystoreDebugUnitTest` -> **PASS**
  * `:app:testPlaystoreReleaseUnitTest` -> **PASS**

---

### 3. Distribution APK Compilation & R8 Minification
```bash
./gradlew assembleNormalDebug assembleFdroidDebug assembleNormalRelease assembleFdroidRelease
```
* **Exit Code**: `0`
* **Output**: `BUILD SUCCESSFUL` (31 executed, 536 up-to-date).
* **Generated Artifacts**:
  * `app/build/outputs/apk/normal/debug/app-normal-debug.apk`
  * `app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk`
  * `app/build/outputs/apk/normal/release/app-normal-release.apk`
  * `app/build/outputs/apk/fdroid/release/app-fdroid-release.apk`

---

## Final Stability Verdict
**HISTORICAL RESULT ONLY (prior revision): ALL GATES PASS (100%)** — This report predates M22. Current M22 release certification is **PENDING**, subject to recompilation and hardware regression testing.
