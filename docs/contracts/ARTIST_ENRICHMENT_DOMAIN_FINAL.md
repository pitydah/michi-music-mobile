# Artist Enrichment final domain contract

Status: frozen locally on 2026-11-07 by the final domain-permanence closure.

This document supersedes the R11.5.2-R2 and R4 Artist Enrichment contracts. Artist
Enrichment is the authority; future UI work adapts to it.

## Domain model

Artist Enrichment persists three different facts:

1. **Verified artist identity**: an explicitly accepted canonical MBID plus validated
   profile facts. A name is never identity.
2. **Durable local association**: a stable, library-local aggregate that owns one
   identity and association-specific artwork selections. The legacy field name
   `albumGroupKey` now carries this stable `associationId`; it is not a current-view hash.
3. **Album-credit binding**: the sole authoritative claim that one album key and one
   normalized credited component belong to an association. Including the credit permits
   several credited artists on one album.

Membership evidence is history, not ownership. Only a confirmed binding assigns an album
credit. `INSUFFICIENT`, malformed, or conflicting evidence never does.

## Authority

- `RoomArtistEnrichmentSelectionStore` is the sole writer for identity, profile,
  association, binding, evidence, exact-context resolution, replacement, and clear.
- `RoomArtistArtworkStore` is the sole writer for confirmed artwork selections and focus.
- Provider responses, names, current album sets, overlap, Compose state, Coil caches, and
  remote URLs are not durable authority.
- `SCOPE_ALL` is a derived read scope and is never persisted as an identity scope.

## Semantic operations

- `confirmNewIdentity`: explicitly creates identity and association and binds only the
  album-credit rows included in that confirmation.
- `bindAlbumsToAssociation`: explicitly reuses a known local association without provider
  HTTP. A conflicting binding fails closed.
- `resolveAssociation`: chooses among already-bound associations for one exact sorted
  context. It creates no binding and never propagates.
- `refreshProfile`: updates provider facts only. It cannot alter membership.
- `replaceAlbumBindings`: explicitly reassigns named album-credit rows, supersedes the old
  confirmed evidence, and invalidates affected exact-context decisions.
- `clear`: deletes one association and its FK-dependent bindings/evidence plus decisions
  that chose it. Other associations and libraries remain.

No generic production save may infer which operation the caller intended.

## Required invariants

The normative invariants are `INV-01` through `INV-20` in
`build/verification/michi-audio/ARTIST_FINAL_INVARIANT_MATRIX.md`. In summary:

- MBIDs change only through explicit identity operations.
- Biography and READY artwork remain identity- and association-scoped.
- Membership/view changes cannot mutate identity.
- Names and album overlap never assign a homonym.
- Exact decisions never propagate to subsets, supersets, or other permutations.
- Explicit bindings survive loss of every other album.
- Ambiguity fails closed; row order, dates, names, and provider availability cannot choose.
- Identity, binding, replacement, resolution, profile, and clear writes are transactional.
- Identity persistence and full-profile persistence remain distinct observable outcomes.
- Startup, recomposition, scrolling, and library switching perform no provider request.
- Valid offline facts survive process/Room reopen and schema upgrades.

## Observable persistence truth

`ArtistPersistenceStatus` remains normative:

- `NOT_ATTEMPTED`: no durable write was attempted.
- `IDENTITY_PERSISTED`: identity and its explicitly confirmed initial bindings committed,
  but new provider facts did not.
- `PROFILE_PERSISTED`: identity and validated profile facts committed.
- `FAILED`: the required write failed; temporary facts are not durable.
- `CONTEXT_UNAVAILABLE`: no concrete library/album context exists.

`PersistedArtistProfile.needsConfirmation` means at least one visible album-credit row is
not bound to the recovered association. The UI may show persisted profile/artwork while
offering an explicit local bind. It must not claim that the unbound album already belongs.

## Freeze boundary

UI3 is not implemented by this closure. Any future UI must consume the domain state and
semantic actions without adding name inference, direct Room writes, a second controller,
automatic provider work, or another artwork authority.
