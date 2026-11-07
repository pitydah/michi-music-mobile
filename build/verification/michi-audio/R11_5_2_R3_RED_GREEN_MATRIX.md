# R11.5.2-R3 RED/GREEN matrix

Baseline: `a363d7340e395deb12bbdce3bfa0fb5400071178` on
`feat/ui2-r11-artist-artwork-federation`.

Implementation commit: `5feb036` (`fix(artist): seal enrichment integrity`).

All Gradle evidence was collected through `gradle-run` workflow
`9155680ee6f43363d155ce0a72e97612`.

## Mandatory RED → FIX → GREEN evidence

| Defect | RED before production fix | GREEN with identical focused test | Regression evidence |
|---|---|---|---|
| K05 persistence truth | Sequence 1, expected failure in 6.928 s. `identityCommitAndFailedProfileCommitReportIdentityOnlyPersistence` and `successfulIdentityAndProfileCommitsReportFullProfilePersistence` failed because the only success value was `PERSISTED`. The file-backed restart fixture passed. | Sequence 2, same orchestrator/restart command, success in 7.462 s. | Sequence 3 controller/startup success; sequence 4 API 36 accessible status test 1/1; sequence 26 full unit matrix success. |
| K06 durable ambiguity choice | Sequence 5, expected failure in 3.897 s: `durableExplicitResolutionSelectsOneHistoricalAssociationWithoutDeletingTheOther` threw ambiguity because v7 could not consume an explicit decision. Sequence 6, expected API 36 failure in 14.91 s: no actionable historical candidates were rendered. | Sequence 7 persistence test success in 7.629 s. Sequence 9 identical functional API 36 test success 1/1 in 9.695 s. | Sequences 10, 11, 18, 21 and 23 cover migration, reopen/artwork isolation, epoch isolation, same-MBID groups, profile refresh, candidate disappearance, album changes and distinct MBIDs. |
| K07 concurrent identity integrity | Sequence 13, expected failure: two different MBID confirmations both succeeded against one file-backed WAL database and `REPLACE` silently selected a winner. | Sequence 14, identical race test success in 8.472 s after transactional read/check/write and `@Upsert`. | Sequence 15 full eight-test race class success in 10.683 s; sequence 23 final persistence/race recheck success; sequence 26 module regressions success. |

## Non-functional command incidents

- Sequences 22 and 24 did not execute tests: Gradle rejected a misplaced
  `--tests` filter on aggregate `:data:test`. The suites were rerun correctly as
  sequences 23 and 25.
- Sequence 28 was terminated by the harness at its 120-second timeout while
  packaging release APKs. No Gradle failure fingerprint existed. The identical
  four-variant assembly was rerun with sufficient time as sequence 29 and
  succeeded.

These incidents are not counted as product test failures.

## Final local matrix

| Gate | Evidence | Result |
|---|---|---|
| Normal app unit | Sequence 26; 875 tests, 0 failures | PASS |
| F-Droid app unit | Sequence 26; 875 tests, 0 failures | PASS |
| Data/Room | Sequences 25/26; 171 tests per debug/release variant, 0 failures | PASS |
| Player | Sequence 26; 169 tests per debug/release variant, 0 failures | PASS |
| Core | Sequence 26; 34 tests per debug/release variant, 0 failures | PASS |
| Michi Link client | Sequence 26; 130 tests per debug/release variant, 0 failures | PASS |
| Lint | Sequence 27, `BUILD SUCCESSFUL` | PASS |
| Normal/F-Droid debug/release APK | Sequence 29, all four variants, `BUILD SUCCESSFUL` | PASS |
| API 36 instrumentation | Sequence 30, runner finished 79 tests; XML records 69 test cases, 10 designed skips, 0 failures | PASS |
| Remote CI | No push authorization; no final-head run | `REMOTE_CI_NOT_RUN` |
