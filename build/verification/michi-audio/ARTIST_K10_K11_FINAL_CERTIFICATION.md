# Artist Enrichment K10/K11 final certification

Date: 2026-11-07

## Scope

- Remote `master` baseline: `5298378b7baf86841a35ee310edc91f75850d9cd`.
- Audited Room v9 parent restored by fast-forward: `5f0bb381ebe08e05a97e93de01a974e3efb37475`.
- Delivery branch: `master`.
- Final commit: the commit containing this report; read with `git rev-parse master`.
- Remote publication and exact-head CI are reported separately after the normal push.

The v9 parent contained every remote-master change and was 52 commits ahead, so restoring it
was a fast-forward. No force-push, history rewrite, destructive migration, UI3 work, provider
addition, playback rewrite, or physical-device data operation was used.

## K10: deterministic historical ownership

The old 8→9 migration wrote bindings while scanning identity rows. Because
`artist_album_credit_binding` is keyed by `libraryId + albumKey + creditKey`, cursor order
could decide which historical association owned a contested key.

Room v10 closes both upgrade paths:

1. Fresh 8→9 first persists every durable association and every legacy claim as evidence.
2. It groups all claims by `libraryId + albumKey + creditKey` before creating a binding.
3. Ownership is created only when exactly one association claims the key and at least one
   claim is reproducibly confirmed.
4. A second association blocks ownership even when its claim is `INSUFFICIENT` or both
   associations share the same MBID. Competing claims remain as `INSUFFICIENT` history.
5. 9→10 removes only ambiguous migration-created `LEGACY_EXPLICIT_CONTEXT` bindings and
   demotes their competing migration evidence. Later user, replacement, embedded-MBID, and
   stable-track bindings keep their evidence type and survive.

Version 10 is a data-repair migration, so schemas 9 and 10 intentionally have the same Room
identity hash. Migration tests cover fresh conflicts, confirmed-versus-insufficient claims,
same-MBID distinct associations, installed-v9 repair, preservation of later explicit and
replacement bindings, repeated migration chains, `PRAGMA integrity_check = ok`, and an empty
`foreign_key_check`.

## K11: exact resolution keeps pending membership truthful

An exact ambiguity decision now chooses a profile for that exact view without assigning its
unbound album-credit components. `load()` returns sorted distinct `unboundAlbumIds` and keeps
`needsConfirmation=true` while any are pending.

The controller and current panel expose one local action per pending album. The transaction
validates the current library, credit, association, MBID, visible context, and unbound status;
it binds only the chosen album and never calls a provider. A profile refresh cannot clear the
pending list. A file-backed test proves the list survives profile refresh and two Room
close/reopen cycles, and that binding C leaves D pending without stealing A from another
artist.

## RED to GREEN

Gradle wrapper workflow `55551ce031e7ee0e85117f9fb03cdb54` recorded:

- run 0001: RED compilation failure because v10 migration and K11 APIs did not exist;
- run 0002: migration GREEN, including strict competing-claim cases and 9→10 repair;
- run 0003: K11 persistence/controller GREEN;
- run 0004: restart fixture failed because its biography lacked required provenance;
- run 0005: corrected file-backed restart/profile-refresh/reopen GREEN;
- run 0006: focused API36 panel GREEN;
- run 0007: one-at-a-time local binding and zero provider enrichment calls GREEN;
- run 0008: all Artist Enrichment JVM and migration tests GREEN.

Run 0004 is not counted as a product failure or PASS. It verified the existing biography
provenance guard and was corrected only in the test fixture.

## Final executable evidence

| Gate | Evidence | Result |
|---|---|---|
| App JVM | Normal 899; F-Droid 899; 0 failures/errors/skips | PASS |
| Data JVM | debug/release 177 each; 0 failures/errors/skips | PASS |
| Player JVM | debug/release 169 each | PASS |
| Core JVM | debug/release 34 each | PASS |
| Michi Link JVM | debug/release 130 each | PASS |
| Lint | wrapper run 0010 | PASS |
| Four APK variants | wrapper run 0011; 375 tasks | PASS |
| API36 isolated emulator | wrapper run 0012; runner 80, XML 70 + 10 designed skips, 0 failures | PASS |
| Independent review | `ses_e49253364ffeWSl9fNwYlB34G0`; SHIP, no P0/P1 | PASS |

The independent reviewer explicitly verified that insufficient competitors and same-MBID
distinct associations participate in K10 conflict grouping, that 9→10 cannot delete a user
binding whose evidence type was replaced, and that K11 pending truth survives exact
resolution, refresh, and reopen. Non-blocking P2 notes concern future coupling, conservative
legacy-source classification, one-time migration complexity, the intentional data-only schema
identity, and an internal unsorted intermediate list. None changes ownership or persistence
truth.

## Decisions

- `K10_MIGRATION_OWNERSHIP_GO = PASS`
- `K11_PENDING_MEMBERSHIP_GO = PASS`
- `MIGRATION_INTEGRITY_GO = PASS`
- `OFFLINE_RESTART_GO = PASS`
- `ANDROID_E2E_GO = PASS_LOCAL_FIXTURES`
- `INDEPENDENT_REVIEW_GO = PASS`
- `REMOTE_CI_GO = PENDING_PUSH`
- `PHYSICAL_DEVICE_GO = NOT_RUN`
- `RELEASE_GO = NOT_AUTHORIZED`
