# Baseline Inventory & Audit Freeze — M49 AERO FINAL 100

**Auditor:** KILLCRITIC Release Auditor, Principal Android Engineer & Distributed Audio Reliability Engineer  
**Date:** 2026-10-31  
**Repository:** `pitydah/michi-music-mobile`  
**Active Branch:** `integration/m46d-aero-premium`  
**PR:** #7  
**Baseline HEAD:** `fdc359f9d9466c443b13a61a89c747f9ff63a0ca`  
**CI Baseline:** Run `41210099864` (7/7 success)  

---

## 1. Toolchain & Environment Verification

- **OS:** Linux x86_64
- **JDK:** OpenJDK 17.0.14
- **Gradle:** Wrapper 8.9 / Android Gradle Plugin 8.7.0
- **Kotlin:** 2.0.21
- **Target SDK:** 35 / Min SDK: 26 / Compile SDK: 35
- **Android SDK & Tools:** `/home/cristian/Android/Sdk`
  - `adb`: `/home/cristian/Android/Sdk/platform-tools/adb` (Android Debug Bridge 1.0.41, Version 36.0.0-13206425)
  - `emulator`: `/home/cristian/Android/Sdk/emulator/emulator` (Version 36.3.11.0)
  - AVD: `Medium_Phone` (API 34, 1080x2400 @ 420dpi)
  - Hardware Acceleration: `/dev/kvm` available (`crw-rw-rw-`)

---

## 2. Initial Defect Ledger (K48-01 through K48-06)

| Defect ID | Severity | Subsystem | Description | Target Invariant |
|---|---|---|---|---|
| **K48-01** | P0 | Playback Authority (`RemoteQueueConfirmation.kt`) | Remote track playback confirmed without verifying `PlaybackStateDto.trackId` against requested track. | Distributed Audio Safety & Queue Integrity |
| **K48-02** | P1 | AirCat Integrity (`AirCatTransferClient.kt`, `LinkTransferManager.kt`) | Empty or invalid checksum bypasses file verification but transfer still claims `checksumVerified=true`. Temp file deleted before logging diagnostic hash. | Honest Content Integrity (Strict SHA-256) |
| **K48-03** | P1 | AirCat Manifest (`AirCatTransferClient.kt`, `AirCatModels.kt`) | Manifests with `totalTracks > 0` but `items.isEmpty()` or inconsistent track counts not rejected upfront. | Manifest Validation & Preflight Truth |
| **K48-04** | P1 | Devices & AirCat Navigation (`DevicesScreen.kt`, `AirCatEligibilityPolicy.kt`) | AirCat shortcut ambiguously routes to generic SyncWizard instead of dedicated AirCat transfer pipeline. Device eligibility allows unverified/incompatible roles (e.g. Stream). | Pure Devices Integration & Role Enforcement |
| **K48-05** | P0/P1 | Playback Concurrency (`PlaybackSessionManager.kt`) | Cancellation token machine in-flight does not cleanly handle remote host execution race or late response reconciliation. | Single Active Playback Authority |
| **K48-06** | P1 | Signal Truth (`SignalTruthResolver.kt`) | Remote declared freshness timestamp borrows local pipeline `observation.observedAtMs` instead of remote telemetry. | Honest Signal Truth & Provenance |

---

## 3. Visual & Architecture Invariants Checklist

1. **Audio-Only**: Zero video codecs or surfaces.
2. **Pure Icon-Only Controls**: Zero discs, rings, bubbles, or background pills under pictograms (including hero Play/Pause). Hitboxes $\ge 48\times 48\text{ dp}$; NowPlaying Play/Pause $64\text{--}72\text{ dp}$.
3. **No Volume Sliders**: Strictly absent from NowPlaying and MiniPlayer.
4. **4 Main Navigation Tabs**: Inicio, Biblioteca, Buscar, Dispositivos. AirCat exclusive to Devices tab.
5. **Real CoverFlow**: Active 3D carousel (`LibraryViewMode.COVERFLOW`) with central album, lateral tilt, and track explorer (not just a 2D list).
6. **Remote with Connected State**: Must verify live or deterministic fixture showing active connection and controls.
