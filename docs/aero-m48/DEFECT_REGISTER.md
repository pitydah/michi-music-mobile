# KILLCRITIC Defect Register — M48 AERO PR7

**Auditor:** KILLCRITIC Release Auditor & Distributed Audio Reliability Engineer  
**Target Repository:** `pitydah/michi-music-mobile`  
**Target Branch:** `integration/m46d-aero-premium`  
**PR:** #7  
**Date:** 2026-10-31  

---

## 1. Executive Summary

This Defect Register contains the exhaustive analysis, root-cause investigation, architectural remediation, and automated verification evidence for the seven critical engineering defects identified in Milestone M47 (K47-01 through K47-07). Every defect has been remediated adhering strictly to the **Audio Purity Architecture**, **Pure Icon-Only UI Invariant**, and **Honest Signal Truth**.

All fixes are covered by dedicated, regression-proof automated unit tests in `:app`, `:player`, and `:michi-link-client`.

---

## 2. Defect Ledger

### K47-01: Split-Brain Double Playback & Queue Race Under Concurrent Local/Remote Requests
- **Severity:** P0 — CRITICAL
- **Subsystem:** Audio Engine / Playback Session Authority (`PlaybackSessionManager.kt`, `RemoteQueueConfirmation.kt`)
- **Root Cause:**
  When a local playback request coincided with a remote queue playback transfer, concurrent coroutines were spawned without a singular authority token. If a remote queue operation was pending while local playback began, both the local ExoPlayer pipeline and the remote `MichiLinkClient` transfer proceeded concurrently, resulting in split-brain playback, corrupted queues, and inconsistent notification states.
- **Remediation:**
  1. Implemented `_activeQueuePlaybackOp` cancellation token tracking in `PlaybackSessionManager.kt`.
  2. Each new queue playback request increments an atomic operation ID, cancels any previous in-flight queue playback job, and marks prior operations as superseded.
  3. Strict cancellation check before committing the active playback authority.
- **Verification Evidence:**
  - `PlaybackSessionManagerTest.kt`:
    - `queuePlaybackPreemption_cancelsPriorRequest` (Verifies in-flight request is cancelled when superseded).
    - `queuePlayback_concurrentPreemption_keepsOnlyWinner` (Verifies atomic preemption under race conditions).
- **Status:** **CLOSED / VERIFIED**

---

### K47-02: Remote Signal Truth Contaminated by Local Audio Pipeline Observation
- **Severity:** P0 — CRITICAL
- **Subsystem:** Signal Truth Engine (`SignalTruthResolver.kt`)
- **Root Cause:**
  When resolving the audio signal chain in remote playback mode (`PlaybackAuthority.REMOTE`), `SignalTruthResolver` was inspecting the local `AudioPipelineObservation.sourceFormat` (populated by local Android audio decoders) and presenting it as the remote stream's declared format. This violated Honest Signal Truth by claiming remote devices were receiving/processing local codec parameters.
- **Remediation:**
  1. In `SignalTruthResolver.resolve()`, when `authority == PlaybackAuthority.REMOTE`, source format is strictly decoupled from `observation.sourceFormat`.
  2. Source format is resolved exclusively from the current track's metadata (`currentTrack.bitrate`, `currentTrack.sampleRate`, `currentTrack.format`).
  3. When track metadata is unavailable, it honestly declares `SignalFormat.UNKNOWN` with `"No declarado"`.
- **Verification Evidence:**
  - `SignalTruthResolverTest.kt`:
    - `remotePlayback_neverInheritsLocalSourceFormat` (Ensures local pipeline format is ignored when authority is remote).
    - `remotePlayback_withoutMetadata_returnsUnknownDeclared` (Ensures missing metadata yields honest unknown).
- **Status:** **CLOSED / VERIFIED**

---

### K47-03: Fabricated Audio Measurements (16/44.1 Defaults & Synthetic Freshness Timestamps)
- **Severity:** P1 — HIGH
- **Subsystem:** Signal Truth Engine (`SignalTruthResolver.kt`)
- **Root Cause:**
  In baseline code, whenever `sampleRate <= 0` or `bitDepth <= 0`, fallback logic arbitrarily assigned `44100 Hz` and `16 bit` ("CD quality"). Furthermore, the freshness timestamp `lastUpdatedMs` was generated on the fly via `System.currentTimeMillis()`, giving the illusion of a live, fresh measurement even when no hardware observation had occurred.
- **Remediation:**
  1. Eliminated all synthetic defaults: non-positive `sampleRate` or `bitDepth` directly evaluates to `SignalFormat.UNKNOWN` with description `"No observado"`.
  2. Strictly bound freshness timestamps to `observation.observedAtMs`. If the hardware observation is absent or zero, timestamp defaults to `0L` (never `System.currentTimeMillis()`).
  3. No DSD, DoP, or synthetic bit-perfect claims are ever synthesized.
- **Verification Evidence:**
  - `SignalTruthResolverTest.kt`:
    - `invalidSampleRateOrBitDepth_isUnknown_neverDefaultsTo16_44` (Verifies non-positive rates/depths do not fabricate 16/44.1).
    - `freshness_comesFromObservationTimestamp_neverInventedSystemCurrentTime` (Verifies timestamps come from observation).
- **Status:** **CLOSED / VERIFIED**

---

### K47-04: Remote Control Optimistic Phantom Connection State
- **Severity:** P1 — HIGH
- **Subsystem:** Michi Control / Remote UI (`RemoteViewModel.kt`, `RemoteScreen.kt`)
- **Root Cause:**
  `RemoteViewModel` was reporting `connected = true` immediately upon selecting an endpoint or initiating a socket attempt, before `SyncConnectionManager` had successfully authenticated and verified bidirectional RPC transport. Users were shown transport controls for devices that were offline or unreachable.
- **Remediation:**
  1. Enriched `RemoteConnectionState` with explicit states: `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `UNAUTHORIZED`, `REVOKED`, `OFFLINE`, and `ERROR`.
  2. `RemoteViewModel` reactively combines `sessionManager.sessionState` with `connectionManager.connectionStates`. Unlinked or unconfirmed endpoints strictly yield `DISCONNECTED` with `connected = false`.
  3. `RemoteScreen.kt` renders honest contextual subtitles (`Conectado`, `Conectando...`, `Sin conexión`, etc.) and informative empty-state UI preventing phantom command dispatch.
- **Verification Evidence:**
  - `RemoteViewModelTest.kt`:
    - `connectionState_reflectsSyncManagerStates_exhaustively` (Validates 1:1 mapping of all network states).
    - `connectionState_unmappedOrDisconnectedEndpoint_isDisconnected` (Ensures unlinked endpoints are not marked connected).
  - Emulator screenshot verification: `docs/aero-m48/screenshots/emulator_nowplaying.png`.
- **Status:** **CLOSED / VERIFIED**

---

### K47-05: AirCat Target Selector Missing & Unverified Completion Claims
- **Severity:** P1 — HIGH
- **Subsystem:** Devices / AirCat Sync (`AirCatModels.kt`, `AirCatTransferClient.kt`, `SyncComponents.kt`, `DevicesScreen.kt`)
- **Root Cause:**
  1. "Sincronizar con AirCat" had no multi-device target selection, picking an arbitrary device or crashing if none existed.
  2. The UI (`AirCatCompletedView`) unconditionally displayed: *"Todos los archivos pasaron la verificación SHA-256 e ingresaron a la biblioteca."* even when transfers failed or completed without integrity checksumming or library ingestion.
- **Remediation:**
  1. Added `checksumVerified: Boolean = false` and `libraryCommitted: Boolean = false` to `AirCatProgressEvent`.
  2. `AirCatTransferClient` strictly asserts both SHA-256 hash match and library database ingestion before setting flags.
  3. `AirCatCompletedView` displays the full verification text ONLY when `checksumVerified && libraryCommitted` are true; otherwise shows an honest warning notice.
  4. `DevicesScreen.kt` queries `eligibleAirCatDevices`:
     - 0 devices: Shows informational alert ("Sin destinos AirCat elegibles").
     - 1 device: Navigates directly to sync route.
     - >1 devices: Launches modal target picker (`AirCatTargetPicker`).
- **Verification Evidence:**
  - `AirCatSafeLayoutTest.kt`:
    - `completedView_requiresBothChecksumAndLibraryFlagsForVerifiedMessage` (Checks truth in completion text).
    - `completedView_missingChecksumOrLibrary_showsUnverifiedNotice` (Checks unverified warning).
  - Emulator screenshot verification: `docs/aero-m48/screenshots/emulator_aircat_dialog.png`.
- **Status:** **CLOSED / VERIFIED**

---

### K47-06: Queue Playback Authority Without Target Confirmation
- **Severity:** P0 — CRITICAL
- **Subsystem:** Playback Authority Engine (`RemoteQueueConfirmation.kt`)
- **Root Cause:**
  Remote queue commands assumed the target device immediately and synchronously accepted queue handover without confirming session endpoint ID, queue checksum, or active status. Network drops caused local playback to mute while remote playback never started.
- **Remediation:**
  1. Created `RemoteQueueConfirmation` contract enforcing 3-way handshake: request dispatch -> remote acknowledgement -> authority transition.
  2. Any mismatch, timeout, or cancellation aborts authority handover and restores local playback state safely.
- **Verification Evidence:**
  - `RemoteQueueConfirmationTest.kt`:
    - `confirmedHandshake_grantsAuthority`
    - `rejectedOrTimedOutHandshake_retainsLocalAuthority`
  - `PlaybackSessionManagerTest.kt`: all preemption tests verify rollback on remote rejection.
- **Status:** **CLOSED / VERIFIED**

---

### K47-07: CoverFlow Pager Crash/Hang on Edge Cases & Rapid Recomposition
- **Severity:** P2 — MEDIUM
- **Subsystem:** UI Components / CoverFlow (`MichiCoverFlowHost.kt`)
- **Root Cause:**
  HorizontalPager key function `{ index -> albums[index].id }` caused an `IndexOutOfBoundsException` during dataset filtering or rapid album deletion when the pager state held an index beyond the new list bounds.
- **Remediation:**
  1. Hardened key function with bounds checking: `{ index -> if (index in albums.indices) albums[index].id else index }`.
  2. Added defensive album resolution inside the page composable: `val album = albums.getOrNull(page) ?: return@HorizontalPager`.
  3. Added empty state fallback and single-item stabilization.
- **Verification Evidence:**
  - `CoverFlowHostTest.kt` (Robolectric Compose tests for 0, 1, and 50+ albums under animated scroll).
  - Emulator screenshot verification: `docs/aero-m48/screenshots/emulator_albums_coverflow.png`.
- **Status:** **CLOSED / VERIFIED**

---

## 3. Summary Matrix

| Defect ID | Title | Severity | Invariant Preserved | Regression Suite | Status |
|---|---|---|---|---|---|
| **K47-01** | Split-Brain Concurrent Playback | P0 | Playback Authority | `PlaybackSessionManagerTest` | **CLOSED** |
| **K47-02** | Remote Signal Truth Leakage | P0 | Honest Signal Truth | `SignalTruthResolverTest` | **CLOSED** |
| **K47-03** | Fabricated Audio Measurements | P1 | Signal Measurement Honesty | `SignalTruthResolverTest` | **CLOSED** |
| **K47-04** | Phantom Remote Connection State | P1 | Reactive State Integrity | `RemoteViewModelTest`, `RemoteScreenTest` | **CLOSED** |
| **K47-05** | AirCat Selector & Unverified Text | P1 | Honest Completion Claims | `AirCatSafeLayoutTest`, `DevicesScreen` | **CLOSED** |
| **K47-06** | Remote Queue Handshake Confirmation | P0 | Queue Confirmation Authority | `RemoteQueueConfirmationTest` | **CLOSED** |
| **K47-07** | CoverFlow Pager Bounds Index Crash | P2 | Visual Smoothness & Safety | `CoverFlowHostTest` | **CLOSED** |
