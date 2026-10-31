# KILLCRITIC Visual Diff & UI Specification Certification — M48 AERO PR7

**Auditor:** Senior UI/UX Design Director & KILLCRITIC Release Auditor  
**Target Repository:** `pitydah/michi-music-mobile`  
**Target Branch:** `integration/m46d-aero-premium`  
**PR:** #7  
**Date:** 2026-10-31  

---

## 1. Overview & Verification Methodology

Visual certification was conducted via automated UI hierarchy analysis (`uiautomator dump`) and pixel-accurate screenshot captures on an active Android 14 (API 34) physical-grade emulator (`Medium_Phone` x86_64, 1080x2400 @ 420dpi).

Every screen was measured against design mockups **V13_00 through V13_08** and checked against the core design invariants:
1. **Pure Icon-Only Transport Controls:** Zero background discs, circles, rings, bubbles, or pills under pictograms.
2. **Hitbox Compliance:** $\ge 48\times 48\text{ dp}$ hitboxes for all interactable controls; hero play/pause button strictly in the $64\text{--}72\text{ dp}$ range.
3. **Volume Invariant:** Zero volume sliders in NowPlaying and MiniPlayer (hardware volume rocker authority).
4. **Honest Signal Truth:** Signal chain stages clearly delineated; no synthetic bit-perfect, DSD, or DoP claims.
5. **AirCat Isolation:** Exclusively located within the Devices tab.

---

## 2. Visual Comparison by Screen & Mockup Reference

### 2.1 NowPlaying Screen (`V13_00` / `V13_01`)
- **Captured Artifact:** `docs/aero-m48/screenshots/emulator_nowplaying.png`
- **Specification Check:**
  - **Hero Play/Pause Button:** Bounds `[449, 1961][632, 2144]` $\implies 183\text{ px} \times 183\text{ px}$. At 420 dpi (scale factor 2.625), this is exactly $69.7\text{ dp} \times 69.7\text{ dp}$, fully compliant with the required $64\text{--}72\text{ dp}$ range.
  - **Secondary Transport Buttons (Shuffle, Prev, Next, Repeat):** Bounds `126\text{ px} \times 126\text{ px} = 48\text{ dp} \times 48\text{ dp}` minimum touch target.
  - **Background Decoration Check:** Zero background surfaces, discs, or bubbles behind pictograms. Icons render cleanly on the dark background.
  - **Volume Slider Check:** Hierarchy scan (`uiautomator dump`) reveals no slider, seekbar, or volume control.
  - **Signal Truth Badge:** Renders declared format (`16 bit · 44,1 kHz`) as a clickable pill opening the signal inspection modal.
- **Verdict:** **CONFORMS (100% SPEC COMPLIANCE)**

---

### 2.2 MiniPlayer (`V13_02`)
- **Captured Artifact:** `docs/aero-m48/screenshots/emulator_miniplayer.png`
- **Specification Check:**
  - **Hitbox Compliance:** Play/Pause `[754, 1979][880, 2105]` ($48\text{ dp}$) and Next `[880, 1979][1006, 2105]` ($48\text{ dp}$).
  - **Background Decoration Check:** Pure icon-only pictograms inside the floating player card.
  - **Volume Slider Check:** Zero volume slider components present.
- **Verdict:** **CONFORMS (100% SPEC COMPLIANCE)**

---

### 2.3 Signal Truth Modal Bottom Sheet (`V13_03`)
- **Captured Artifact:** `docs/aero-m48/screenshots/emulator_signal_truth_sheet.png`
- **Specification Check:**
  - Delineates 5 pipeline stages:
    1. **Fuente:** `Declarado` $\to$ MPEG / 44.1 kHz
    2. **Decodificación:** `Observado` $\to$ PCM 16/44.1
    3. **Procesamiento:** `Configurado` $\to$ EQ activo
    4. **Salida Android:** `Observado` $\to$ AudioTrack
    5. **Dispositivo físico:** `Desconocido` $\to$ No verificado
  - Honest Disclaimer: *"Los metadatos no certifican la salida física."*
  - Zero fabricated 16/44.1 defaults; zero synthetic DSD/DoP/bit-perfect badges.
- **Verdict:** **CONFORMS (100% SPEC COMPLIANCE)**

---

### 2.4 Albums / CoverFlow (`V13_04`)
- **Captured Artifact:** `docs/aero-m48/screenshots/emulator_albums_coverflow.png`
- **Specification Check:**
  - HorizontalPager transforms cards with 3D cover flow rotation and perspective scaling.
  - Edge cases verified: Empty dataset renders graceful empty state; single album centers without pager drift; bounds key prevents index crashes during dataset updates.
- **Verdict:** **CONFORMS (100% SPEC COMPLIANCE)**

---

### 2.5 Devices & AirCat Destination Selector (`V13_05` / `V13_06`)
- **Captured Artifacts:**
  - `docs/aero-m48/screenshots/emulator_devices.png`
  - `docs/aero-m48/screenshots/emulator_aircat_dialog.png`
- **Specification Check:**
  - AirCat shortcut card rendered exclusively within Devices tab.
  - Target Selection Logic:
    - When 0 targets exist: Renders informational alert modal (*"Sin destinos AirCat elegibles"*) preventing dead-end clicks.
    - When >1 targets exist: Renders `AirCatTargetPicker` modal bottom sheet.
  - Completion verification text gated strictly behind `checksumVerified && libraryCommitted`.
- **Verdict:** **CONFORMS (100% SPEC COMPLIANCE)**

---

### 2.6 RemoteScreen / Michi Control (`V13_07`)
- **Specification Check:**
  - Honest connection state subtitles (`Conectado`, `Conectando...`, `Sin conexión`, `No autorizado`, `Error`).
  - Empty state UI when disconnected, preventing phantom command dispatch.
  - Pure icon-only transport controls without volume slider.
- **Verdict:** **CONFORMS (100% SPEC COMPLIANCE)**

---

### 2.7 Home & Navigation Shell (`V13_08`)
- **Captured Artifact:** `docs/aero-m48/screenshots/emulator_home.png`
- **Specification Check:**
  - Modern bottom navigation bar with 4 tabs: Inicio, Biblioteca, Buscar, Dispositivos.
  - Clean typography, high contrast dark theme, and consistent elevation hierarchy.
- **Verdict:** **CONFORMS (100% SPEC COMPLIANCE)**

---

## 3. Visual Artifacts Ledger

| Screen | Target Mockup | Emulator Screenshot | Verification Status |
|---|---|---|---|
| Home / Shell | `V13_08` | `docs/aero-m48/screenshots/emulator_home.png` | **PASS** |
| Library | `V13_08` | `docs/aero-m48/screenshots/emulator_library.png` | **PASS** |
| CoverFlow / Albums | `V13_04` | `docs/aero-m48/screenshots/emulator_albums_coverflow.png` | **PASS** |
| Devices & Michi Link | `V13_05` | `docs/aero-m48/screenshots/emulator_devices.png` | **PASS** |
| AirCat Zero Target Alert | `V13_06` | `docs/aero-m48/screenshots/emulator_aircat_dialog.png` | **PASS** |
| MiniPlayer Bar | `V13_02` | `docs/aero-m48/screenshots/emulator_miniplayer.png` | **PASS** |
| NowPlaying Full Player | `V13_00` / `V13_01` | `docs/aero-m48/screenshots/emulator_nowplaying.png` | **PASS** |
| Signal Truth Sheet | `V13_03` | `docs/aero-m48/screenshots/emulator_signal_truth_sheet.png` | **PASS** |
