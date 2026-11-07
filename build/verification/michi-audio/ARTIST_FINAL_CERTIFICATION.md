# Artist Enrichment final local certification

> Historical v9 certification. K10/K11 and Room v10 are certified by
> `ARTIST_K10_K11_FINAL_CERTIFICATION.md`.

Date: 2026-11-07

## Scope and candidates

- Initial directive HEAD: `a10108d6c8d2dd8b4089c8cb20c8514358dd73dd`.
- Structural implementation HEAD: `04655fb2af366763f12ef454e51dcb0c24f7e6af`.
- Reviewed contracts/evidence HEAD: `5be051aab4f9739578145a303a136d21a66c37cb`.
- Branch: `feat/ui2-r11-artist-artwork-federation`.
- Local commits: `04655fb2` (`fix(artist): separate identity from membership`) and
  `5be051a` (`docs(artist): freeze final domain model`), followed by the local report commit
  containing this certification.

The exact final worktree HEAD is reported by `git rev-parse HEAD` after the report commit; a
Git commit cannot embed its own content-derived SHA. The executable candidate is unchanged
after `04655fb2`; subsequent commits contain contracts and evidence only.

No push, PR mutation, merge, release, UI3 implementation, provider addition, player rewrite,
destructive migration, physical-user-data operation, or master mutation occurred.

## Root cause and final model

Room v8 conflated verified MBID identity, stable local selection ownership, and temporary
visible album membership. A current album-set hash and overlap recovery could either leak a
homonym or lose valid continuity after the original album disappeared. No stricter overlap
rule could represent a legitimate later binding.

Room v9 separates the existing identity/profile row, a stable durable association,
authoritative album+normalized-credit bindings, non-authoritative membership evidence, and
exact-context resolutions. Names and overlap never assign identity. The semantic writes are
explicit identity confirmation, local membership binding, exact ambiguity resolution,
profile-only refresh, explicit wrong-binding replacement, and selective clear.

## Modified implementation surface

- Room/schema/migration: `data/src/main/java/org/michimusic/data/artist/ArtistEnrichmentCache.kt`,
  schema `9.json`, and `ArtistEnrichmentMigrationTest.kt`.
- Domain/persistence: `ArtistEnrichmentPersistence.kt`, orchestrator, controller, and current
  Artist Enrichment panel.
- Tests: structural identity/membership, independent generated sequences, persistence,
  restart, transactional integrity, controller selection, API36 panel, and durable E2E.
- Contracts: the four `ARTIST_ENRICHMENT_*` final contracts. Older overlap contracts and the
  R4 certification are marked historical.

## RED to GREEN evidence

Gradle workflow `2de733b9b12589602afb87f8d4382e8a` recorded the required failures before
production behavior existed:

- run 0001: missing semantic identity/binding/profile APIs;
- run 0006: missing controller local-binding operation;
- run 0008: missing truthful current-panel local-bind action;
- run 0017: missing explicit wrong-binding replacement;
- run 0020: replacement left contradictory confirmed evidence;
- run 0023: API36 historical fixture lacked v9 associations/bindings.

The corresponding GREEN runs were 0002, 0007, 0009, 0018/0021, and 0024/0025. Run 0022
passed the independent reference model after replacement entered its operation set.

## Final executable evidence

| Gate | Evidence | Result |
|---|---|---|
| Identity/membership structure | 8 focused tests | PASS |
| Generated sequence model | seed `0x4D49434849`, 500 sequences × 1–25 operations | PASS |
| Manual sequences | SEQ-01 through SEQ-20 mapped to executable tests | PASS |
| Transaction integrity | 9 file-backed WAL tests | PASS |
| Migration | 8 tests through v9; integrity `ok`, zero FK rows | PASS |
| App JVM | Normal 894/894; F-Droid 894/894 | PASS |
| Data JVM | debug 172/172; release 172/172 | PASS |
| Player JVM | debug/release 169/169 each | PASS |
| Core JVM | debug/release 34/34 each | PASS |
| Michi Link JVM | debug/release 130/130 each | PASS |
| Lint | workflow run 0027 | PASS |
| Four APK variants | run 0029, 375 tasks | PASS |
| API36 isolated emulator | runner 80; XML 70, 10 designed skips, 0 failures/errors | PASS |
| Independent adversarial review | two SHIP verdicts, no P0/P1 | PASS |

Run 0028 was interrupted by the 120-second harness timeout after reaching release packaging;
it contained no Gradle failure. The identical run 0029 completed successfully. No interrupted
run is counted as PASS.

## Migration and Android preservation

`MIGRATION_8_9` is additive and non-destructive. It preserves identity/profile facts,
biography, provenance/licence, artwork assets/links/selections/focus/revisions, and exact
decisions. Only reproducible explicit legacy membership becomes authoritative; uncertain
history becomes `INSUFFICIENT` evidence.

On isolated API36 emulator `emulator-5554`, `adb install -r` preserved a byte-verified private
sentinel for package `org.michimusic.mobile.debug`. This proves same-v9 package-data
preservation, not cross-version migration; the real v8→v9 Room test supplies migration
evidence.

## Independent reviews and limits

Review sessions `ses_e49d89efeffe5ArX13CtdHHgQc` and
`ses_e49c8fefbffe4OEgoSox1vvrdt` both returned SHIP with no P0/P1. Their P2 observations and
dispositions are recorded in `ARTIST_FINAL_INDEPENDENT_REVIEW.md`.

Deterministic fixtures used real private decoded image bytes, but live-provider qualification
for Midnight Oil, A Flock Of Seagulls, and Martika was not newly run. Physical device,
TalkBack, Bluetooth, and real-provider live gates remain unexecuted. The skill receipt checker
passed, but release authority still requires human inspection of original OpenCode logs.

## Gate decisions

| Gate | Decision |
|---|---|
| `DOMAIN_MODEL_GO` | PASS |
| `IDENTITY_CONTINUITY_GO` | PASS |
| `HOMONYM_ISOLATION_GO` | PASS |
| `EXPLICIT_BINDING_GO` | PASS |
| `PERSISTENCE_TRUTH_GO` | PASS |
| `ARTWORK_PERMANENCE_GO` | PASS |
| `OFFLINE_RECOVERY_GO` | PASS |
| `TRANSACTION_INTEGRITY_GO` | PASS |
| `MIGRATION_GO` | PASS |
| `SEQUENCE_TESTING_GO` | PASS |
| `ANDROID_E2E_GO` | PASS_LOCAL_FIXTURES |
| `REGRESSION_GO` | PASS |
| `INDEPENDENT_REVIEW_GO` | PASS |
| `DOMAIN_CONTRACT_GO` | PASS |
| `DOMAIN_CLOSED_LOCAL` | YES |
| `REMOTE_CI_GO` | `REMOTE_CI_NOT_RUN` |
| `PHYSICAL_DEVICE_GO` | NOT_RUN |
| `PR_MERGE_GO` | NOT_AUTHORIZED |
| `RELEASE_GO` | NOT_AUTHORIZED |

## UI adaptation boundary

Future UI3 must consume the final domain state and semantic actions. It must not infer MBIDs,
use names/overlap as membership authority, write Room association tables directly, trigger
provider work during bind/recomposition/scroll, treat Coil or remote URLs as artwork
authority, or create a second Artist Enrichment pipeline. UI3 was not implemented here.

**`ARTIST_ENRICHMENT_DOMAIN_STATUS = CLOSED_LOCAL`**

**`ARTIST_ENRICHMENT_READY_FOR_UI_ADAPTATION = YES`**
