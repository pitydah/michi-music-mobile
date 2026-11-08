# UI3.2 Pending Corrections Report

## Candidate and boundaries

- Exact base: `88f707a6123e06f1bfb2650af77b1dc87e13a2c4`.
- Local branch: `fix/ui3-2-pending-corrections`.
- Approved plan: `.scratch/to-plan/ui3-2-pending-corrections.md`.
- Gradle evidence workflow: `8bb93a3cdcb9b93dc437925bcb77cd51`.
- Publication target: draft PR #11. Merge and release are not authorized.

This correction preserves UI3.1 codec truth, source-aware MediaStore numbering,
playback, Signal Truth, AirCat, Michi Link/Stream and Album/Artist Enrichment.
It does not change the audio engine or persistent protocol models.

## Corrections

### P0 — restored CoverFlow

The album selector again exposes three equal presentations in the exact cycle
Mosaic → CoverFlow → List → Mosaic, with Mosaic as default. The existing
`MichiCoverFlowHost` remains the interaction authority: side covers select and
center, while only a settled central cover opens detail; a cover never starts
playback.

`AlbumCoverFlowPolicy` maps scoped albums to stable opaque
`albumNavigationIdentity` values and excludes ambiguous duplicate identities.
Selection is saveable per active library and reconciled after reorder, rescan or
removal. Grid/list states are also scoped so one library cannot inherit another
library's invalid position.

The complete CoverFlow surface is restored: cover stage, position cue, album
facts, Play/Shuffle/Add actions and canonical interactive track list. A shared
production selector/content switch requires all three mode slots, and its
Compose test exercises the full cycle.

### P1 — one canonical album order

`canonicalAlbumTracks()` and `canonicalPlayableAlbumTracks()` derive from the
UI3.1 source-aware disc/track policy. Detail, List and CoverFlow now share that
order for rows, queues, requested-track starts and playlist insertion, filtering
live `scoped.playableTrackIds` only after ordering. Raw MediaStore values, IDs and
track objects remain unchanged.

### P1 — compact detail heading

The track section heading is exactly `Pistas`. The count, A–Z action and all
title-sort state were removed. Canonical disc/track order, multidisc headers,
per-track favorite and menu actions remain.

### P1/P2 — bottom-sheet ghost and modal exclusivity

The displaced rectangle came from applying a border to the full-window
`ModalBottomSheet` root. `MichiBottomSheet` now passes the caller modifier
unchanged while preserving shape, opaque fill, elevation, drag handle, scrim,
gestures, Back and accessibility behavior.

Album detail now owns one nullable `AlbumDetailModal` instead of four independent
booleans. Menu → quality/history and playlist transitions therefore compose at
most one album sheet and one scrim.

### Follow-up — playlist availability, reload continuity and 200% text

Album detail now derives Add-to-list enablement, explanatory unavailable copy
and the submitted payload from the same canonical `eligibleTracks` set. An
album with registered but unavailable tracks cannot open or submit an empty
playlist modal, and a rescan that empties eligibility closes an already-open
modal safely.

CoverFlow keeps snapshot-specific navigation identities unchanged and adds a
separate opaque continuity key that excludes track membership. A transient
empty refresh retains the pending selection; repopulation can rebind a changed
snapshot identity only through a unique continuity key. Settled empty results,
confirmed deletion and ambiguous continuity still fail closed.

At 360dp and 200% text the album hero stacks, allowing the complete long title
up to five lines, while the Dock becomes two rows of two destinations. Full
Spanish labels, semantics and 48dp targets are preserved without shrinking the
requested font scale. Normal and wide configurations retain their original
horizontal layouts.

## Principal files

- `app/src/main/java/org/michimusic/mobile/ui/screens/AlbumsScreen.kt`
- `app/src/main/java/org/michimusic/mobile/ui/screens/AlbumViewModePolicy.kt`
- `app/src/main/java/org/michimusic/mobile/ui/screens/AlbumViewModeComponents.kt`
- `app/src/main/java/org/michimusic/mobile/ui/screens/AlbumCoverFlowPolicy.kt`
- `app/src/main/java/org/michimusic/mobile/ui/screens/AlbumTrackPositionPolicy.kt`
- `app/src/main/java/org/michimusic/mobile/ui/screens/AlbumDetailScreen.kt`
- `app/src/main/java/org/michimusic/mobile/ui/screens/AlbumDetailUiPolicy.kt`
- `app/src/main/java/org/michimusic/mobile/ui/components/premium/MichiBottomSheet.kt`

## Automated verification

All Gradle commands used the required compact-output wrapper.

| Wrapper log | Gate | Result |
|---|---|---|
| `0001.log` | Focused RED contracts | Expected unresolved-policy compile failures |
| `0002.log` | Initial mode/order/detail/CoverFlow/playback tests | PASS |
| `0003.log` | Normal debug assembly | PASS |
| `0004.log` | Initial `test lint` + four APK variants | PASS |
| `0005.log` | Initial API 36 connected suite | PASS; 80 finished, 8 skips, 0 failed |
| `0006.log` | CoverFlow identity/host integration repair | PASS |
| `0007.log` | New mode-component test compile | Expected test-API compatibility failure; corrected immediately |
| `0008.log` | Production selector/content integration + assembly | PASS, 121 tasks |
| `0009.log` | Final `test lint` + Normal/F-Droid debug/release | PASS, 493.5s |
| `0010.log` | Final API 36 connected suite | PASS; 80 finished, 8 skips, 0 failed |

Follow-up workflow `13ebd2ae2ef16df705d0548b5f8a6c98`:

| Wrapper log | Gate | Result |
|---|---|---|
| `0001.log` | Playlist availability RED | Expected missing-policy compile failure |
| `0002.log` | Playlist policy GREEN | PASS, 92 tasks |
| `0003.log` | CoverFlow continuity RED | Expected missing-policy compile failure |
| `0004.log` | CoverFlow continuity GREEN | PASS, 92 tasks |
| `0005.log` | Hero/Dock responsive RED | Expected missing-policy compile failure |
| `0006.log` | Hero/Dock responsive GREEN | PASS, 92 tasks |
| `0007.log` | Integrated affected tests + Normal debug | PASS, 121 tasks |
| `0008.log` | Final `test lint` + Normal/F-Droid debug/release | PASS, 441.8s |
| `0009.log` | Final API 36 connected suite | PASS; 80 finished, 10 designed live-test skips, 0 failed |

Additional checks:

- `git diff --check`: PASS.
- No `sortByTitle`, `SortByAlpha` or `Pistas (` remains in affected screens.
- No root `.border(` remains in `MichiBottomSheet.kt`.
- `bash scripts/verify_michi_opencode_skills.sh --check`: PASS manifest format;
  independent review still owns inspection of original native skill calls.
- `ui3_2.patch`: 37-file binary patch. In a detached clean worktree pinned to
  the exact base, `git apply --check --whitespace=error` passed, the patch
  applied, and all 37 patched files matched the delivery tree byte-for-byte.

## Independent review

Read-only review session `ses_e47769d0cffenbyiLlXHMwSQq3` initially returned
`fix-first` because restored CoverFlow was not connected to a sensitive
production integration seam. The repair extracted stable identity mapping,
added policy+host interaction coverage, made ambiguous-only CoverFlow fail
closed, and introduced the production `AlbumViewModeToggle` plus exhaustive
`AlbumPresentationContent` used directly by `AlbumsScreen`.

The final test drives the real selector through Mosaic → CoverFlow → List →
Mosaic and asserts each required content slot. Removing CoverFlow from the enum,
cycle, icon mapping, dispatcher or `AlbumsScreen` slot now causes a compile or
test failure. Final reviewer verdict: `ship`; no unresolved P0/P1 findings.

Fresh follow-up review session `ses_e472c5102ffejZw8LUjWTM8aMS` inspected the
playlist eligibility/payload contract, snapshot identity plus continuity-key
reconciliation, stacked 200% hero and semantic two-by-two Dock. It confirmed
defense-in-depth against empty playlist submissions, fail-closed identity
behavior, unchanged playback/navigation and ≥48dp Spanish navigation targets.
Final verdict: `ship`; no new blocking finding.

## P0/P1/P2 matrix

| Priority | Requirement | Status | Evidence |
|---|---|---|---|
| P0 | CoverFlow restored as third equal mode | PASS | production Compose test + API 36 capture |
| P0 | Side select / settled center open / no cover autoplay | PASS | host integration tests |
| P0 | Stable identity and rescan/removal reconciliation | PASS | policy/host tests |
| P1 | Heading exactly `Pistas`; no count/A–Z/title sort | PASS | policy + real capture |
| P1 | Ghost rectangle removed | PASS_LOCAL | source fix + before/after captures |
| P1 | Canonical live order across Detail/List/CoverFlow | PASS | unit/integration tests |
| P2 | One active album sheet/scrim | PASS | exhaustive state policy + real transition |
| P2 | Library-scoped scroll/selection | PASS | keyed/saveable state + reconciliation tests |
| P2 | Reduced motion | PASS_AUTOMATED | motion/geometry tests |
| P1 | Playlist action matches live eligible payload | PASS_AUTOMATED | policy tests; no unavailable device fixture |
| P2 | CoverFlow survives transient empty reload and membership changes | PASS_AUTOMATED | continuity-policy tests |
| P2 | 360×800 at 200% preserves long title and full Dock labels | PASS_API36_EMULATOR | policy/Compose tests + real capture |
| P2 | Physical TalkBack/OEM/tablet/320 visual sign-off | BLOCKED_HARDWARE | unavailable locally |

The complete evidence matrix is in `evidence/ui3_2/README.md`.

## Status and limits

- `UI3_2_CODE_GO = PASS_LOCAL`
- `UI3_2_VISUAL_GO = PASS_API36_EMULATOR`
- `INDEPENDENT_REVIEW = SHIP`
- `PHYSICAL_ACCESSIBILITY_SIGNOFF = BLOCKED_HARDWARE`
- `REMOTE_CI_GO = PENDING_FOLLOWUP_PUSH`
- `RELEASE_GO = NOT_AUTHORIZED`

At font scale 2.0 the album actions remain usable and the first track stays
below the fold as allowed; the follow-up stacked hero and two-by-two Dock keep
the complete long title and all full navigation labels visible. No new real
320dp/tablet capture, physical TalkBack run or OEM-device certification was
available. The unavailable-playlist and transient-refresh cases are automated
only because the emulator data did not contain those fixtures.
