# UI3.1 Visual and Functional Evidence

Candidate: `fix/ui3-1-visual-icon-hardening` against
`qa/ui3-r19@47577c0b33efbadd90843d9880e173982b4db3fc`.

## Reproduce deterministic evidence

```bash
python3 scripts/generate_ui3_launcher.py
python3 scripts/verify_ui3_launcher.py
python3 scripts/render_ui3_launcher_evidence.py
```

The launcher comparison script reads the before layers directly from the exact
base commit. The original app captures are documented in
[`before/README.md`](before/README.md).

## Evidence index

### Adaptive launcher icon

- `icon/adaptive-mask-comparison.png`: exact-base and current foregrounds under
  deterministic circle, squircle, and rounded-square masks.
- `icon/after-pixel-launcher.png`: current icon in the real API 36 Pixel
  Launcher.
- `icon/after-pixel-themed-launcher.png`: current monochrome layer with Pixel
  Launcher themed icons enabled.
- `before/baseline-pixel-launcher.png`: exact-base Pixel Launcher comparison.

The deterministic verifier checks all density dimensions, foreground safe-zone
bounds, full-bleed opaque backgrounds, monochrome silhouette/detail, legacy
square/round outputs, and a deliberately bad full-canvas foreground fixture.

### Library

- `library/after-library-albums-mosaic.png`: primary Mosaic presentation.
- `library/after-library-albums-list.png`: List alternative.
- `library/after-library-mosaic.png`: populated-library context.

The visible selector exposes only Mosaic and List. The screenshots show square
artwork and local title/year/artist/track-count facts.

### Album detail

- `album_detail/after-album-detail-412x915-font1.0.png`
- `album_detail/after-album-detail-360x800-font1.0.png`
- `album_detail/after-album-detail-360x800-font1.5.png`
- `album_detail/after-album-detail-360x800-font2.0.png`

At 360×800 and font scale 1.0 the first track is fully visible without scroll.
The fixture displays `MP3` and visual track number `6` while preserving the raw
MediaStore value `1006`. At scale 1.5 the actions use two rows and the first
track card begins in the viewport. At scale 2.0 the action area remains limited
to two rows, but the first track falls below the fold; this is an observed
accessibility limit, not a passed first-track-visibility claim.

### Functional/P2 smoke

- `p2/album-playback-mini-player.png`: album Play action started the scoped
  track and exposed the active mini-player.
- API 36 UIAutomator checks also exercised Shuffle, album Favorite toggle,
  Add-to-list (truthful empty-playlist state), and the album overflow menu.

## Validation matrix

| Area | Case | Evidence | Result |
|---|---|---|---|
| Width | 320dp | `AlbumLayoutPolicyTest` | PASS (policy) |
| Width | 360dp | policy test + real screenshots | PASS |
| Width | 412dp | policy test + real screenshot | PASS |
| Width | tablet/600dp | `AlbumLayoutPolicyTest` | PASS (policy only) |
| Font | 1.0 | policy + real 360/412 captures | PASS |
| Font | 1.3 | `AlbumLayoutPolicyTest` | PASS (policy) |
| Font | 1.5 | policy + real 360 capture | PASS; two rows |
| Font | 2.0 | policy + real 360 capture | PASS for max-two-row actions; first track below fold |
| Codec | MP3/MP2/MPEG/FLAC/mixed/unknown and supported mappings | `AlbumQualityPolicyTest` | PASS |
| Numbering | normal/1006/2002/absent/invalid/non-MediaStore | `AlbumTrackPositionPolicyTest` | PASS |
| Albums | one/multiple/multidisc sorting and headers | policy tests; one-track real capture | PASS automated; multidisc visual fixture only automated |
| Navigation | Mosaic → detail → back/state seam | Compose/state and instrumentation suites | PASS |
| Actions | Play/Shuffle/Add/Favorite/menu | API 36 UIAutomator smoke + automated suites | PASS |
| Launcher | circle/squircle/rounded square | deterministic composites | PASS |
| Launcher | Pixel regular/themed | real API 36 Pixel Launcher | PASS |
| Launcher | legacy densities | deterministic verifier + build variants | PASS |

## Explicit limits

- `BLOCKED_HARDWARE`: no physical OEM launcher or alternative launcher/shape
  overlay was available. Deterministic mask composites do not replace OEM
  certification.
- `BLOCKED_HARDWARE`: no physical TalkBack run was available. Spanish semantics
  and minimum-target behavior are covered by code/tests and API 36 inspection,
  not physical accessibility certification.
- No real tablet or 320dp screenshot was captured; those dimensions are covered
  by deterministic layout-policy tests only.
- MusicBrainz live-provider results are not claimed.
- This evidence supports local code acceptance only. It is not `RELEASE_GO`.
