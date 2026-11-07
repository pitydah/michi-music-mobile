# R11.5.2-R — Durable artist identity and persistent enrichment contract

> **Historical contract — superseded.** The overlap-recovery model in this document is
> retained only for audit history. Current authority is
> `ARTIST_ENRICHMENT_DOMAIN_FINAL.md`, which separates verified identity, stable local
> association, and authoritative album-credit bindings in Room v9. Names and album overlap
> are never membership authority.

Historical status: implemented and verified for the earlier pre-v9 candidate.
Baseline: `9334aa4`. Date: 2026-11-06.

## 1. Identity is not enrichment

An artist has four independent layers:

1. **Durable external identity** — a canonical MusicBrainz MBID confirmed by an
   explicit user selection or authorized structured evidence. Name equality is
   never identity proof.
2. **Persistent local association** — the confirmed MBID linked to one concrete
   local library and one canonical artist association. It belongs to exactly one
   library. Two libraries may share an MBID but never share selection data.
3. **Current musical membership** — the albums/tracks currently visible for that
   artist. This is rescan evidence, not identity authority.
4. **Enrichment** — biography, Wikidata QID, photographs, licences, attribution
   and provider state. Missing or stale enrichment never revokes identity.

A Wikipedia failure cannot erase MusicBrainz identity. A Commons failure cannot
erase a saved biography. A provider failure cannot erase a validated image.

## 2. Canonical association

`scopedArtistCreditKey(libraryId, credit, albumIds)` produces:

- `creditKey`: normalized local credit (lowercase, trimmed);
- `currentGroupKey`: SHA-256 of the sorted visible album IDs.

The fingerprint is **membership evidence**, not the durable association identity.
The durable association is the Room `albumGroupKey`. `artist_enrichment` also
stores `membershipFingerprint`, the latest visible fingerprint that resolved to
that association.

Canonical resolution on startup:

1. Load by `(libraryId, creditKey, membershipFingerprint)`.
2. Fall back to the exact legacy `albumGroupKey`.
3. If neither exists, recover only when exactly one stored row for that
   library+credit shares at least one album with the visible set.
4. If two different associations match, fail closed as ambiguous. Never merge by
   name.

A refresh saves the profile under the recovered canonical `albumGroupKey` while
updating `membershipFingerprint` to the current visible fingerprint. Profile and
artwork therefore keep the same association after adding, removing or reordering
albums.

## 3. Startup is local hydration

With a saved profile, startup performs **zero external queries**:

local library → Room MBID/profile → private artwork files → UI.

`findCandidates`, `confirmedProfile`, `refreshProfile`, Wikidata, Wikipedia,
Commons and Openverse are not called by default on startup. A stale biography or a
missing optional image is not permission to query providers. External refresh is
explicit (`Actualizar datos externos`) or a future expressly enabled policy.

The seven-day `staleAfterMs` is external-data freshness, not identity expiry. It
may produce a discrete update-available state; it must not force a network call on
open or delete valid local data.

## 4. Persistence truth

Room is the durable authority for identity and selections. The controller is a
reactive representation, not a second persistent source. Providers only propose
candidates.

States exposed to UI3 include: `IDENTITY_NOT_CONFIRMED`,
`IDENTITY_CONFIRMING`, `IDENTITY_PERSISTED`, `PROFILE_LOADING_LOCAL`,
`PROFILE_READY_OFFLINE`, `PROFILE_UPDATE_AVAILABLE`, `PROFILE_REFRESHING`,
`PROFILE_REFRESH_FAILED`, `ARTWORK_READY`, `ARTWORK_PARTIAL`,
`ARTWORK_CACHE_ERROR`, `ASSOCIATION_AMBIGUOUS`, `PERSISTENCE_FAILED`.

UI3 must not infer any of these from null fields. A confirmed MBID is available as
soon as the Room read finishes. A missing image is not a missing identity.

## 5. Artwork durability

A selected image is READY only after:

validated provider → real bytes → MIME/dimensions → decode → SHA-256 →
atomic private publish → Room asset+link+selection → later read.

A URL alone is not a persisted image. Assets are content-addressed under
`filesDir/artist-artwork/`; the file is private and is not a TTL cache. A
referenced file is never deleted by age, app close/open, album rescan, Coil cache
clear or provider failure.

THUMB and HERO may share bytes but keep independent focus. A manual choice is never
replaced by an automatic result. A damaged slot becomes `CACHE_ERROR` without
deleting the other valid slot or the profile; repair is explicit.

## 6. Revision authority

`artist_artwork_revision` is a per-library monotonic counter, independent of wall
clock. `persistArtwork` and focus updates bump it and assign the resulting revision
to the selection. When two valid same-MBID hero candidates exist, the highest
persisted revision wins; a measured tie keeps the controller value.

Room v7 normalizes historical timestamp revisions to relative ordinal ranks and
resets each library counter to its selection count. A huge legacy timestamp can no
longer outrank every new revision.

## 7. Deletion

Explicit clear uses the **canonical association** (`associationGroupKey`), not a
new current fingerprint. It removes the profile row and the corresponding artwork
selections/links, and deletes a shared file only when no other reference remains.
Partial failure is disclosed and is never reported as complete.

## 8. Provider and photo rules

- Flickr origin evidence must bind the exact numeric photo id and the expected
  licence to the **same** schema.org `ImageObject`. A longer id containing the
  expected id is a different photo.
- Openverse remains manual/opt-in. TheAudioDB and Fanart stay disabled.
- The Wikimedia image user agent is required; image origins are allowlisted.
- A provider result that is not verified cannot overwrite a verified selection.

## 9. Verification gates

`ARTIST_IDENTITY_DURABLE_GO`, `ARTIST_RESTART_GO`, `ARTIST_OFFLINE_GO`,
`ARTIST_NO_REFETCH_GO`, `ARTIST_ASSOCIATION_GO`, `ARTIST_ARTWORK_DURABLE_GO`,
`ARTIST_ROOM_GO`, `ARTIST_MANUAL_SELECTION_GO`, `ARTIST_PROVIDER_GO`,
`ARTIST_UI3_READY`, `ANDROID_CI_GO`.

`PR_MERGE_GO` requires independent review. `RELEASE_GO` requires independent
distribution certification. Physical-device/TalkBack evidence remains `NOT_RUN`
unless actually executed.
