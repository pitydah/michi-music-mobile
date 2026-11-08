# UI3.2 Visual and Functional Evidence

Candidate: `fix/ui3-2-pending-corrections` against exact UI3.1 head
`88f707a6123e06f1bfb2650af77b1dc87e13a2c4`.

## Evidence index

### Supplied before evidence

`before/README.md` identifies the source video and extraction timestamps.

- `before/detail-heading-and-sort.png`: `Pistas (1)` and the A–Z action.
- `before/bottom-sheet-ghost.png`: displaced outline around the modal region.
- `before/album-menu-opening.png` and `before/bottom-sheet-dismiss.png`:
  transition context.

### Album presentations

- `library/mosaic-api36.png`: Mosaic, the default mode.
- `library/coverflow-api36.png`: restored CoverFlow with adjacent cover,
  position hint, explicit actions, metadata and canonical track number.
- `library/list-api36.png`: List reached after the exact
  Mosaic → CoverFlow → List cycle.

The deterministic Compose coverage is authoritative for the adjacent-cover
contract: a side cover selects without opening, then the settled center opens
the same opaque album identity. A manual edge tap was not retained as evidence
because it was unreliable on the emulator recording.

### Detail and sheets

- `detail/detail-pistas-api36.png`: heading exactly `Pistas`; no count or A–Z.
- `detail/detail-360x800-font1.0-api36.png`: actions and first track visible.
- `detail/detail-360x800-font2.0-api36.png`: actions remain usable in at most
  two rows; the first track is below the fold, which is allowed by UI3.2.
- `detail/detail-360x800-font2.0-followup-api36.png`: follow-up correction with
  a stacked hero, the complete long album title, usable actions and the full
  two-by-two Dock at 200% text.
- `p2/dock-360x800-font2.0-api36.png`: the same real-device frame retained as
  focused Dock evidence; all four labels, including `Dispositivos`, are visible.
- `bottom_sheet/album-menu-open-api36.png`: clean album menu without the
  displaced root-border rectangle.
- `bottom_sheet/quality-transition-api36.png`: menu → quality transition with
  one visible sheet/scrim.
- `bottom_sheet/after-dismiss-api36.png`: detail restored after dismissal.

UIAutomator inspection during the transition found `Calidad de los archivos`
and no simultaneous `Opciones del álbum`, matching the exclusive modal state.

## Validation matrix

| Area | Case | Evidence | Result |
|---|---|---|---|
| Modes | Mosaic → CoverFlow → List → Mosaic | `AlbumViewModePolicyTest`, `AlbumViewModeComponentsTest`, real API 36 captures | PASS |
| CoverFlow | side select; settled center open; deletion/shrink recovery | `MichiCoverFlowHostTest` | PASS automated |
| CoverFlow | reduced motion | `CoverFlowMotionPolicyTest`, `CoverFlowGeometryTest` | PASS automated |
| Identity | opaque scoped IDs; ambiguous duplicates fail closed | `AlbumCoverFlowPolicyTest` | PASS |
| Refresh | transient empty reload and changed track membership preserve selection; confirmed deletion reconciles | `AlbumCoverFlowPolicyTest` | PASS automated |
| Order | normal, encoded and multidisc positions; live playable filtering | `AlbumTrackPositionPolicyTest`, `AlbumPlaybackPolicyTest` | PASS |
| Detail | exact `Pistas`, no title-sort state | policy test + real API 36 capture | PASS |
| Playlist | add action uses the exact eligible payload; registered-but-unavailable tracks disable it with explanatory copy | `AlbumDetailUiPolicyTest` | PASS automated; unavailable fixture not present on device |
| Sheets | no full-window root border | source check + before/after API 36 captures | PASS_LOCAL |
| Sheets | one active album modal | `AlbumDetailUiPolicyTest` + API 36 transition inspection | PASS |
| Width | 360×800 | real API 36 captures | PASS |
| Width | default emulator (~412dp) | real API 36 captures | PASS |
| Width | 320dp / tablet | unchanged layout-policy and broad Compose suites | PASS automated only; no new real capture |
| Font | 1.0 / 2.0 | real 360×800 captures | PASS; at 2.0 the hero stacks, the complete title and Dock labels remain visible, and the first track stays below fold as allowed |
| Font | 1.3 / 1.5 | unchanged UI3.1 layout-policy coverage | PASS automated only |
| Build | Normal/F-Droid debug and release | follow-up Gradle wrapper `0008.log` | PASS |
| Device tests | API 36 connected suite | follow-up Gradle wrapper `0009.log` | PASS; 80 finished, 10 designed live-test skips, 0 failed |

## Reproduction notes

- Device: `emulator-5554`, Android API 36, Pixel launcher, density 420.
- 360×800 capture: `adb shell wm size 945x2100`; size and font scale were
  reset after capture.
- The supplied before source is `/home/cristian/Escritorio/ui3-3.webm` and is
  not copied into the repository.

## Explicit limits

- `BLOCKED_HARDWARE`: no physical TalkBack or OEM-device certification.
- No new real 320dp or tablet screenshot was captured.
- At font scale 2.0, the first track remains below the fold as allowed. The
  stacked hero preserves the complete long title and the two-by-two Dock
  preserves all full labels without reducing the requested font scale.
- The zero-playable-track playlist state and transient-empty CoverFlow reload
  are proved with deterministic policy tests; the emulator library did not
  contain those fixtures, so no manual screenshot is claimed for them.
- Real manual side-cover tapping is not claimed; deterministic Compose tests
  prove that interaction contract.
- Draft PR #11 is the publication target for the approved follow-up. Merge and
  release remain unauthorized.
