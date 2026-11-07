# R11.5.2-R3 K06 — explicit ambiguity resolution

## Root cause

The typed ambiguity exception correctly prevented arbitrary identity selection,
but Room v7 had no durable representation of an explicit user choice. The
controller could only publish a generic failed state, so a valid conflict could
not be resolved without deleting or merging history.

## Minimal durable model

Room v8 adds `artist_association_resolution`, keyed by library, normalized
credit and current membership fingerprint. Each decision records the chosen
historical group, confirmed MBID, evidence and confirmation time.

Migration `MIGRATION_7_8` is additive: it creates only the decision table and
index. It does not rewrite or delete artist, artwork, license or selection rows.

Resolution is one Room transaction:

1. recompute candidates for the affected library/credit/membership;
2. require a still-ambiguous set;
3. revalidate the chosen historical group and MBID;
4. update only the chosen row's current membership evidence;
5. upsert the explicit decision;
6. decode and validate the chosen persisted profile before commit.

Restart and later album-set changes carry a prior decision forward only when
the chosen historical row still overlaps visible albums. Multiple applicable
decisions or disjoint homonyms fail closed. Explicit unlink deletes decisions
for that library+credit transactionally, while preserving unselected historical
association and artwork rows.

## Mandatory scenarios

| ID | Test/evidence | Result |
|---|---|---|
| K06-T01 | `ambiguousHistoricalAlbumOverlapIsReportedWithoutDeletingRows`; `duplicateMembershipWithDifferentMbidsFailsClosedAndPreservesBothRows` | PASS |
| K06-T02 | API 36 `ambiguousHistoricalAssociationsExposeRealChoicesAndPersistTheExplicitSelection`; zero name searches | PASS after expected RED |
| K06-T03 | `durableExplicitResolutionSelectsOneHistoricalAssociationWithoutDeletingTheOther` | PASS after expected RED |
| K06-T04 | `explicitAmbiguityResolutionSurvivesReopenAndKeepsArtworkScopedToTheChosenGroup` | PASS |
| K06-T05 | Same tests assert both historical rows remain | PASS |
| K06-T06 | Reopen test loads only selected identity's HERO asset and focus | PASS |
| K06-T07 | `lateExplicitResolutionFromPreviousLibraryCannotOverwriteTheNewLibrary` | PASS |
| K06-T08 | `explicitResolutionRejectsACandidateRemovedBeforeConfirmation` | PASS |
| K06-T09 | `sameMbidInTwoHistoricalGroupsKeepsGroupScopedSelection` including identity/profile refresh | PASS |
| K06-T10 | `v7RowsAndArtworkSurviveAddingExplicitAssociationResolutions` plus file-backed close/reopen | PASS; non-destructive upgrade/reopen |

The distinct-MBID choice is also reloaded after adding another album, proving
that durable carry-forward is not limited to equal-MBID historical groups.

Decision: `ARTIST_AMBIGUITY_RESOLUTION_GO`.
