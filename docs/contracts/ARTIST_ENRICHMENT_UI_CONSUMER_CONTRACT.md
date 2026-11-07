# Superseded R4 UI consumer contract

The normative v9 boundary is
[`ARTIST_ENRICHMENT_UI_ADAPTATION_CONTRACT.md`](ARTIST_ENRICHMENT_UI_ADAPTATION_CONTRACT.md).

<!-- Historical content retained below for auditability. -->

This contract applies to current Compose surfaces and to future UI3 work. UI code presents
domain state and invokes domain actions; it does not decide artist identity or persistence.

## Required inputs

The UI consumes:

- `ArtistEnrichmentController.state` for current status, profile, persistence truth,
  ambiguity candidates, error/warning, and artwork state;
- the controller's bind, explicit-discovery, explicit-confirm, ambiguity-resolution,
  refresh, clear, artwork selection, and focus actions;
- `PersistedArtistProfile.associationGroupKey` when constructing an artwork identity anchor;
- Room-backed artwork selections only after private-file verification.

Binding a screen may hydrate local Room state. It must not start MusicBrainz, Wikipedia,
Commons, Openverse, or an embedded-tag scan. Provider work requires an explicit supported
user action and an already confirmed identity where the action demands one.

## State presentation

- `IDENTITY_PERSISTED` means the MBID survived but full provider facts did not commit.
- `PROFILE_PERSISTED` means verified profile facts also committed.
- `needsConfirmation=true` means local recovery found one canonical history for a changed
  view; the UI may use that local profile/artwork while presenting truthful confirmation
  language.
- Ambiguity displays the actual stored candidates and requires the user to choose one. It
  must not select the first row or launch a name search.
- `NO_IMAGE`, `PROVIDER_ERROR`, `CACHE_ERROR`, `IDENTITY_MISMATCH`, partial artwork, and
  legitimate no-photo are distinct outcomes.

All interactive controls keep truthful enabled/disabled behavior, Spanish semantics, and a
minimum 48 dp target. Cancellation and library changes must prevent stale work from
reappearing in the new scope.

## Forbidden parallel authorities

UI3 and later consumers must not:

- infer or merge identity from artist name;
- perform MusicBrainz work during composition, recomposition, screen entry, scrolling, or
  tab changes;
- write `artist_enrichment` or `artist_association_resolution` directly;
- manufacture, broaden, or propagate canonical album evidence;
- treat an exact-context decision as ownership of overlapping contexts;
- make Coil, memory cache, disk cache, or a remote URL the confirmed-artwork authority;
- replace a verified private file with a remote URL fallback;
- share focus/selection between THUMB and HERO implicitly;
- collapse identity-persisted and profile-persisted into one success claim;
- create a second Artist Enrichment controller, repository, database, or provider pipeline.

## Adaptation acceptance checks

A future UI adaptation is conforming only if tests prove:

1. local entry and recomposition perform no provider request;
2. provider discovery starts only from a truthful explicit action with a minimum 48 dp
   target;
3. exact ambiguity candidates and actions come from the controller;
4. identity-only, profile, stale, offline, no-photo, partial, corrupt-file, and error states
   remain distinguishable;
5. library switching cancels or ignores prior-scope work;
6. THUMB/HERO render verified private bytes with independent focus;
7. no UI code writes association rows or chooses an MBID implicitly.

This document is an adaptation boundary, not authorization to implement UI3.
