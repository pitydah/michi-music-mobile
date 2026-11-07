# UI3.1 Sprint Report

## Candidate and scope

- Base: `qa/ui3-r19@47577c0b33efbadd90843d9880e173982b4db3fc`.
- Local branch: `fix/ui3-1-visual-icon-hardening`.
- Delivery mode: local incremental patch; no commit, push, PR, merge, or release.
- Approved plan: `.scratch/to-plan/ui3-1-visual-icon-hardening.md`.
- Gradle evidence workflow: `1b2489635a43c58c16e6b4f8f38af561`.

UI3 was refined in place. The patch does not reapply `ui3.patch`, change the
audio engine, or alter Michi Link, remote-control, AirCat, Signal Truth, Artist
Enrichment, or Album Enrichment authority.

## Root causes and corrections

### P0 — launcher icon

The adaptive foreground contained a complete rounded-square scene over a second
background and had no monochrome layer. The replacement uses a checked-in vector
master at `design/ui3_1/michi_launcher_symbol.svg`, preserving the ecosystem cat,
note, ears, and whiskers and reconstructing the phone from the verified base
artwork. The generator produces separate full-bleed background, transparent
foreground, tonal monochrome, and legacy square/round resources for every Android
density. The antialiased foreground is constrained inside the 66dp safe zone on
a 108dp canvas.

`scripts/verify_ui3_launcher.py` validates dimensions, opacity, safe bounds,
monochrome detail, legacy symbol presence, and a bad-fixture sensitivity check.
`scripts/render_ui3_launcher_evidence.py` renders exact-base/current comparisons
under circle, squircle, and rounded-square masks. Real API 36 Pixel Launcher
regular and themed-icon captures also pass.

### P1 — album library

The visible presentation cycle included CoverFlow and owned scroll state inside
the rendering branches. The selector now contains only Mosaic and List, Mosaic
remains the default, and caller-owned saveable grid/list states preserve the
visible position across detail navigation. Existing unrelated CoverFlow source
files were not deleted.

`AlbumMosaicGrid` continues to show square artwork, title, local year, artist,
and real local track count using stable scoped navigation identity.

### P1 — album detail and actions

`albumActionsStacked()` forced three full-width bars on ordinary phones. It was
replaced with an explicit one-row/two-row policy: Play is primary, Shuffle and
Add-to-list are compact 48dp+ icon actions, and very large fonts use at most two
rows. The hero is 112–144dp and the top bar keeps only Back, Favorite, and
overflow. Duplicate Add-to-list and history/info clutter were removed from the
overflow surface.

At 360×800 and normal font scale, the complete first track is visible without
scroll. At font scale 2.0 the actions still use only two rows, but the first track
is below the fold; this limitation is recorded rather than hidden.

### P1 — codec truth

The old policy uppercased a reduced MIME subtype, so `audio/mpeg` became the
misleading label `MPEG`. MediaStore ingestion now retains three runtime-only
facts on `Track`: full source MIME, normalized filename extension, and explicit
MediaStore track-number provenance. These fields are `@Transient`, so existing
wire/cache contracts are unchanged.

The centralized presentation policy ranks compatible extension/MIME/provider
evidence. Confirmed MP3 is `MP3`, `.mp2` is `MP2`, MIME-only MPEG is the cautious
`Audio MPEG`, and conflicting evidence makes no claim. FLAC, AAC, ALAC, WAV,
AIFF, Ogg, Opus, and WavPack are mapped explicitly. Mixed albums report
`Formatos mixtos`; albums with only unknown facts report no codec. The same
policy feeds hero, rows, and the `Formato de archivo` sheet without making DAC
or effective-output claims.

### P1 — MediaStore track positions

Raw `MediaStore.Audio.AudioColumns.TRACK` was rendered directly. A source-aware
pure policy now decodes only values explicitly marked as MediaStore-origin:
`1006` becomes disc 1/track 6 and `2002` becomes disc 2/track 2. Ordinary values,
invalid values, and non-MediaStore sources are handled conservatively. Raw
values, track IDs, and playback objects are unchanged. Sorting is stable and
multidisc headings appear only when more than one explicit disc exists.

### P1 — information authority

The hero labels the embedded year as `Año local`. File details use `Formato de
archivo`. External chronology is exposed as `Cronología externa · MusicBrainz`,
preserving the existing release-group authority and avoiding a promise of a
review or biography.

## Principal files

- `app/src/main/java/org/michimusic/mobile/ui/screens/AlbumDetailScreen.kt`
- `app/src/main/java/org/michimusic/mobile/ui/screens/AlbumTrackPositionPolicy.kt`
- `app/src/main/java/org/michimusic/mobile/ui/screens/AlbumQualityPolicy.kt`
- `app/src/main/java/org/michimusic/mobile/ui/screens/AlbumLayoutPolicy.kt`
- `app/src/main/java/org/michimusic/mobile/ui/screens/AlbumsScreen.kt`
- `app/src/main/java/org/michimusic/mobile/ui/components/AlbumMosaicGrid.kt`
- `core/src/main/java/org/michimusic/core/models/Models.kt`
- `data/src/main/java/org/michimusic/data/repository/LocalMediaRepository.kt`
- `design/ui3_1/michi_launcher_symbol.svg`
- `scripts/generate_ui3_launcher.py`
- `scripts/verify_ui3_launcher.py`
- `scripts/render_ui3_launcher_evidence.py`

## Automated verification

All Gradle commands used the required compact-output wrapper.

| Wrapper log | Gate | Result |
|---|---|---|
| `0001.log` | Focused RED matrix | Expected compile failures for missing new contracts |
| `0002.log` | Core/data/app focused metadata/position/view tests | PASS |
| `0003.log` | Album layout/position/quality/playback/identity/view tests | PASS |
| `0004.log` | Mosaic state test + Normal debug assembly | PASS, 121 tasks |
| `0005.log` | Normal debug assembly after monochrome correction | PASS, 113 tasks |
| `0006.log` | First broad run | Harness timeout (exit 143), no failed task |
| `0007.log` | `test lint` + Normal/F-Droid debug/release APKs | PASS, 443.132s |
| `0008.log` | API 36 `connectedNormalDebugAndroidTest` | PASS; 80 finished, 9 designed skips, 0 failed |
| `0009.log` | Playback/position regression tests + Normal debug assembly | PASS, 121 tasks |
| `0010.log` | Final shared playback/playlist ordering tests + Kotlin compile | PASS, 92 tasks |

Additional checks:

- `python3 scripts/verify_ui3_launcher.py`: PASS.
- `bash scripts/verify_michi_opencode_skills.sh --check`: PASS manifest format;
  original native skill logs still require independent inspection.
- `git diff --check`: PASS.
- `ui3_1.patch`: 81-file binary patch; `git apply --check --whitespace=error`
  passed in a detached clean worktree at the exact base, the patch applied, the
  launcher verifier passed there, and every patched file matched the delivery
  tree byte-for-byte.
- Real API 36 functional smoke: Play, Shuffle, Add-to-list empty state,
  Favorite toggle, overflow, mini-player: PASS.

Warnings in the broad build were pre-existing AirCat constant-condition and JVM
class-sharing warnings. The timed-out `0006` attempt was superseded by identical
successful run `0007`.

## Independent review

Read-only review session `ses_e47d83e3fffe0n7SwoPQbz9Zz2` initially returned
`fix-first` after finding that the visual list used decoded disc/track order but
album playback still received MediaStore's raw title order. The correction now
uses one stable presented order for rows, playback queues, track-start indexes,
and album-to-playlist insertion. A title-ordered multidisc regression fixture
proves the resulting `disc 1/track 1 → disc 1/track 6 → disc 2/track 2` queue.

The same reviewer verified the repair, the strict icon safe-zone tightening, and
the final playlist-order alignment in two follow-ups. Final verdict: `ship`, with
no unresolved P0/P1 findings.

## P0/P1/P2 matrix

| Priority | Requirement | Status | Evidence |
|---|---|---|---|
| P0 | Separated adaptive foreground/background | PASS | verifier + mask comparison |
| P0 | Monochrome and legacy density resources | PASS | verifier + builds + Pixel themed capture |
| P0 | Circle/squircle/rounded masks | PASS_LOCAL | deterministic composites |
| P0 | Real launcher validation | PASS_PIXEL / BLOCKED_HARDWARE_OEM | API 36 Pixel regular/themed; no OEM hardware |
| P1 | Mosaic primary, List alternative, no visible CoverFlow | PASS | screenshots + tests |
| P1 | Stable navigation and scroll ownership | PASS | Compose/state + instrumentation suites |
| P1 | Compact hero/actions and first track at 360×800 normal scale | PASS | real screenshots |
| P1 | Truthful codec labels and mixed/unknown behavior | PASS | unit tests + real MP3 capture |
| P1 | MediaStore decode and multidisc grouping | PASS | unit tests + real `1006`→`6` capture |
| P1 | Separate local/file/MusicBrainz facts | PASS | UI copy + existing enrichment contracts |
| P2 | Home/Dock/mini-player bounded smoke | PASS_LOCAL | API 36 smoke + screenshot |
| P2 | Physical TalkBack/OEM sign-off | BLOCKED_HARDWARE | no physical device/alternate launcher |

The full visual and dimension/font/content matrix is in
`evidence/ui3_1/README.md`.

## Limits and status

- No physical-device, OEM-launcher, Bluetooth, real external playback-route, or
  physical TalkBack certification was available.
- No real 320dp/tablet screenshot was captured; deterministic policy coverage
  passed for 320/360/412/600dp and font scales 1.0/1.3/1.5/2.0.
- The 2.0 font-scale screenshot does not keep the first track above the fold.
- Live MusicBrainz/Wikipedia behavior is not claimed.
- Remote CI was not run and no remote mutation was authorized.

Status:

- `UI3_1_CODE_GO = PASS_LOCAL`
- `UI3_1_VISUAL_GO = PASS_PIXEL_AND_DETERMINISTIC`
- `OEM_PHYSICAL_SIGNOFF = BLOCKED_HARDWARE`
- `REMOTE_CI_GO = NOT_RUN`
- `RELEASE_GO = NOT_AUTHORIZED`
