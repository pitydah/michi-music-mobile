# UI3 R19 Integration Report

## Candidate

- Base: `master` at `3773c96f452289dbf8fa53683fe7de022d898f41`.
- Source artifact: `/home/cristian/Descargas/ui3.patch`.
- Verified artifact SHA-256: `caae1b8d9e5f76082e4dc01d084e272f0f2d7dd0843d062f1de59f1c573805e9`.
- Verified size: 639,878 bytes; 47 files; 2,064 patch insertions and 103 deletions before integration fixes.
- Integration branch: `qa/ui3-r19`.
- Source commit: `210d5b5` (`feat(ui): integrate UI3 album experience`).

`git apply --check --whitespace=error` passed on the exact base. The patch was applied without `--3way`, `--reject`, or an earlier UI3 patch.

## Integration fixes

The first Normal compilation correctly rejected four calls to experimental Material 3 bottom-sheet APIs. The affected composables now opt in explicitly. The new album-detail playlist icons also use the auto-mirrored API instead of the deprecated directional icon. No Artist Enrichment schema, Room v10 identity authority, or provider lifecycle was changed.

## Local verification

Gradle evidence used workflow `811b49e4853e1490a684bb61abc5df90` exclusively.

| Gate | Result |
|---|---|
| Focused app/data tests after integration fix | PASS |
| App Normal JVM | 939 tests, 0 failures/errors/skips |
| App F-Droid JVM | 939 tests, 0 failures/errors/skips |
| Data debug JVM | 178 tests, 0 failures/errors/skips |
| Player debug JVM | 169 tests, 0 failures/errors/skips |
| Core debug JVM | 34 tests, 0 failures/errors/skips |
| Michi Link debug JVM | 130 tests, 0 failures/errors/skips |
| Full lint | PASS |
| Normal/F-Droid debug/release APKs | PASS; 375 tasks |
| Isolated API 36 instrumentation | Runner 80; XML 70 plus 10 designed skips; 0 failures/errors |
| Diff whitespace | PASS |
| Launcher asset structure | Ten PNG files at 108/162/216/324/432 px; adaptive XMLs compile |

Run 0003 of the broad JVM matrix was interrupted by the harness's 120-second limit without a Gradle failure. The identical run 0004 completed successfully with a larger timeout.

## Independent review

Read-only review session `ses_e48f825daffehd1AvGMaXTUjrG` returned `SHIP`, with no P0/P1 findings. It verified fail-closed album identity, separate navigation and playback actions, scope/rescan guards, playback eligibility, Album Enrichment separation from Artist Enrichment and Room v10, persistence truth, accessibility semantics, favorites ordering, and launcher resource completeness.

Non-blocking observations:

- Opening the explicitly requested album-history sheet starts a cache-first release-group search when no cache exists. This is separate from frozen Artist Enrichment, but a later hardening pass could require a second explicit search tap.
- Album-wide favorite changes are serialized and write-before-publish per track, not one all-or-nothing transaction across every track.
- The repository declares `targetSdk = 35` while `AGENTS.md` describes target 36.

## Limits

- No physical-device, TalkBack, Bluetooth, real playback-route, or OEM-launcher visual sign-off was run.
- The ten live-provider/screenshot cases remained intentionally skipped; no live MusicBrainz/Wikipedia result is claimed.
- API36 instrumentation passed the existing suite, but the new album navigation surfaces do not yet have a dedicated full navigation instrumentation scenario. Their identity and interaction policies are covered by JVM/Compose tests.

## Status

- `UI3_CODE_GO = PASS`
- `OPENCODE_INTEGRATION_GO = PASS_LOCAL`
- `REMOTE_CI_GO = PENDING`
- `PHYSICAL_RELEASE_SIGNOFF = NOT_RUN`
- `RELEASE_GO = NOT_AUTHORIZED`
