# Aero Screen Matrix & Design System Compliance (M49)

**Auditor:** Senior UI/UX Design Director & Jetpack Compose Architect  
**Milestone:** M49 — AERO FINAL 100  
**Branch:** `integration/m46d-aero-premium`  

---

## 1. Aero Design Core Directives

1. **Pure Icon-Only Transport Controls:** Zero background discs, circles, rings, bubbles, or filled pill containers behind pictograms (including the hero Play/Pause button). The pictogram floats directly over the Aero obsidian background with an invisible minimum touch target of $\ge 48\times 48\text{ dp}$ (hero Play/Pause $64\text{--}72\text{ dp}$).
2. **Zero Software Volume Sliders:** NowPlaying and MiniPlayer contain NO volume sliders. Volume is strictly controlled via physical hardware rockers on the Android device or via remote host audio settings.
3. **Canonical 4-Tab Navigation:** `Inicio`, `Biblioteca`, `Buscar`, and `Dispositivos`. AirCat file transfer is exclusive to the Devices tab.
4. **Surface Palette:**
   - Background Obsidian: `#0D0D0E`
   - Aero Graphite Raised: `#1A1A1E`
   - Cyan Accent: `#00E5FF`
   - Pure White Typography: `#FFFFFF`
   - Text Muted: `#8E8E93`

---

## 2. Comprehensive Screen Audit

| Screen / Component | File Location | Pure Icon Controls? | Min Touch Target | Volume Slider Removed? | Emulator Screenshot |
|---|---|---|---|---|---|
| **Home (`Inicio`)** | `HomeScreen.kt` | N/A (Cards & chips) | $\ge 48\times 48\text{ dp}$ | N/A | `emulator_home.png` |
| **Library List (`Biblioteca`)** | `AlbumsScreen.kt` | Yes (mode toggle) | $\ge 48\times 48\text{ dp}$ | N/A | `emulator_library_list.png` |
| **Library CoverFlow 3D** | `MichiCoverFlowHost.kt` | Yes (real 3D carousel) | $\ge 48\times 48\text{ dp}$ | N/A | `emulator_library_coverflow_real.png` |
| **MiniPlayer** | `MiniPlayer.kt` | **Yes (Pure Icon Play/Pause)** | $\ge 48\times 48\text{ dp}$ | **YES — Zero slider** | `emulator_miniplayer.png` |
| **NowPlaying (Playing)** | `NowPlayingScreen.kt` | **Yes (Hero Pause floats clean)** | $64\times 64\text{ dp}$ hero | **YES — Zero slider** | `emulator_nowplaying_playing.png` |
| **NowPlaying (Paused)** | `NowPlayingScreen.kt` | **Yes (Hero Play floats clean)** | $64\times 64\text{ dp}$ hero | **YES — Zero slider** | `emulator_nowplaying_paused.png` |
| **Signal Truth Sheet** | `NowPlayingScreen.kt` (`SignalTruthDialog`) | Yes (Icon buttons) | $\ge 48\times 48\text{ dp}$ | N/A | `emulator_signal_truth_sheet.png` |
| **Playback Queue** | `QueueScreen.kt` | Yes (drag/remove icons) | $\ge 48\times 48\text{ dp}$ | N/A | `emulator_queue.png` |
| **Search (`Buscar`)** | `SearchScreen.kt` | Yes (clear query) | $\ge 48\times 48\text{ dp}$ | N/A | `emulator_search.png` |
| **Devices (`Dispositivos`)** | `DevicesScreen.kt` | Yes (refresh/more icons) | $\ge 48\times 48\text{ dp}$ | N/A | `emulator_devices.png` |
| **AirCat Device Picker** | `DevicesScreen.kt` | Yes (AirCat shortcut) | $\ge 48\times 48\text{ dp}$ | N/A | `emulator_aircat_dialog.png` |
| **Remote (Disconnected)** | `RemoteScreen.kt` | Yes (back/more icons) | $\ge 48\times 48\text{ dp}$ | N/A | `emulator_remote_disconnected.png` |
| **Remote (Connected)** | `RemoteScreen.kt` | **Yes (Pure Icon Controls)** | $\ge 48\times 48\text{ dp}$ | N/A | `emulator_remote_connected.png` |
| **Settings** | `SettingsScreen.kt` | Yes (back/nav icons) | $\ge 48\times 48\text{ dp}$ | N/A | `emulator_settings.png` |

---

## 3. Real 3D CoverFlow Verification

The CoverFlow carousel in `MichiCoverFlowHost.kt` is implemented using pure Jetpack Compose graphics transformations:
- **Y-Axis 3D Rotation:** Rotates lateral covers along the Y-axis (`rotationY = -clamp(fraction * 38f, -42f, 42f)`).
- **Perspective Camera Distance:** Sets `cameraDistance = 16f * density` to prevent distortion while maintaining depth.
- **Scale and Elevation:** Central active album scales to $1.0\times$ while flanking items scale down to $0.85\times$.
- **Mirror Reflection Floor:** Computes inverted gradient reflection under the album carousel.
- **Integrated Track Explorer:** Selecting an album expands its tracklist directly below the CoverFlow carousel.

