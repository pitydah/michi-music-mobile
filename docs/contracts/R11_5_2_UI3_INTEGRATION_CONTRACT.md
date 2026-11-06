# R11.5.2 — Artist Enrichment UI3 integration contract

Status: stable for UI3 integration. No visual redesign is included in this change.

## Ownership

`ArtistEnrichmentController` is the only owner of identity, biography and artwork
state for one local artist. `ArtistDetailScreen` creates it once and passes it to
`ArtistEnrichmentPanel`; the panel does not create a second controller. The mosaic
reads Room directly and never starts a provider request.

## Identity

- The only accepted identity is a canonical MusicBrainz MBID confirmed by an explicit
  user selection.
- A single same-name candidate is never auto-confirmed.
- The association is scoped by library, normalized local credit and confirmed MBID.
- `albumGroupKey` is the album fingerprint; `albumIdsJson` is the evidence used to
  recover a legitimate album add/remove/rescan. Recovery is allowed only when exactly
  one historical row for that library+credit overlaps the visible albums. Ambiguous
  overlaps fail closed and remain in Room.
- `PersistedArtistProfile.needsConfirmation` is true for a recovered association.
- Artwork reads use `PersistedArtistProfile.associationGroupKey`, so a changed album
  set does not hide previously confirmed artwork.

## Public state

`ArtistEnrichmentState` exposes:

- `status`: `IDLE`, `SEARCHING`, `CHOOSING`, `VERIFIED`, `FAILED`
- `profile`, `candidates`, `selections`, `pendingPortraits`
- `persisted`, `persistedMbid`, `persistenceStatus`, `persistenceError`
- `associationGroupKey`, `needsConfirmation`
- `stale`, `refreshing`, `refreshFailed`, `message`

`ArtistPersistenceStatus` distinguishes provider success from Room success:
`NOT_ATTEMPTED`, `PERSISTED`, `FAILED`, `CONTEXT_UNAVAILABLE`.

UI3 must not synthesize "saved" from a provider response. When persistence is not
`PERSISTED`, show the profile only with the verifiable not-saved diagnostic.

## Artwork state

`ArtistArtworkSelection` carries slot, asset id, confirmed MBID, local path, focus,
manual flag, revision, source page, license id/url, attribution and status.
`ARTIST_THUMB` and `ARTIST_HERO` are independent; both may share one content-addressed
file. `ArtworkLoadStatus` includes `READY`, `PARTIAL`, `NO_IMAGE`, `REJECTED`,
`PROVIDER_ERROR`, `CACHE_ERROR`, `IDENTITY_MISMATCH`.

The hero authority contract is: a valid READY selection with matching MBID; when both
controller and Room are valid, the higher persisted `revision` wins, ties keep the
controller. Manual choices and focus updates receive the revision assigned inside the
Room transaction, never the wall clock.

## Events and navigation

- `bind(libraryId, credit, albumIds)` is called by the screen owner.
- `confirm(...)` is called only from an explicit candidate tap.
- `refresh(...)` preserves the visible profile while refreshing.
- `clear(...)` cancels in-flight work and suppresses immediate auto-discovery.
- `applySelections(...)` requires the full library/credit/album context and matching
  MBID; stale contexts are ignored.
- Navigation and existing test tags remain compatible: `artist_detail_enrichment_toggle`,
  `artist_enrichment_panel`, `artist_enrichment_clear`, `artist_artwork_saved_*`,
  `artist_artwork_focus_*`, `artist_artwork_choose_*`.

## Provider contract

MusicBrainz is identity. Wikidata supplies QID/relations. Wikipedia supplies verified
text. Commons supplies P18/P180/PageImages. Openverse/Flickr is manual-only: discovery
does not confirm identity, no automatic assignment, and an image becomes READY only
after a schema.org `ImageObject` binds the exact photo id and expected licence and the
user chooses it. TheAudioDB and Fanart.tv remain disabled.

## Offline and errors

Room and `filesDir/artist-artwork/` are the offline authority. A URL is not an image.
A missing/corrupt file is `CACHE_ERROR`; a corrupt profile is a cache failure, not a
verified profile. The mosaic shows the monogram and never starts a network request.

## UI3 risks

- A future UI3 change must not create a second controller or call providers from
  composition.
- Keep `ArtistArtworkStore.load` as the only artwork read path; do not reintroduce
  legacy `CommonsPortrait.imageUrl` downloads in the mosaic.
- Do not change the hero revision contract without a migration/backfill plan.
- Do not widen persistence to `LibraryScope.All` or a library-less context.
