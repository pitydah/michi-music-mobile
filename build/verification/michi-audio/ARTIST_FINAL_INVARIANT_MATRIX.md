# Artist Enrichment final invariant matrix

Structural candidate: `04655fb2af366763f12ef454e51dcb0c24f7e6af`

First independent review: `ses_e49d89efeffe5ArX13CtdHHgQc` — `SHIP`, no P0/P1.

| Invariant | Result | Executable evidence |
|---|---|---|
| INV-01 Explicit MBID changes only | PASS | `aDifferentMbidCannotSilentlyReplaceAnExistingAssociation`; `concurrentDifferentMbidConfirmationsCannotReplaceTheSameAssociation` |
| INV-02 Biography/READY artwork match identity | PASS | `anAssetFromAnotherMbidNeverReachesThisArtistStore`; `crossMbidSelectionNeverSatisfiesAnotherArtist` |
| INV-03 Visible membership cannot mutate identity | PASS | `profileRefreshCannotCreateMembershipForANewAlbum`; `explicitMembershipBindingPreservesOneCanonicalAssociationAcrossDisjointViews` |
| INV-04 No name homonym inheritance | PASS | `unboundAlbumNeverInheritsIdentityFromNameOrPriorContext`; `homonymBindingsOwnSubsetsAndCombinedResolutionNeverTransfersOwnership` |
| INV-05 Exact decision does not propagate | PASS | `durableExplicitResolutionSelectsOneHistoricalAssociationWithoutDeletingTheOther`; generated probes include subsets, supersets, and permutations |
| INV-06 Explicit B binding survives loss of A | PASS | `explicitlyBoundAlbumKeepsIdentityAfterOriginalAlbumDisappears`; restart continuity sequence |
| INV-07 Insufficient evidence never confirms | PASS | `v8FactsSurviveAndOnlyReproducibleExplicitMembershipBecomesAuthoritative`; malformed legacy tests |
| INV-08 Semantic writes are transactional | PASS | nine file-backed `ArtistEnrichmentTransactionalIntegrityTest` cases |
| INV-09 Clear is selective | PASS | `clearInvalidatesOnlyResolutionsThatDependOnTheDeletedGroup`; `clearIsScopedAndDoesNotRemoveOtherLibrary` |
| INV-10 Referenced artwork survives | PASS | `clearingOneGroupPreservesSharedArtworkBytesReferencedByAnotherGroup`; prune test |
| INV-11 Persistence status is truthful | PASS | `identityCommitAndFailedProfileCommitReportIdentityOnlyPersistence`; full-profile counterpart |
| INV-12 Idle UI paths perform no HTTP | PASS | startup hydration tests; `recompositionNeverStartsAnAutomaticLookup`; explicit local-bind UI test |
| INV-13 Offline restart uses local facts | PASS | four restart/reopen tests, including identity-only and real artwork |
| INV-14 Late response is scope-safe | PASS | late manual selection, exact resolution, and prior-artist API36 tests |
| INV-15 Migration preserves and invents nothing | PASS | eight migration tests; v8→v9 PRAGMAs clean |
| INV-16 Ambiguity never uses row/date/name | PASS | homonym/duplicate-MBID ambiguity tests and explicit candidate revalidation |
| INV-17 Profile update cannot bind | PASS | `profileRefreshCannotCreateMembershipForANewAlbum` |
| INV-18 Reimport ID change requires evidence | PASS | `changedLocalAlbumIdNeedsExplicitEvidenceBeforeItRecoversIdentity` |
| INV-19 Idempotence/conflicts fail closed | PASS | same/different-MBID races, stale candidate, replacement evidence assertions |
| INV-20 Multi-credit and same-MBID groups isolate | PASS | `oneAlbumCanBindMultipleArtistCreditsWithoutGlobalAlbumOwnership`; `sameMbidCanOwnTwoIndependentAssociations` |

## Failure boundaries exercised

Tests inject or model failure before identity commit, after identity/before profile commit,
provider transport/profile failure, artwork HTTP/decode/licence failure, missing file, SHA
mismatch, private-path/symlink escape, corrupt profile JSON, migration uncertainty,
transaction cancellation, different-MBID concurrency, clear/bind races, and late library
responses. Each leaves only committed facts and does not trigger name-based recovery.
