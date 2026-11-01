# KILLCRITIC Evidence Matrix — M48 AERO PR7

**Target Repository:** `pitydah/michi-music-mobile`  
**Target Branch:** `integration/m46d-aero-premium`  
**PR:** #7  
**Date:** 2026-10-31  

---

## 1. Compliance & Evidence Traceability

| Requirement / Directive ID | Architectural Constraint | Implementation File(s) | Automated Test File & Case | Status |
|---|---|---|---|---|
| **REQ-AUTH-01** (K47-01) | Queue playback operations must be preemptible via cancellation token tracking; no split-brain playback. | `PlaybackSessionManager.kt` | `PlaybackSessionManagerTest.kt`<br>• `queuePlaybackPreemption_cancelsPriorRequest`<br>• `queuePlayback_concurrentPreemption_keepsOnlyWinner` | **PASS** |
| **REQ-AUTH-02** (K47-06) | Remote queue transfer requires 3-way handshake verification before committing authority. | `RemoteQueueConfirmation.kt`<br>`PlaybackSessionManager.kt` | `RemoteQueueConfirmationTest.kt`<br>• `confirmedHandshake_grantsAuthority`<br>• `rejectedOrTimedOutHandshake_retainsLocalAuthority` | **PASS** |
| **REQ-SIG-01** (K47-02) | Remote Signal Truth must NEVER inherit local Android audio pipeline observation. | `SignalTruthResolver.kt` | `SignalTruthResolverTest.kt`<br>• `remotePlayback_neverInheritsLocalSourceFormat`<br>• `remotePlayback_withoutMetadata_returnsUnknownDeclared` | **PASS** |
| **REQ-SIG-02** (K47-03) | Zero fabricated measurements: invalid sample rate/bit depth must evaluate to `UNKNOWN ("No observado")`, never defaulting to 16/44.1. | `SignalTruthResolver.kt` | `SignalTruthResolverTest.kt`<br>• `invalidSampleRateOrBitDepth_isUnknown_neverDefaultsTo16_44` | **PASS** |
| **REQ-SIG-03** (K47-03) | Freshness timestamp must strictly derive from `observation.observedAtMs`, never `System.currentTimeMillis()`. | `SignalTruthResolver.kt` | `SignalTruthResolverTest.kt`<br>• `freshness_comesFromObservationTimestamp_neverInventedSystemCurrentTime` | **PASS** |
| **REQ-SIG-04** | Signal Truth must honestly disclaim physical DAC verification without claiming bit-perfect / DSD / DoP. | `SignalTruthResolver.kt`<br>`SignalTruthModal.kt` | `SignalTruthTest.kt`<br>Emulator verification (`emulator_signal_truth_sheet.png`) | **PASS** |
| **REQ-REM-01** (K47-04) | Remote UI connection state must reactively reflect true network state (`CONNECTING`, `CONNECTED`, `DISCONNECTED`, `ERROR`). | `RemoteViewModel.kt`<br>`RemoteScreen.kt` | `RemoteViewModelTest.kt`<br>• `connectionState_reflectsSyncManagerStates_exhaustively`<br>• `connectionState_unmappedOrDisconnectedEndpoint_isDisconnected` | **PASS** |
| **REQ-AIR-01** (K47-05) | AirCat sync shortcut must provide modal target selector (`AirCatTargetPicker`) when multiple targets exist, or honest alert when 0 targets exist. | `DevicesScreen.kt` | `AirCatSafeLayoutTest.kt`<br>Emulator verification (`emulator_aircat_dialog.png`) | **PASS** |
| **REQ-AIR-02** (K47-05) | AirCat completion text must ONLY claim SHA-256 and library commitment if `checksumVerified && libraryCommitted` are true. | `AirCatModels.kt`<br>`AirCatTransferClient.kt`<br>`SyncComponents.kt` | `AirCatSafeLayoutTest.kt`<br>• `completedView_requiresBothChecksumAndLibraryFlagsForVerifiedMessage`<br>• `completedView_missingChecksumOrLibrary_showsUnverifiedNotice` | **PASS** |
| **REQ-AIR-03** | AirCat is exclusive to Devices tab; forbidden from Home, Library, or Settings navigation. | `MichiNavHost.kt`<br>`NavigationPresentationPolicy.kt` | `ComposeNavigationIntegrationTest.kt` | **PASS** |
| **REQ-COV-01** (K47-07) | CoverFlow pager key and page lookups must be bounds-safe for 0, 1, and 50+ albums under dataset modifications. | `MichiCoverFlowHost.kt` | `CoverFlowHostTest.kt`<br>Emulator verification (`emulator_albums_coverflow.png`) | **PASS** |
| **REQ-UI-01** | Pure icon-only transport controls: zero background discs, rings, bubbles, or pills under pictograms. | `NowPlayingScreen.kt`<br>`MiniPlayer.kt`<br>`RemoteScreen.kt` | `AudioPurityInvariantTest.kt`<br>`NowPlayingScreenTest.kt`<br>Emulator screencap inspection | **PASS** |
| **REQ-UI-02** | Hitbox ergonomics: minimum $\ge 48\times 48\text{ dp}$ for all controls; play/pause hero button $64-72\text{ dp}$. | `NowPlayingScreen.kt`<br>`MiniPlayer.kt`<br>`RemoteScreen.kt` | `RemoteScreenTest.kt`<br>`NowPlayingScreenTest.kt`<br>Hierarchy dump verification | **PASS** |
| **REQ-UI-03** | No volume sliders in NowPlaying or MiniPlayer. Master hardware volume reserved for OS volume rockers. | `NowPlayingScreen.kt`<br>`MiniPlayer.kt`<br>`RemoteScreen.kt` | `NowPlayingNoVolumeTest.kt`<br>• `testNowPlayingScreenDoesNotContainVolumeSlider`<br>• `testMiniPlayerDoesNotContainVolumeSlider`<br>• `testRemoteScreenDoesNotContainVolumeSlider` | **PASS** |
| **REQ-SYS-01** | Pure audio-only architecture: zero video codecs, surfaces, or dependencies. | Entire repo | Build dependencies audit & AST inspection | **PASS** |
| **REQ-SYS-02** | Michi Stream is a preprovisioned PCM/I2S receiver; no physical pairing button assumed. | `michi-link-client`<br>`DeviceDiscovery.kt` | Link protocol inspection & discovery tests | **PASS** |

---

## 2. Test Execution Ledger

The full automated multi-module test suite passes with zero failures across all target modules:

```bash
./gradlew :core:testDebugUnitTest \
          :michi-link-client:testDebugUnitTest \
          :player:testDebugUnitTest \
          :data:testDebugUnitTest \
          :app:testNormalDebugUnitTest \
          :app:testFdroidDebugUnitTest
```

### Module Verification Results
- `:core`: **BUILD SUCCESSFUL** (100% tests pass)
- `:michi-link-client`: **BUILD SUCCESSFUL** (100% tests pass)
- `:player`: **BUILD SUCCESSFUL** (100% tests pass)
- `:data`: **BUILD SUCCESSFUL** (100% tests pass)
- `:app (normalDebug)`: **BUILD SUCCESSFUL** (100% tests pass, 54+ tests)
- `:app (fdroidDebug)`: **BUILD SUCCESSFUL** (100% tests pass, 54+ tests)
