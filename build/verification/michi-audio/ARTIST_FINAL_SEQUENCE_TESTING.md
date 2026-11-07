# Artist Enrichment final sequence testing

## Generated model

`ArtistAssociationSequencePropertyTest.fiveHundredGeneratedSequencesMatchTheIndependentAssociationModel`
uses fixed seed `0x4D49434849`. It runs 500 sequences of 1–25 operations against real Room
and an independent map-based reference model. Operations include identity confirmation,
binding, exact resolution, profile refresh, selective clear, explicit replacement, and
observation across two libraries, same/different MBIDs, homonyms, and albums A/B/C.

After every operation it probes `[]`, `[A]`, `[B]`, `[C]`, `[A,B]`, `[B,A]`, `[A,C]`,
`[B,C]`, and `[A,B,C]`. A divergence prints the seed and complete operation trace for
retention/minimization. Gradle workflow run 0022 passed after replacement entered the model;
no divergent seed was produced.

## SEQ-01 through SEQ-20

These required manual sequences map to executable tests rather than duplicate test bodies:

| Sequence | Scenario | Executable test/evidence |
|---|---|---|
| SEQ-01 | Confirm A, bind B, lose A, offline B-only | `albumMembershipChangeKeepsTheCanonicalAssociationAndBothArtworkSlots` |
| SEQ-02 | Confirm A, observe unbound B | `unboundAlbumNeverInheritsIdentityFromNameOrPriorContext` |
| SEQ-03 | X→A/Y→B subsets and combined ambiguity | `homonymBindingsOwnSubsetsAndCombinedResolutionNeverTransfersOwnership` |
| SEQ-04 | Combined exact choice does not steal subset | `exactCombinedResolutionCannotStealTheOtherArtistsCanonicalSubset` |
| SEQ-05 | Unknown C and empty context remain unassigned | structural test plus generated probes |
| SEQ-06 | Same MBID, separate associations/focus | `sameMbidCanOwnTwoIndependentAssociations`; group-scoped artwork test |
| SEQ-07 | Separate libraries isolate | `separateLibrariesCanConfirmTheSameMbidWithoutSharingAssociations` |
| SEQ-08 | Selective clear preserves independent decisions | `clearInvalidatesOnlyResolutionsThatDependOnTheDeletedGroup` |
| SEQ-09 | Shared artwork bytes survive clear | `clearingOneGroupPreservesSharedArtworkBytesReferencedByAnotherGroup` |
| SEQ-10 | Identity-first provider failure | `confirmedIdentityIsPersistedBeforeAnImmediateProviderFailure` |
| SEQ-11 | Profile second commit fails | `identityCommitAndFailedProfileCommitReportIdentityOnlyPersistence` |
| SEQ-12 | Missing slot/SHA corruption preserves other slot | artwork downloader/coordinator corruption tests |
| SEQ-13 | Different-MBID confirmation race | `concurrentDifferentMbidConfirmationsCannotReplaceTheSameAssociation` |
| SEQ-14 | Partial/full rescan and disjoint views | `explicitMembershipBindingPreservesOneCanonicalAssociationAcrossDisjointViews` |
| SEQ-15 | v8→v9 upgrade preserves facts | `v8FactsSurviveAndOnlyReproducibleExplicitMembershipBecomesAuthoritative` |
| SEQ-16 | Local album ID changes | `changedLocalAlbumIdNeedsExplicitEvidenceBeforeItRecoversIdentity` |
| SEQ-17 | Multi-artist album | `oneAlbumCanBindMultipleArtistCreditsWithoutGlobalAlbumOwnership` |
| SEQ-18 | Cancellation and library switch | transaction rollback plus late prior-library controller tests |
| SEQ-19 | Wrong binding replacement and stale resolution | `replacingAWrongBindingIsExplicitAndInvalidatesTheAffectedContextDecision` |
| SEQ-20 | API36 explicit local bind and repeated reopen | panel local-bind test and `ArtistDurableAssociationE2ETest` |

## Limits

The generated model covers persistence semantics, not filesystem bytes or Compose lifecycle;
those are covered by dedicated artwork, controller, restart, and API36 suites. No random
failure occurred, so there is no minimized divergent sequence to retain.
