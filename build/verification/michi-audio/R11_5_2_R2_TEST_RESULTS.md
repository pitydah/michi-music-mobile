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
| 0020 | pre-review broad local gate | PASS |
| 0021 | independent-review stale-state RED | expected FAIL: failed refresh cleared cached staleness |
| 0022 | stale-state GREEN + hydration suite | PASS |
| 0023 | exact reviewed source-head broad gate | PASS |
| 0024 | exact reviewed source-head API 36 | PASS: 77 tests, 10 skips, 0 failures |

## Final local gates

- Verified source commit: `c7face3`.
- App Normal: **859**, App F-Droid: **859**, data: **170**, player: **169**, core: **34**, Michi Link: **130**; all 0 failures/errors/skips.
- Room/data: PASS; Room remains v7 and exported schema is unchanged.
- Lint: PASS.
- Normal debug/release and F-Droid debug/release: PASS.
- API 36 at the reviewed source head: 77 tests, 0 failures, 10 opt-in skips.
- Live providers/artwork: 6/6 PASS.
- Playback/library regressions are included in player/core/app module suites.
- Independent read-only code review: no blocking or critical findings. Its one
  actionable runtime finding received its own RED/GREEN regression in runs
  0021–0022. Skill inventory executed; receipt format check PASS. Independent
  review of native skill logs remains a release-process check, not a product defect.

## Remote CI

- Published candidate: `41f9a65c24d4c3988319ddabb600f1ce8c3658c5`.
- GitHub Actions run: `42321152896`.
- **8/8 PASS**: lint, Normal/F-Droid unit tests, Room migrations/schemas,
  Normal/F-Droid builds, API 36 instrumented tests, and release-candidate
  quality gate.
- Non-blocking runner annotations: Node.js 20 action compatibility warnings,
  `setup-java@v4` deprecation, and the announced `ubuntu-latest` image migration.

APK hashes for the final source candidate (regenerate only if source changes):

- Normal debug: `555fdc9373d84218b28427a23d6a798dd424c4458de06c1d3dac3cbec77f3845`
- Normal release unsigned: `b5cc2bc00e4c1b93489a19c9712910401e59828576930184250d6b7dd288b6a4`
- F-Droid debug: `28e7a8d78f63dd30059e377ae78d17e57f49f1e3fb010321503878487088cafd`
- F-Droid release unsigned: `50f68eb2202be7311de50581c164448c57eb970062ed396ac3e6ecba908368de`
