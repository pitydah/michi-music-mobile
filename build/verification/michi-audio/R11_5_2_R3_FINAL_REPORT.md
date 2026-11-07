# R11.5.2-R3 final local report

## Scope and source

- Baseline: `a363d7340e395deb12bbdce3bfa0fb5400071178`
- Branch: `feat/ui2-r11-artist-artwork-federation`
- Implementation commit: `5feb036` (`fix(artist): seal enrichment integrity`)
- Scope: K05 persistence truth, K06 explicit ambiguity resolution, K07
  transactional identity integrity only
- Remote actions: none; no push, PR mutation, merge or remote CI

## Delivered contract

1. Confirmed identity persistence is distinct from full-profile persistence.
2. A failed profile commit cannot make temporary metadata appear durable.
3. Historical ambiguity presents real Room candidates and records an explicit,
   revalidated choice without deleting the other histories.
4. Room v8 adds only the minimum decision table through a non-destructive
   migration.
5. Concurrent identity/profile/clear operations are transactionally serialized
   and association writes no longer use SQLite `REPLACE`.
6. Offline restart remains Room-authoritative: same MBID, persisted biography,
   private artwork, manual selection and independent focus, with no automatic
   provider recovery call.

## Changed production surfaces

- `ArtistEnrichmentOrchestrator.kt`: explicit identity/profile persistence truth.
- `ArtistEnrichmentController.kt`: profile truth, historical choices and
  epoch-safe explicit resolution.
- `ArtistEnrichmentPanel.kt`: accessible identity-only and ambiguity UI.
- `ArtistEnrichmentPersistence.kt`: durable resolution, transactional writes,
  fail-closed recovery and restart decoding.
- `ArtistEnrichmentCache.kt`: Room v8 entity/DAO/migration and non-destructive
  upserts.
- `data/schemas/.../8.json`: exported Room v8 schema.
- Focused unit, migration, file-backed race/restart and API 36 tests listed in
  the companion reports.

## Local certification

| Gate | Result |
|---|---|
| K05 RED/GREEN and restart | PASS |
| K06 RED/GREEN, migration, restart and artwork isolation | PASS |
| K07 RED/GREEN, races, rollback and SQLite PRAGMAs | PASS |
| Normal app unit | 875 tests, 0 failures |
| F-Droid app unit | 875 tests, 0 failures |
| Data | 171 tests per debug/release variant, 0 failures |
| Player | 169 tests per debug/release variant, 0 failures |
| Core | 34 tests per debug/release variant, 0 failures |
| Michi Link client | 130 tests per debug/release variant, 0 failures |
| Lint | PASS |
| Normal/F-Droid debug/release APK | PASS, four variants |
| API 36 Normal instrumentation | Runner finished 79; XML: 69 cases, 10 designed skips, 0 failures |
| Independent read-only review | No high-confidence K05–K07 blocker after final edge recheck |
| Skills manifest | Nine required rows; `RECEIPT_FORMAT_PASS`; original logs still require human inspection for release authority |

## Gate decision

| Gate | Decision |
|---|---|
| `ARTIST_PERSISTENCE_TRUTH_GO` | GO_LOCAL |
| `ARTIST_AMBIGUITY_RESOLUTION_GO` | GO_LOCAL |
| `ARTIST_IDENTITY_TRANSACTION_GO` | GO_LOCAL |
| `ARTIST_RESTART_GO` | GO_LOCAL |
| `ARTIST_OFFLINE_GO` | GO_LOCAL |
| `ARTIST_ARTWORK_GO` | GO_LOCAL |
| `ARTIST_REGRESSION_GO` | GO_LOCAL |
| `ARTIST_UI3_READY` | `GO_LOCAL` |
| `REMOTE_CI_GO` | `REMOTE_CI_NOT_RUN` — final R3 commit was not pushed |
| `PR_MERGE_GO` | NOT AUTHORIZED |
| `RELEASE_GO` | NOT AUTHORIZED |

## Residual limits

- Physical user-device, TalkBack and Bluetooth/hardware certification were not
  run. Potentially destructive instrumentation was confined to the API 36 AVD.
- Remote CI cannot certify the final local commits until a future authorized
  push produces an exact-head run.
- Existing compiler/deprecation warnings in legacy migration/player tests are
  unchanged and did not fail lint or tests.

Local functional decision: **`ARTIST_UI3_READY = GO_LOCAL`**. This is not merge
or release authorization.
