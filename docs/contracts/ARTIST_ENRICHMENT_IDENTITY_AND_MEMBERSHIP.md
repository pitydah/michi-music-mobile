# Artist identity and membership contract

## Stable keys

| Persisted concept | Key | Meaning |
|---|---|---|
| Identity/profile row | `libraryId + creditKey + associationId` | One explicit MBID and its validated facts. `artist_enrichment.albumGroupKey` is the legacy column that stores `associationId`. |
| Durable association | `libraryId + creditKey + associationId` | Stable local aggregate pointing to the identity row. |
| Album-credit binding | `libraryId + albumKey + creditKey` | Sole current ownership of that credited component by one association. |
| Membership evidence | binding scope + association + type + evidence key | Historical observation and status; never ownership by itself. |
| Exact resolution | `libraryId + creditKey + exact context fingerprint` | User choice for that exact visible context only. |

The normalized credit is a namespace for local credited components. It is not artist
identity. The association ID is immutable after creation even if every visible album
changes.

## Lookup

For a concrete library, normalized credit, and visible album keys:

1. Read authoritative bindings for those album-credit rows.
2. Validate an exact-context decision only against associations currently present in those
   bindings. A stale target fails closed.
3. Return no identity when no album is bound.
4. Return the one bound association when exactly one is present. Set `needsConfirmation`
   if some visible albums remain unbound.
5. Throw `ArtistAssociationAmbiguousException` when several associations are present and
   there is no valid exact decision.

Names, album overlap, creation date, row order, and provider results are never tie-breakers.
The empty context has no persisted identity.

## Confirmation and continuity

First identity confirmation binds only the album-credit rows explicitly included in that
action. A later album remains unbound until `bindAlbumsToAssociation` or another approved
structured-evidence operation confirms it. Once B is bound to association X, B-only views
recover X offline even after A disappears.

Changed MediaStore album IDs are new observations. They require explicit binding or stable
track/content evidence; matching names do not transfer the old binding.

One album can carry multiple credited artists because `creditKey` is part of the binding
key. The same MBID can back several local associations with independent artwork/focus.

## Ambiguity and replacement

An exact A+B decision never changes A or B ownership and never applies to A, B, A+B+C,
B+A under a different exact fingerprint, or C. Replacement is a separate explicit write:
it validates the expected old association, reassigns only named bindings, supersedes old
confirmed evidence, and invalidates decisions whose saved context intersects those albums.
It preserves both identity histories.

## Selective clear

Clear identifies one association transactionally. Deleting its identity cascades only its
durable association, bindings, and evidence. Decisions choosing it are removed explicitly.
Other associations, credits, libraries, profiles, and content-addressed artwork assets
remain. Artwork selections/links are cleared through the separately scoped artwork store;
filesystem pruning remains reference-aware.

## Legacy v8 facts

Room v9 preserves every v8 identity/profile and exact decision. A v8 album list becomes a
confirmed binding only when its deterministic hash reproduces the association ID and the
row records an explicit manual identity confirmation. Other observations migrate as
`INSUFFICIENT`; they cannot recover identity by overlap.
