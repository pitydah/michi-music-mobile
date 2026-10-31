# Visual & Functional Diff: Baseline vs M49 AERO FINAL 100

**Auditor:** Senior UI/UX Design Director & KILLCRITIC Release Auditor  
**Milestone:** M49 — AERO FINAL 100  
**Branch:** `integration/m46d-aero-premium`  

---

## 1. Visual Comparison Overview

```
Before (Baseline / Legacy M46):
┌────────────────────────────────────────────────────────┐
│ NowPlaying:                                            │
│   [⏮]   ( ( ▶ ) )   [⏭]  <-- Solid circular disc / pill│
│   ───[=========O────]───  <-- In-app volume slider      │
│                                                        │
│ Devices:                                               │
│   [ Michi Stream I2S ] -> [ Sincronizar con AirCat ]  │
│   (Invalid! Hardware DAC has no local disk storage)    │
└────────────────────────────────────────────────────────┘

After (M49 AERO FINAL 100):
┌────────────────────────────────────────────────────────┐
│ NowPlaying:                                            │
│        ⏮        ▶        ⏭    <-- Pure icon-only!      │
│   (No discs, no rings, no pills, 48-64dp touch hitboxes)│
│   (Volume slider completely removed; delegated to HW)  │
│                                                        │
│ Devices:                                               │
│   AirCat filtered strictly by AirCatEligibilityPolicy: │
│   - Desktop Server: ELIGIBLE                           │
│   - Michi Stream: EXCLUDED (Audio-only PCM receiver)   │
│   - USB DAC: EXCLUDED (Audio-only peripheral)          │
└────────────────────────────────────────────────────────┘
```

---

## 2. Component-by-Component Diff

### A. Transport Controls (NowPlaying, MiniPlayer, Remote)
* **Baseline:** Some controls rendered with colored background circles (`CircleShape`), border halos, or pill-shaped containers behind icons.
* **M49 Aero 100:**
  - Removed all background decorators, borders, and tinted containers from pictograms.
  - Transport glyphs float cleanly on the dark obsidian background.
  - Minimum touch target $\ge 48\times 48\text{ dp}$ enforced via invisible padding (`MichiSpacing.minTouchTarget`).
  - Hero Play/Pause rendered at $64\text{ dp}$ with ripple bounded to icon silhouette.

### B. Volume Handling (NowPlaying & MiniPlayer)
* **Baseline:** Included on-screen slider manipulating local software volume, creating redundant volume stages with system hardware keys.
* **M49 Aero 100:**
  - Volume sliders completely removed from NowPlaying and MiniPlayer.
  - Local volume delegated exclusively to Android hardware rocker buttons.
  - Remote volume isolated to dedicated remote session parameters within `RemoteScreen`.

### C. AirCat Sync & Device Selection
* **Baseline:** All discovered endpoints appeared in AirCat sync picker regardless of capabilities.
* **M49 Aero 100:**
  - Introduced `AirCatEligibilityPolicy`.
  - Michi Stream (hardware PCM receiver) and USB DACs are strictly omitted from file transfer dialogs.
  - Unverified or revoked peers are disabled and tagged with clear guidance text.

### D. Signal Truth Provenance & Honesty
* **Baseline:** Remote playback reported local pipeline observation freshness and latency figures.
* **M49 Aero 100:**
  - `SignalTruthResolver` isolates remote playback telemetry: sets `freshnessMs = 0L` when unobserved directly.
  - Audio badges explicitly declare `STREAM_RECEIVER_OBSERVED` vs `REMOTE_HOST_DECLARED` vs `LOCAL_DECODING`.
  - Zero fake measurements, no fake DSD/DoP claims.

### E. Remote Queue Protection (K48-01 / K48-05)
* **Baseline:** Switching playback to a remote target silently replaced its active queue.
* **M49 Aero 100:**
  - Interactive confirmation prompt appears whenever the target has active tracks.
  - User can choose to replace or append to the queue, or cancel without disruption.

