# Artist Enrichment UI adaptation contract

This contract applies to current Compose surfaces and future UI3. It is not authorization to
implement UI3.

## Consume domain state and actions

The UI consumes `ArtistEnrichmentController.state`, persisted association IDs, verified
private artwork selections, and these semantic actions:

- explicit provider discovery and identity confirmation;
- explicit local `bindVisibleAlbums` for one selected `unboundAlbumIds` entry when persisted
  identity is known;
- exact-context ambiguity resolution;
- explicit profile/artwork refresh;
- scoped clear and artwork focus/selection;
- a future explicit wrong-binding replacement flow backed by
  `ArtistMembershipReplacementStore`, never by direct Room writes.

Screen binding may hydrate Room and private files only. Composition, recomposition, scrolling,
screen entry, tab changes, and library switching must not start MusicBrainz, Wikipedia,
Commons, Openverse, or tag scans.

## Truthful presentation

- `IDENTITY_PERSISTED` and `PROFILE_PERSISTED` are different outcomes.
- `needsConfirmation=true` means some visible album-credit rows are unbound. Persisted
  identity/profile/artwork may remain visible, but the UI must offer explicit local binding
  rather than claiming automatic ownership.
- Exact resolution does not clear pending membership. Each pending album gets its own local
  action; choosing one must not bind another or start provider work.
- Ambiguity shows actual stored associations and requires an explicit choice.
- `NO_IMAGE`, legitimate no-photo, partial artwork, provider error, cache error, and identity
  mismatch remain distinguishable.
- Controls use truthful enabled states, Spanish semantics, and at least 48 dp targets.
- Epoch/library guards prevent stale work from appearing in a newly selected scope.

## Forbidden parallel authority

No UI may:

- infer, merge, or replace identity from a name;
- treat album overlap or the current view as membership authority;
- write identity, association, binding, evidence, or resolution tables directly;
- use an exact-context choice to transfer subset/superset ownership;
- refresh a profile as a way to bind albums;
- make Coil, memory/disk cache, or a remote URL the confirmed artwork authority;
- implicitly share THUMB/HERO focus;
- create a second controller, repository, database, or provider pipeline.

## Adaptation acceptance

A conforming UI proves zero idle HTTP; explicit provider and local-binding actions; exact
ambiguity; distinct persistence/error/offline states; library-switch cancellation; private
THUMB/HERO rendering with independent focus; and no direct association decisions in Compose.
