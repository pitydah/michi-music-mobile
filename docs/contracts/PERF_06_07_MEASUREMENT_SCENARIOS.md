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

`PERF-05` (search cost) is now measurable on the JVM and reports 1 ms / 2 ms / 9 ms for
1k / 10k / 50k tracks through `SearchBenchmarkHarnessTest`; that number is the baseline
the contract asked for before choosing an index, pagination or FTS.

## PERF-06 — frame pacing of CoverFlow and Now Playing

Scenario (repeat 5 times, report median and worst frame):

1. Cold start to Home, wait for the artwork to settle.
2. Open Library, switch to CoverFlow, and scroll one full cycle at a steady pace.
3. Open Now Playing and let the Aurora progress bar run for 30 s while the sheet is
   closed, then open and close the Signal Truth sheet twice.

Commands (physical device, debuggable build not required for Macrobenchmark):

```sh
./gradlew :app:assembleNormalRelease
./gradlew :benchmark:connectedCheck   # once a benchmark module exists
adb shell atrace --async_start -c gfx view sched freq
```

Evidence to capture: `FrameTimingMetric` output, a Perfetto trace per scenario, and the
jank percentage per scenario. Acceptance for a future `PERFORMANCE_GO`: no jank increase
against the recorded baseline and no frame above 32 ms in the scroll cycle.

## PERF-07 — long pause and resume

Scenario:

1. Start playback on a physical route (wired headset preferred; USB DAC when available).
2. Pause for 5, 30 and 120 minutes with the screen off, one run each.
3. Resume, then measure time-to-first-audio, wake-ups and battery drain for the window.

What to record: `dumpsys batterystats` before/after, `dumpsys audio` route at resume,
`adb shell dumpsys media_session` state, and logcat filtered by the playback service tag.
Acceptance for a future `PERFORMANCE_GO`: resume reaches `PLAYING` in under 1 s, no
wakelock held while paused, and no silent fallback to the speaker route.

## What must not happen

Do not mark PERF-06/07 as implemented because this document exists. They stay
`SPEC_ONLY` until a physical run produces the numbers above.
