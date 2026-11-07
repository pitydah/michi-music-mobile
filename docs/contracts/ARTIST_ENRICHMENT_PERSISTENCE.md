# Artist Enrichment persistence contract

## Room authority

Artist Enrichment uses the separate private database `michi-artist-enrichment.db`, schema
version 9. It does not modify the playback/library database. Every migration v1→v9 is
explicit; destructive fallback is forbidden.

The v9 authority tables are:

- `artist_enrichment`: verified MBID, profile JSON, provenance, stale time, and legacy
  creation evidence. It remains the sole identity/profile fact table.
- `durable_artist_association`: stable association and FK to its identity row.
- `artist_album_credit_binding`: authoritative album-credit ownership and FK to association.
- `artist_membership_evidence`: confirmed, superseded, insufficient, or conflicting history.
- `artist_association_resolution`: exact-context user decision and saved context album keys.
- artwork asset, link, selection, and revision tables described below.

Identity/association/binding writes, binding replacement, profile refresh, exact resolution,
and clear run in Room transactions. Association writes use `@Upsert`, not SQLite `REPLACE`.
Cancellation rolls back the transaction.

## Upgrade to v9

Migration 8→9 adds the association, binding, and evidence tables and the exact resolution
context column. It copies every v8 identity to a stable association without rewriting the
identity, profile, biography, provenance, artwork, focus, or resolution rows. Only
reproducible explicit legacy contexts become bindings; uncertain rows become insufficient
evidence. Migration tests require `PRAGMA integrity_check = ok` and an empty
`foreign_key_check`.

## Profile truth

An identity-first row uses source `musicbrainz-manual-selection-pending`. The MBID and
initial explicit bindings are durable while provider facts are not. A successful profile
commit changes only validated facts/provenance/staleness and marks profile persistence.
Provider, cancellation, or Room failure preserves the last committed truth and never turns
temporary facts into persisted facts.

## Artwork permanence

Artwork remains split into:

1. `artist_artwork_asset`: immutable content-addressed bytes, SHA-256, MIME, dimensions,
   source, licence, attribution, evidence, and private relative path.
2. `artist_artwork_link`: association/MBID/slot provenance.
3. `artist_artwork_selection`: association/MBID/slot choice, independent focus, manual flag,
   and monotonic revision.

THUMB and HERO may share bytes but keep independent focus and choice. Rendering requires a
matching identity anchor, a private non-symlink path, non-empty bounded bytes, matching SHA,
successful Android decode, dimensions, licence, attribution, and evidence. A remote URL or
image-loader cache is never a confirmed render fallback.

Missing, corrupt, hash-mismatched, undecodable, or identity-mismatched bytes produce a
truthful cache/identity error or monogram. They do not erase identity, profile, attribution,
or the other valid slot.

## Cleanup and offline behavior

SQLite and filesystem mutation are not claimed to be atomic. Scoped clear removes Room
ownership. Reference-aware pruning later removes only unreferenced asset rows and old orphan
files after its grace period. Shared/referenced bytes survive unrelated clear.

Room plus verified private files are sufficient for process death, repeated database reopen,
offline restart, and complete loss of the original album after another album was explicitly
bound. Normal local load performs no HTTP.
