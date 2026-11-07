# R11.5.2-R3 K05 — persistence truth

## Root cause

`ArtistEnrichmentOrchestrator.enrichLocked()` committed the identity first and
then fetched and saved the profile. If the second save failed, it retained the
single `ArtistPersistenceStatus.PERSISTED` value. The controller therefore
could present temporary downloaded metadata as though Room had confirmed it.

## Correction

- Replaced the overloaded success state with `IDENTITY_PERSISTED` and
  `PROFILE_PERSISTED`.
- Kept the compatibility `persisted` flag at identity level while exposing
  explicit `identityPersisted` and `profilePersisted` truth.
- A failed profile write retains the confirmed MBID and canonical group, keeps
  the downloaded profile temporary, reports the exact unsaved-update message,
  and never clears `stale` as if a profile commit succeeded.
- Identity-first Room rows decode with `profilePersisted=false`; startup
  hydration is local-only and does not invent a biography or call providers.
- Full-profile success is the only path that renders the complete persisted
  status.

## Mandatory scenarios

| ID | Test/evidence | Result |
|---|---|---|
| K05-T01 | `identityCommitAndFailedProfileCommitReportIdentityOnlyPersistence` | PASS after expected RED |
| K05-T02 | `identityOnlyConfirmationSurvivesRoomCloseAndOfflineReopen` | PASS |
| K05-T03 | `failedProfileUpdateReopensWithPreviousBiographyArtworkAndFocus` | PASS; old biography, HERO asset, manual selection and focus survive |
| K05-T04 | `successfulIdentityAndProfileCommitsReportFullProfilePersistence` | PASS after expected RED |
| K05-T05 | Existing identity-save failure/provider-order tests in `ArtistEnrichmentOrchestratorTest` | PASS; no provider call and no persistence claim |
| K05-T06 | `failedRefreshKeepsCachedProfileMarkedStale` | PASS |
| K05-T07 | API 36 `identityOnlyPersistenceIsAnnouncedSeparatelyFromTheFailedProfileUpdate` | PASS 1/1; exact accessible Spanish messages asserted |

File-backed restart coverage also verifies that only Room-authoritative data is
shown after close/reopen and that previous artwork remains identity-scoped.

Decision: `ARTIST_PERSISTENCE_TRUTH_GO`.
