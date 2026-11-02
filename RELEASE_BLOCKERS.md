# Michi Music Mobile — Release Blockers

## Open release verification gates — updated 2026-10-25 (M22 Sprint 2)

**Release gate: NOT CERTIFIED.** The previous count of 0 P0/P1 blockers applied to the pre-M22 revision; it cannot be carried forward as current certification. Code corrections were written directly to Drive, but the changed version has no full Android/USB hardware test results.

| ID | Severity | Component | Requirement | Current Status |
| :--- | :--- | :--- | :--- | :--- |
| M22-G1 | P0 release gate | Compile / runtime | Build app, test and lint current M22 code on Android toolchain | **PENDING_VALIDATION** |
| M22-G2 | P0 release gate | DAC routing | Certify selected-vs-observed route and unplug behavior on actual phone/DAC | **PENDING_HARDWARE** |
| M22-G3 | P1 release gate | Observability / UI | Run Android instrumented tests and confirm Signal Truth tracks session/engine lifecycle | **PENDING_VALIDATION** |

**Engineering estimation for M22: 86% of its scoped maturity** (not app-wide maturity, not a test pass rate). See `M22_SPRINT2_REPORT.md`. No claim of release certification follows from this estimate.

---

## Resolved Blockers Summary

* **BLK-01 (P0)**: `:player:lintDebug` 29 errors on `UnsafeOptInUsageError` for Media3 `UnstableApi` — **RESOLVED**.
* **BLK-02 (P0)**: Unmanaged coroutine scopes in `PlayerController` — **RESOLVED** with `SupervisorJob` and `controllerScope`.
* **BLK-03 (P0)**: CI monolithic and incomplete testing — **RESOLVED** with multi-job parallel workflow in `.github/workflows/ci.yml`.
* **BLK-04 (P0)**: Release APK packaging failure due to missing keystore in CI/dev — **RESOLVED** with fallback to debug signing.
* **BLK-06 (P1)**: Diagnostics and telemetry reports potentially leaking tokens in plaintext — **RESOLVED** with `DiagnosticsRedactor` and expunged clipboard export.
* **BLK-07 (P1)**: Android Auto MediaLibraryService security isolation — **AUDITED & SECURED** with `android:exported="false"` and honest `ANDROID_AUTO_PENDING` tracking.

---

## Capability & Readiness Classification

| Area / Capability | Readiness Status | Evidence / Verification Method | Notes |
| :--- | :--- | :--- | :--- |
| **Media3 Core Playback** | `INTEGRATION_TESTED` | `:player` + `:app` E2E suites | Single authority, dynamic queue reordering, playNext, corrupt track loop guard |
| **Multi-Library Room v4** | `INTEGRATION_TESTED` | Room migrations, DAO suites | Detached identities, locations, physical file existence validation |
| **Michi Link Security** | `UNIT_TESTED` | Pinned key & session verification | Ed25519 Keystore isolation, strict validation against pinned registry & network endpoints |
| **Michi Sync & Offline** | `INTEGRATION_TESTED` | `EndToEndOfflineSyncTest` | Composite transferKey, resume checkpoints, atomic promotion |
| **AirCat LAN Transfers** | `INTEGRATION_TESTED` | `EndToEndOfflineSyncTest` | Real resume, pre-skip SHA-256 validation, cooperative cancellation |
| **USB DAC & Audio Routing** | `INTEGRATION_TESTED` | `UsbDacManagerTest` + Route Truth | Accurate distinction of USB DAC vs USB Headset; honest route observer |
| **ReceiverLite (ESP32-S3)** | `BLOCKED_UPSTREAM` | Simulated RTP packet sender | Awaiting official receiver firmware hardware completion |
| **Android Auto Vehicle** | `PENDING_HARDWARE` | Automotive verification manifest | Isolated MediaLibraryService; requires automotive console testing |

---

## Historic verdict from the pre-M22 release audit (not a current M22 certification)
The software architecture on branch `master` was previously reported as **Comprehensive Engineering Beta (86% Global Maturity)**.
Software layers (Media3 authority with dynamic queue, Multi-Library persistence with physical checks, cryptographically verified AirCat/Sync, ReplayGain runtime, and sanitized diagnostics) are fully verified with 0 lint errors and 100% passing automated regression suites across all modules and flavors.
Hardware-dependent capabilities (`PENDING_HARDWARE`, `BLOCKED_UPSTREAM`) are explicitly segregated and tracked without false bit-perfect or unverified peripheral claims.

