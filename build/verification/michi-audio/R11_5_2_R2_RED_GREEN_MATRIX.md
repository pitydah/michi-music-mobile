# R11.5.2-R2 — RED/GREEN matrix

Baseline: `f45669ac82a6c678c3c5db98e223dd9cfb95a795`.
Gradle workflow: `517b489279efd2d2e9d3af3f08c25a24`.

| Defect | RED evidence | Root cause | Minimal correction | GREEN evidence | Regression | Residual risk |
|---|---|---|---|---|---|---|
| K01 identity after provider | Run 0001 failed `confirmedIdentityIsPersistedBeforeAnImmediateProviderFailure`, `providerSuccessNeverBecomesPersistenceSuccessWhenRoomSaveFails`, and `aDifferentMbidCannotSilentlyReplaceAnExistingAssociation`. | `ArtistEnrichmentOrchestrator.enrichLocked` called `confirmedProfile`/`refreshProfile` before Room save. | Added identity-first store seam, Room pending identity row, controlled MBID replacement, and provider short-circuit on Room failure. | Run 0002, same targeted command/tests: PASS. | Run 0003 identity/orchestrator/persistence/restart/controller suites PASS; run 0017 compatibility regression PASS. | None in contract. |
| K02 arbitrary historical row | Run 0004 failed `duplicateMembershipWithDifferentMbidsFailsClosedAndPreservesBothRows` and `exactAssociationCannotOverrideAConflictingMembershipIdentity`. | DAO used `getByMembership(... LIMIT 1)` and load selected before proving uniqueness. | DAO returns all matches; repository deduplicates canonical groups and throws typed ambiguity while retaining every row. | Run 0005, same persistence tests: PASS. | Run 0006 persistence/restart/consensus/startup/data suites PASS. | None; no schema change. |
| K03 remote confirmed artwork | Direct API 36 RED: hero and mosaic tests each failed `assertDoesNotExist`, finding `artist_detail_hero_photo` and `artist_mosaic_photo_Midnight Oil` from profile URLs. | Confirmed panel/mosaic/hero silently fell back to `portrait.imageUrl`. | Removed saved-profile URL rendering; READY private selections are the sole confirmed render model. Candidate previews remain temporary. | Same two direct tests: `OK (2 tests)`. | Run 0009 artwork JVM/restart PASS; hero/durable/manual 9/9; scoped mosaic 4/4. | None; corrupt/missing file intentionally displays monogram. |
| K04 aggregate monogram despite local artwork | Direct API 36 RED: `allLibrariesShowsPhotoWhenEveryLibraryConfirmsSameMbid` timed out at 5000 ms because aggregate path never loaded artwork. | `ArtistMosaicGrid` resolved a consensus portrait URL only when no artwork store; production aggregate path did nothing. | Consensus now retains scoped associations; local aggregate resolver checks same MBID, loads READY THUMB selections, prefers manual then revision, and observes scoped revision flows. | Same test: `OK (1 test)`. | `ArtistProfileConsensusTest` PASS; complete `ArtistMosaicGridTest` 16/16; full API 36 77 tests with 0 failures. | None in aggregate identity/artwork contract. |

Stable diagnostics introduced/used: `AE_IDENTITY_SAVE_ATTEMPT`,
`AE_IDENTITY_SAVE_COMMITTED`, `AE_IDENTITY_SAVE_FAILED`,
`AE_PROFILE_SAVE_FAILED`, `AE_ASSOCIATION_AMBIGUOUS`.
