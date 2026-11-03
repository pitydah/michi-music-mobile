# PERF-06 / PERF-07 — Measurement scenarios (prepared, not measured)

Status: **PREPARED_ONLY**. No number in this document was produced by a run. The
performance contract (`R16_4_PERFORMANCE_IMPLEMENTATION_CONTRACT.md`) requires
before/after evidence on a physical device before `PERFORMANCE_GO`; this file is the
reproducible script for producing that evidence, not the evidence itself.

## Why these two remain open

| Ticket | Subject | Blocker |
|---|---|---|
| PERF-06 | CoverFlow and Now Playing frame pacing | Needs Macrobenchmark + Perfetto on a physical device |
| PERF-07 | Long pause / resume session behaviour | Needs a physical device and a real audio route |

`PERF-05` (search cost) is closed: `SearchBenchmarkHarnessTest` reports 1 ms / 2 ms /
9 ms for 1k / 10k / 50k tracks through the same matching path production uses. The
result and its reopening conditions live in the performance contract.

## Measurement matrix (applies to both tickets)

Every scenario below must be repeated on each row that the team can provision. A run
that only covers the emulator does not close PERF-06/07; the emulator is a smoke test.

| Axis | Values | Why it matters here |
|---|---|---|
| Refresh rate | 60 Hz, 90 Hz, 120 Hz | CoverFlow and the Aurora bar are per-frame visuals; a fixed budget hides work that only tears at 120 Hz |
| Display width | 320, 360, 393, 412 dp | The editorial hero and the dock change layout at 330/340 dp |
| Font scale | 100 %, 150 %, 200 % | Large text changes line wrapping and therefore re-layout cost |
| Contrast | normal, high contrast | High contrast swaps colour tokens and disables shadows |
| Motion | normal, reduced motion | Reduced motion must remove animation work, not just hide it |
| Accessibility | TalkBack off / on | Semantics merging and traversal cost are part of the contract |
| Build | release (benchmark), debug (StrictMode + tracing) | Release gives the frame numbers; debug gives the disk/network violations |

Record for every run: device model, API level, refresh rate, `wm size`, `wm density`,
`settings get system font_scale`, build type and the exact commit SHA.

## PERF-06 — frame pacing of CoverFlow and Now Playing

Repeat each scenario 5 times and report median plus worst case; discard the first cold
run of the device boot, never the first run of the app.

### 6.A Cold start to Home

1. Force-stop the app, clear logcat.
2. `adb shell am start -W -n org.michimusic.mobile.debug/org.michimusic.mobile.MainActivity`.
3. Record `TotalTime`, `WaitTime` and the first-frame `FrameTimingMetric`.

Acceptance: no frame above 32 ms at 60 Hz (16.6 ms at 120 Hz), total start within the
baseline recorded on the same device before the change.

### 6.B Library CoverFlow scroll

1. Open Library, switch to CoverFlow, scroll one full cycle at a steady pace, then
   scroll back.
2. Run the same cycle with 1k, 10k and 50k tracks when the harness supports it.

Evidence: `FrameTimingMetric` p50/p90/p95/p99, jank percentage, one Perfetto trace per
size, and `dumpsys gfxinfo <package> framestats` for the same window.

Acceptance: no regression against the recorded baseline; no frame above 32 ms in the
smooth-scroll segment; artwork never blanks mid-scroll.

### 6.C Now Playing and the Aurora bar

1. Open Now Playing with a playing track, let the Aurora progress bar run 30 s.
2. Open and close the Signal Truth sheet twice, then the Michi Control menu twice.
3. Repeat with `reducedMotion = true` and with high contrast.

Evidence: `FrameTimingMetric` for the 30 s window, one Perfetto trace per open/close
cycle, recomposition counts for `NowPlayingScreen` and `GradientProgressBar`.

Acceptance: the progress bar does not write snapshot state back into composition
(`compose-performance` defers that read to draw); no frame above 32 ms while the sheet
animates; reduced motion produces strictly less work than the animated path.

### 6.D Artwork loading

1. Scroll the library quickly with the largest available artwork (the embedded cache
   caps a single image at 25 MiB; use a file near that cap).
2. Repeat after `adb shell am send-trim-memory <package> RUNNING_LOW` and `COMPLETE`.

Evidence: `dumpsys meminfo <package>` before/after each pass, image cache sizes from
Coil's memory cache and the embedded LRU, and any `OutOfMemoryError` in logcat.

Acceptance: memory returns to the pre-scroll band after the trim, no duplicate decoding
of the same thumbnail, and no image flashes back to the placeholder.

### 6.E Recomposition budget

1. Capture Compose compiler metrics (`-P plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=...`)
   for `:app`.
2. Use Layout Inspector's recomposition counts on the three screens above.

Acceptance: no composable in CoverFlow/Now Playing/Home recomposes on every frame, and
no unstable parameter is introduced where the compiler previously reported stable.

## PERF-07 — long pause and resume

### 7.A Pause windows

1. Start playback on a physical route (wired headset preferred; USB DAC when available;
   Bluetooth when the codec is confirmed).
2. Pause for **5, 30 and 60 minutes** with the screen off, one run each; add a 120 minute
   run only when the first three are clean.
3. Resume and measure time-to-first-audio, wake-ups and battery drain for the window.

### 7.B CPU, memory and network while paused

For each window capture: `dumpsys cpuinfo`, `dumpsys meminfo <package>`,
`dumpsys netstats detail` and a 60 s Perfetto trace started 2 minutes before resume.

Acceptance: no sustained CPU above the idle band, no growing memory, and no polling
traffic while paused with SSE connected (`RemotePollingPolicy.nextDelayMs` returns
`null` in that case; a request in the trace is a defect).

### 7.C Foreground service and process survival

1. Confirm `dumpsys activity services org.michimusic.mobile.debug` still lists
   `MichiPlaybackService` while paused, and that the notification is still posted.
2. Kill the process from the background (`adb shell am kill`) and reopen: the queue and
   the endpoint must be restored (this is the A3 rotation/background scenario too).

### 7.D Route changes while paused

1. While paused, plug or unplug the headset, then connect and disconnect a USB DAC.
2. Resume and record the route reported by `dumpsys audio` against the route shown in
   Now Playing and in Signal Truth.

Acceptance: resume reaches `PLAYING` in under 1 s, no wakelock is held while paused, no
silent fallback to the speaker, and the displayed route matches the observed one. A
mismatch is a Signal Truth defect, not a performance one.

## What must not happen

Do not mark PERF-06/07 as implemented because this document exists. They stay
`SPEC_ONLY` until a physical run produces the numbers above with the matrix recorded.
Do not substitute emulator numbers for physical ones, and do not report a percentage
improvement without the before and after runs on the same device.
