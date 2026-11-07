# Superseded Artist Enrichment R4 domain contract

This R4 contract is historical. Its overlap-based recovery model was superseded by Room v9.
The normative contract is [`ARTIST_ENRICHMENT_DOMAIN_FINAL.md`](ARTIST_ENRICHMENT_DOMAIN_FINAL.md).

<!-- Historical content retained below for auditability. -->

## Historical authority

Artist Enrichment is an autonomous domain subsystem. `RoomArtistEnrichmentSelectionStore`
is the only durable authority for confirmed identity, profile facts, canonical local
association, and exact-context ambiguity decisions. `RoomArtistArtworkStore` is the
only durable authority for confirmed artwork selections. Compose state, image-loader
caches, provider responses, remote URLs, and the current library view are not durable
authority.

The durable identity scope is:

`libraryId + normalized credit + canonical albumGroupKey + confirmed MBID`

Names are display and local-credit evidence. Equal names do not merge artists, equal
MBIDs do not merge canonical groups, and aggregate `SCOPE_ALL` is never persisted as an
identity scope.

## Required invariants

1. A canonical MBID is selected explicitly and persisted before optional provider work.
2. A different MBID cannot replace an existing canonical association without an explicit
   clear followed by a new confirmation.
3. Canonical album evidence is immutable. A rescan, partial view, album addition/removal,
   profile refresh, or explicit decision in another context cannot broaden it.
4. An explicit ambiguity decision applies only to its exact membership fingerprint.
5. Historical overlap may recover one row only when immutable evidence yields exactly one
   candidate. Competition fails closed with `ArtistAssociationAmbiguousException`.
6. Identity, profile update, exact decision, and selective clear are Room transactions.
   Association writes use `@Upsert`, never SQLite `REPLACE`.
7. Clear deletes one canonical identity and only exact decisions that chose that group.
   Other groups, libraries, profiles, decisions, and shared artwork bytes remain.
8. Identity persistence and full-profile persistence are distinct observable outcomes.
9. Local startup and screen entry read Room and private files only. They do not trigger an
   identity search or provider recovery request.
10. Cancellation and library epochs prevent late work from mutating a newly selected
    library.

## Observable outcomes

`ArtistPersistenceStatus` is normative:

- `NOT_ATTEMPTED`: no durable write was attempted.
- `IDENTITY_PERSISTED`: the canonical MBID row committed, but provider facts did not.
- `PROFILE_PERSISTED`: identity and verified profile facts committed.
- `FAILED`: the required write failed; temporary facts must not be represented as durable.
- `CONTEXT_UNAVAILABLE`: no concrete library/album scope exists, so persistence cannot be
  claimed.

`PersistedArtistProfile.needsConfirmation` is true when immutable local evidence uniquely
recovers an association for a changed album view. Saving refreshed profile facts under that
canonical group does not convert the temporary view into canonical ownership. Only an
explicit exact-context resolution can settle a genuinely ambiguous view.

## Boundaries

- Explicit MusicBrainz discovery proposes candidates but never decides identity. Profile and
  artwork enrichment require an already confirmed MBID.
- Embedded-tag scans record observations only and run only after user action.
- Biography, portrait provenance, licence, and attribution survive stale-provider and
  offline states when their Room commit succeeded.
- Missing or corrupt artwork bytes produce a truthful cache error/monogram without erasing
  identity, profile facts, attribution, or the other valid slot.
- SQLite and the filesystem are not one atomic transaction. Database clear removes scoped
  ownership; reference-aware pruning is the separate file collector.

## Freeze decision

Future UI work must adapt to this contract. It must not alter association precedence,
introduce another persistence authority, or infer identity from names or remote media.
