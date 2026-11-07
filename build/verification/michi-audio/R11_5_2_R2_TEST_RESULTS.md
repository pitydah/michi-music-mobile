# R11.5.2-R2 — Test results

## Focused workflow

| Run | Scope | Result |
|---|---|---|
| 0001 | K01 RED | expected FAIL, 3 contract failures |
| 0002 | K01 same tests | PASS |
| 0003 | K01 regression | PASS |
| 0004 | K02 RED | expected FAIL, 2 ambiguity failures |
| 0005 | K02 same tests | PASS |
| 0006 | K02 regression + data | PASS |
| 0007–0008 | K03 install, RED/GREEN direct methods | compile/install PASS; RED then GREEN recorded by ADB |
| 0009 | K03 artwork/restart regression | PASS |
| 0010–0012 | K04 RED/GREEN and hydration correction | PASS after same RED method turned GREEN |
| 0013 | K04 unit consensus | PASS |
| 0014 | K04 UI compile/install | PASS; full mosaic 16/16 |
| 0015 | all modules, flavors, lint, four APKs | PASS |
| 0016 | first full API 36 | FAIL: binary-incompatible interface default + superseded provider-MBID assertion |
| 0017 | compatibility repair focused | PASS |
| 0018 | final API 36 | PASS: 77 tests, 10 skips, 0 failures |
| 0019 | final emulator APK install | PASS |

## Final local gates

- App Normal: **858**, App F-Droid: **858**, data: **170**, player: **169**, core: **34**, Michi Link: **130**; all 0 failures/errors/skips.
- Room/data: PASS; Room remains v7 and exported schema is unchanged.
- Lint: PASS.
- Normal debug/release and F-Droid debug/release: PASS.
- API 36: 77 tests, 0 failures, 10 opt-in skips.
- Live providers/artwork: 6/6 PASS.
- Playback/library regressions are included in player/core/app module suites.
- Skill inventory executed; receipt format check PASS. Independent review of
  native skill logs remains a release-process check, not a product defect.

APK hashes for the final source candidate (regenerate only if source changes):

- Normal debug: `e8c34d4f9a01a233fa9cc60679c4021e21f2d87b918b745e66945df1853573a6`
- Normal release unsigned: `231cd05c208d03da9dec59dd00d902bb10b7d5c7ec6600140e50f35fdf5f17e3`
- F-Droid debug: `78fd3aa892c49c927d156e276c46c415d6776ddb7c41139be6d33db55ef14086`
- F-Droid release unsigned: `3c2da721d8dc1c265f9c7d5d108bfec76f552af88437b7ad15f8d29f0b7e9e05`
