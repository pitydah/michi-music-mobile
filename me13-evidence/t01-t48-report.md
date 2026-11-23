# ME-13 T01–T48 evidence report

Baseline: `08a0b750`
Branch: `fix/me13-megasprint-08a0b750`
Product worktree: `/home/cristian/michi-mobile-me13-megasprint`
Original UI worktree: untouched at `d223eb74` (`me11v-ux-corrective`)

## Verdict

| Measure | Result |
|---|---:|
| T01–T48 executed and passed | **32 / 48 (66.7%)** |
| T01–T48 NOT RUN | **16 / 48** |
| T01–T48 failed | **0 / 48** |
| Full JVM tests | **7491 / 7491, 0 failures** |
| Data instrumented | **24 total, 21 executed, 3 opt-in skips, 0 failures** |
| `:app:lint` | PASS |
| Debug/release variants and androidTest sources | PASS |
| Xiaomi production engine (historical final product code) | PASS |
| Xiaomi manual app-UI traversal | **NOT RUN / BLOCKED_HARDWARE** |
| Global verdict | **NO-GO** (16 scenarios not run) |

A skipped test is never counted as PASS. Unit-only evidence is labeled as such in
`t01-t48.csv`.

## Executed verification

All Gradle commands ran through the `gradle-run` lifecycle, workflow
`af95f6c8ec4122e4ea2adfde5f4b236a`.

| Run | Command | Result | Log |
|---|---|---|---|
| Final JVM + data instrumented | seven JVM suites plus `:data:connectedDebugAndroidTest` | BUILD SUCCESSFUL | 0029 |
| Full data instrumented after T24 fix | `:data:connectedDebugAndroidTest` | 24 total / 3 opt-in skips / 0 failures | 0027 |
| Recovery coordinator target | `MetadataRecoveryCoordinatorInstrumentedTest` | 3 / 3 PASS | 0026 |
| Restore target | `MetadataSourceRestoreCoordinatorTest` | PASS | 0028 |
| Stability target | `MetadataCommittedCandidateStabilityTest` | PASS | 0014 |
| App instrumented | `:app:connectedNormalDebugAndroidTest` | 206 tests / 25 skips / 0 failures after one isolated Compose flake rerun | 0023 |
| Lint | `:app:lint` | PASS | 0024 |
| Release variants | Normal and F-Droid release assemble | PASS | 0020 |

JVM breakdown: core 1023, data 729, player 234, michi-link-client 205,
aircat-transfer 414, app Normal 2443, app F-Droid 2443.

## What is proven

- Real disposable MediaStore MP3 ID3v2.3 TITLE save, independent postcheck,
  exact `cached_tracks` CAS and journal clear
  (`MetadataSaveMediaStoreE2EInstrumentedTest`).
- MediaStore announced `SIZE` lag: content is authoritative; an announced stamp
  move triggers exactly one fresh read; persistent movement fails closed
  (`MetadataCommittedCandidateStability`, 10 cases; five consecutive full data
  instrumented runs after the fix).
- Review-free recovery from a durable owner receipt, intent and private copies,
  with no `MetadataReview` input and no second destructive open
  (`MetadataRecoveryCoordinatorInstrumentedTest`).
- Missing owner receipt fails closed without CAS or source write.
- Missing Room row leaves an actionable V2 receipt and does not roll back the
  verified candidate.
- Altered intent/candidate, foreign owner/URI and forged evidence are refused.
- CAS conflicts are retained without overwrite; an already-applied row is
  cleaned without a second CAS.
- Cleanup is resumable from `CLEANUP_SAFE`, including a missing stage file, and
  the journal record is deleted last.
- Corrupt receipts are retained and never published.
- Typed restore writes `ROLLBACK_PENDING` before the destructive open, verifies
  the original by independent full SHA, keeps both private copies on failure,
  and completes `RESTORED_VERIFIED` without a provider write when the source is
  already original.
- ME-12 four tabs and footer `Revertir cambios | Guardar` remain; Save is
  disabled while recovery is pending and recovery is a separate action.

## Not run (16)

| Scenario | Reason |
|---|---|
| T02, T03, T04, T05, T06 | No MediaStore end-to-end run for ID3v2.4, ARTIST, ALBUM, GENRE or four fields. Unit rewriter evidence exists but is not a product save. |
| T10 | The real MediaStore consent dialog was not driven automatically. |
| T12 | No product-point fault injection between preflight and `MUTATING`. |
| T13 | No product-point save with an active playback descriptor. |
| T15 | No scripted double-tap product test. |
| T18, T19, T20 | No production Save process kill at `BACKUP_DURABLE`, `STAGE_READY` or `MUTATING`. Only the opt-in synthetic-provider harness exists. |
| T22 | No actual reboot; the coordinator test uses a fresh instance in the same process. |
| T36 | No forced Room-down product test. |
| T43 | No production kill during rollback. |
| T48 | Editor cold reopen is proven; a full Library plus editor traversal is not. |

## Physical device

The previous Xiaomi `25010PN30G` run proved the production engine on final
product code: real MediaStore MP3, independent postcheck, exact Room CAS,
`indexPublished=true`, journal clear. That evidence is historical and is not
re-attributed to every later commit.

The manual app-UI path remains **NOT RUN / BLOCKED_HARDWARE**: HyperOS denies
`adb shell input` with `INJECT_EVENTS`. No human tap run was performed in this
session.

## Safety boundaries

- Global write gates stay closed.
- Certified profile: MediaStore MP3, ID3v2.3/2.4, single-value
  `TITLE/ARTIST/ALBUM/GENRE`.
- No artwork, multivalue, FLAC, SAF or MP4 expansion.
- Synthetic disposable fixtures only; no personal music was modified.
- No push, PR, merge or remote CI.
