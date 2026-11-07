# Artist Enrichment persistence and artwork contract

## Storage authority

Artist Enrichment uses the separate `michi-artist-enrichment.db` Room database at schema
version 8. It does not migrate or delete playback, history, playlist, or main-library data.
All schema upgrades v1→v8 are explicit and non-destructive.

`artist_enrichment` stores canonical identity, verified profile JSON, stale time, biography
source, portrait provenance/licence/attribution, and immutable album evidence. An identity-
first row uses source `musicbrainz-manual-selection-pending`; this means the MBID is durable
while the full profile is not.

## Profile persistence

- Provider facts are optional after identity commit.
- A provider or profile-write failure preserves the committed identity and any previous
  valid profile. It does not persist temporary replacement facts.
- A stale profile remains readable and visibly stale; offline startup does not replace or
  erase it.
- Biography text is accepted only with allowed source evidence. Portrait metadata is
  accepted only under the Commons policy.

## Artwork model

Artwork is split into three durable records:

1. `artist_artwork_asset`: immutable, content-addressed bytes and their SHA-256, MIME type,
   dimensions, source, licence, attribution, evidence, and private relative path.
2. `artist_artwork_link`: identity- and slot-scoped provenance binding.
3. `artist_artwork_selection`: identity- and slot-scoped choice, independent focus,
   manual-choice flag, and monotonic revision.

`ARTIST_THUMB` and `ARTIST_HERO` may share one asset but retain independent focus and manual
selection. The complete scope is library, normalized credit, canonical group, confirmed
MBID, and slot. Same-name artists, same-MBID groups, and separate libraries do not share a
selection merely because bytes match.

## Render eligibility

Confirmed artwork renders only when all of these checks pass:

- Room selection and provenance link agree with the identity anchor;
- path resolves beneath private `filesDir/artist-artwork/` and is not a symlink escape;
- file is non-empty and within size/pixel limits;
- SHA-256 equals the persisted digest;
- Android can decode the bytes as an image;
- persisted licence, attribution, dimensions, and evidence remain available.

A remote URL is provenance or a discovery input, never a confirmed render fallback. Coil or
another image-loader cache is not persistence authority.

## Failure and cleanup

Missing, corrupt, hash-mismatched, undecodable, or identity-mismatched bytes yield
`CACHE_ERROR` or `IDENTITY_MISMATCH`. They do not erase identity/profile facts or another
valid slot. A legitimate no-photo outcome is distinct from a provider failure and remains a
truthful monogram state.

Clearing an identity removes only its scoped selections and links. It does not directly
delete the asset row or file. Reference-aware pruning removes unreferenced asset rows, and
only old orphan files after the race-grace period. SQLite and filesystem operations are not
claimed to be atomic.

## Offline and aggregate behavior

Room plus verified private files are sufficient for process death, database reopen, offline
restart, and a changed album view. Recovery may set `needsConfirmation`; it must not issue
HTTP automatically.

`SCOPE_ALL` artwork is derived only when every participating concrete library has a
persisted profile for the same MBID. Missing, duplicate, conflicting, or non-persisted
scopes fail closed. Selection remains local and manual-first, then highest persisted
revision; no provider participates in aggregate rendering.
