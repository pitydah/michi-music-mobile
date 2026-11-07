# R11.5.2-R3 K07 — transactional identity integrity

## Reproduced race

The original store read associations, checked invariants and then executed
`@Insert(onConflict = REPLACE)` without a common transaction. A file-backed WAL
test synchronized two coroutines after their reads. Two different MBIDs both
succeeded for the same association; SQLite `REPLACE` silently selected one.
This was reproduced as the mandatory RED in gradle-run sequence 13.

## Correction

- `saveIdentity`, enriched `save(profile)`, explicit resolution and `clear`
  perform their entire read/check/write sequence inside
  `database.withTransaction`.
- Association writes use `@Upsert`, avoiding SQLite REPLACE's delete+insert
  behavior and identity-scoped cascade risk.
- Identity and profile writes use the same association/MBID/group invariants.
- Controller mutexes and epochs remain UI safety measures; storage integrity no
  longer depends on them.

## File-backed concurrency matrix

| ID | Test | Result |
|---|---|---|
| K07-T01 | `concurrentSameMbidConfirmationsAreIdempotent` | PASS; one row |
| K07-T02 | `concurrentDifferentMbidConfirmationsCannotReplaceTheSameAssociation` | PASS after expected RED; one accepted, one rejected |
| K07-T03 | `concurrentIdentityConfirmationCannotOverwriteANewerBiography` | PASS |
| K07-T04 | `explicitClearQueuedAfterConfirmationDoesNotResurrectTheAssociation` | PASS; defined transaction order leaves no active row |
| K07-T05 | `separateLibrariesCanConfirmTheSameMbidWithoutSharingAssociations` | PASS |
| K07-T06 | `albumMembershipChangesUpdateOneHistoricalAssociation` | PASS; no hidden duplicate |
| K07-T07 | `cancellationInsideRoomTransactionRollsBackTheAssociation` | PASS |
| K07-T08 | `reopeningAfterConcurrentConflictHasOneIdentityAndCleanSqlitePragmas` | PASS |

The reopened database returned `PRAGMA integrity_check = ok`, zero rows from
`PRAGMA foreign_key_check`, and one non-contradictory active association.

Focused identical race GREEN: sequence 14, 8.472 s. Full eight-test class:
sequence 15, 10.683 s. Final persistence/race recheck: sequence 23, success.

Decision: `ARTIST_IDENTITY_TRANSACTION_GO`.
