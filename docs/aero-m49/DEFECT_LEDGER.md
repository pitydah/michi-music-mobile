# M49 Aero Final 100 — Defect Ledger (K48-01 to K48-06)

**Audit Milestone:** M49 — AERO FINAL 100  
**Target Branch:** `integration/m46d-aero-premium`  
**Base Commit:** `fdc359f9d9466c443b13a61a89c747f9ff63a0ca`  
**Release Auditor:** KILLCRITIC Release Auditor & Principal Android Engineer  

---

## Executive Defect Summary

| Defect ID | Severity | Category | Status | Primary Component | Verified By Test |
|---|---|---|---|---|---|
| **K48-01** | CRITICAL | Playback Authority / Concurrency | **RESOLVED** | `PlaybackSessionManager` / `RemoteQueueConfirmation` | `RemoteQueueConfirmationTest`, `PlaybackSessionManagerTest` |
| **K48-02** | HIGH | AirCat Integrity / Cryptographic Hash | **RESOLVED** | `LinkTransferManager` | `AirCatContractTest` |
| **K48-03** | HIGH | Manifest Validation / Security | **RESOLVED** | `AirCatTransferClient` | `AirCatContractTest` |
| **K48-04** | MEDIUM | Domain Eligibility & Routing | **RESOLVED** | `AirCatEligibilityPolicy` / `DevicesScreen` | `AirCatEligibilityPolicyTest` |
| **K48-05** | HIGH | Destructive UX / Queue Safety | **RESOLVED** | `RemoteQueueConfirmation` | `RemoteQueueConfirmationTest` |
| **K48-06** | HIGH | Signal Truth / Telemetry Integrity | **RESOLVED** | `SignalTruthResolver` / `NowPlayingScreen` | `SignalTruthResolverTest` |

---

## Detailed Root Cause Analysis & Resolutions

### K48-01 & K48-05: Playback Authority Collision & Destructive Remote Queue Wipe

* **Severity:** CRITICAL / HIGH
* **Symptom:**
  Attaching or switching playback to a remote endpoint when a remote queue is already active caused an unprompted, destructive overwrite of the remote queue. Simultaneously, if local playback was active, both local and remote audio pipelines could emit audio concurrently, violating the single active playback authority invariant.
* **Root Cause:**
  1. `PlaybackSessionManager` lacked an authoritative confirmation gate (`RemoteQueueConfirmationPolicy`) before issuing destructive `REPLACE` queue commands to remote hosts.
  2. The local ExoPlayer session was not unconditionally paused and surrendered when remote playback authority was asserted.
* **Resolution:**
  - Implemented `RemoteQueueConfirmation` domain policy with `ConfirmationRequired`, `ConfirmReplace`, `ConfirmAppend`, and `Dismiss` actions.
  - Mandated user confirmation whenever the destination queue has active items.
  - Bound single active playback authority: when `activeEndpoint != Local`, local ExoPlayer is explicitly paused and hardware focus is released.
* **Files Modified:**
  - `app/src/main/java/org/michimusic/mobile/playback/RemoteQueueConfirmation.kt`
  - `app/src/main/java/org/michimusic/mobile/playback/PlaybackSessionManager.kt`
* **Test Verification:**
  - `app/src/test/java/org/michimusic/mobile/playback/RemoteQueueConfirmationTest.kt` (6 tests)
  - `app/src/test/java/org/michimusic/mobile/playback/PlaybackSessionManagerTest.kt` (12 tests)

---

### K48-02: AirCat File Checksum Verification Race & Temp File Inversion

* **Severity:** HIGH
* **Symptom:**
  Transferred audio files via AirCat could fail integrity verification or produce corrupt files due to calculating the SHA-256 hash before flushing stream buffers to the temporary file, or hashing the empty destination file rather than the completed temporary download.
* **Root Cause:**
  In `LinkTransferManager.kt`, the hashing stream was closed after attempting to verify file length against the target directory, causing a race condition where cached OS filesystem blocks were uncommitted during cryptographic hash generation.
* **Resolution:**
  - Re-ordered file stream operations in `LinkTransferManager.kt`:
    1. Write payload directly to `.tmp` file.
    2. Flush and close writing channel.
    3. Calculate SHA-256 on the finalized `.tmp` file.
    4. Validate checksum against manifest declaration.
    5. Atomic rename `.tmp` $\to$ final destination.
* **Files Modified:**
  - `michi-link-client/src/main/java/org/michimusic/link/LinkTransferManager.kt`
* **Test Verification:**
  - `michi-link-client/src/test/java/org/michimusic/link/AirCatContractTest.kt` (`transferWithCorruptItem_failsIntegrityCheck`, `transferWithEmptySha256OnItem_completesAsUnverifiedChecksum`)

---

### K48-03: AirCat Manifest Validation Vulnerabilities (Negative Sizes, Overflow, Bad Hashes)

* **Severity:** HIGH
* **Symptom:**
  Malicious or malformed remote AirCat manifests could trigger integer overflow in total byte calculations, accept invalid SHA-256 strings (such as non-hex characters or truncated hashes), allow duplicate track IDs, or allow negative track file sizes.
* **Root Cause:**
  `AirCatTransferClient.kt` performed only coarse-grained checks (`manifest.tracks.isNotEmpty()`) without validating track-level integrity invariants before allocating transfer jobs.
* **Resolution:**
  Added strict pre-transfer validation in `AirCatTransferClient.validateManifest()`:
  - Regex validation `^[a-fA-F0-9]{64}$` for all SHA-256 fields.
  - Rejection of negative track file sizes (`sizeBytes < 0`).
  - Detection and rejection of duplicate track IDs.
  - Safe 64-bit accumulator check to prevent byte sum overflow.
  - Legitimate empty-transfer detection returning clean zero-byte result rather than crash.
* **Files Modified:**
  - `michi-link-client/src/main/java/org/michimusic/link/AirCatTransferClient.kt`
* **Test Verification:**
  - `michi-link-client/src/test/java/org/michimusic/link/AirCatContractTest.kt` (5 new adversarial manifest tests: `manifestWithInvalidSha256_isRejectedBeforeTransfer`, `manifestWithNegativeTrackSize_isRejected`, `manifestWithDuplicateTrackIds_isRejected`, `manifestWithByteSumOverflow_isRejected`, `manifestWithZeroTracks_completesWithZeroBytes`)

---

### K48-04: AirCat Transfer Offered to Ineligible Endpoints (Michi Stream, USB DAC, Revoked)

* **Severity:** MEDIUM
* **Symptom:**
  The AirCat sync shortcut in `DevicesScreen` allowed initiating an AirCat sync session to hardware endpoints that cannot receive files (e.g., Michi Stream I2S hardware DACs, local USB DAC dongles, or revoked peers), resulting in confusing connection timeouts and unhandled network errors.
* **Root Cause:**
  `DevicesScreen.kt` passed all discovered endpoints directly to the AirCat picker dialog without domain policy filtering.
* **Resolution:**
  - Created dedicated domain policy `AirCatEligibilityPolicy.kt`:
    - Checks device capability `AIRCAT_RECEIVER` / `FILE_TRANSFER`.
    - Explicitly rejects `deviceType == STREAM_RECEIVER` (Michi Stream is audio-only I2S/PCM).
    - Explicitly rejects `deviceType == USB_DAC` or `AUDIO_OUTPUT`.
    - Explicitly rejects endpoints with `SyncConnectionState.REVOKED` or `UNAUTHORIZED`.
  - Filtered picker list in `DevicesScreen.kt` via `AirCatEligibilityPolicy.filterEligibleDevices`.
* **Files Modified:**
  - `app/src/main/java/org/michimusic/mobile/devices/aircat/AirCatEligibilityPolicy.kt`
  - `app/src/main/java/org/michimusic/mobile/ui/screens/DevicesScreen.kt`
* **Test Verification:**
  - `app/src/test/java/org/michimusic/mobile/devices/aircat/AirCatEligibilityPolicyTest.kt` (9 unit tests covering all device categories)

---

### K48-06: Signal Truth Borrowed Freshness & Provenance Telemetry Leak

* **Severity:** HIGH
* **Symptom:**
  When playing to a remote endpoint, the Signal Truth sheet displayed local Android ExoPlayer latency and observation freshness timestamps, creating false bit-perfect and false latency claims for a remote receiver pipeline.
* **Root Cause:**
  `SignalTruthResolver.kt` fell back to `localObservationFreshness` when `remoteFreshness` was not explicitly provided by the remote protocol payload.
* **Resolution:**
  - Hardened `SignalTruthResolver.kt`:
    - Remote playback authority strictly sets `freshnessMs = 0L` (unobserved directly by local kernel).
    - Provenance badge clearly delineates `LOCAL_DECODING` vs `REMOTE_HOST_DECLARED` vs `STREAM_RECEIVER_OBSERVED`.
    - Zero fake measurements: no interpolated DSD/DoP or fake bit-perfect certifications.
  - Aligned badge copy in `NowPlayingScreen.kt` with the Signal Truth bottom sheet.
* **Files Modified:**
  - `app/src/main/java/org/michimusic/mobile/signaltruth/SignalTruthResolver.kt`
  - `app/src/main/java/org/michimusic/mobile/ui/screens/NowPlayingScreen.kt`
* **Test Verification:**
  - `app/src/test/java/org/michimusic/mobile/signaltruth/SignalTruthResolverTest.kt` (`remotePlayback_neverBorrowsLocalObservationFreshness_reportsZeroMs`)

