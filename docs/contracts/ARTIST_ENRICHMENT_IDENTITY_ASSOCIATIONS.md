# Artist identity and association rules

This reference defines how Artist Enrichment maps a local credit and album view to a
durable identity.

## Persisted concepts

| Concept | Meaning |
|---|---|
| `albumGroupKey` | Immutable key of the album set that created a canonical association. |
| `albumIdsJson` | Immutable canonical album evidence for that association. |
| `membershipFingerprint` on `artist_enrichment` | Legacy observed value retained for non-destructive compatibility; not lookup authority. |
| `artist_association_resolution.membershipFingerprint` | Exact visible context in which a user chose among historical candidates. |
| `chosenAlbumGroupKey + chosenMbid` | Target of that exact-context decision. |

Room schema version 8 already separates canonical rows from exact-context decisions. R4
therefore requires no schema change. Migrations v1 through v8 remain additive and have no
destructive fallback.

## Resolution precedence

For a concrete library, normalized credit, and current album set:

1. Compute the deterministic current membership fingerprint.
2. If an exact decision exists, load only its chosen canonical group and MBID. A missing or
   mismatched target fails closed; no fallback may hide the invalid decision.
3. Otherwise include an exact canonical row and rows whose immutable canonical album
   evidence overlaps the visible set.
4. Deduplicate by canonical group and MBID.
5. Return one candidate. Return no identity for no candidates. Throw
   `ArtistAssociationAmbiguousException` for more than one.

No exact decision propagates by overlap. For example, if X owns album A and Y owns album B,
choosing X for the exact A+B view does not give X ownership of B. Loading B still returns Y;
loading an unknown C returns no association; loading a competing view remains ambiguous.

## Legacy broadened evidence

R3 could rewrite `albumIdsJson` and legacy `membershipFingerprint` to a temporary expanded
view while retaining an older `albumGroupKey`. R4 trusts album evidence only when its hash
reproduces the immutable canonical key. This also fails closed if an interrupted or
hand-authored row changed only one legacy field. The canonical row remains available by
exact group, but inconsistent evidence cannot drive overlap recovery or invent ownership.

## Writes and ambiguity

- `saveIdentity()` creates one canonical row or reuses the requested historical row; it
  never changes canonical evidence on an existing row.
- `save(profile)` updates verified facts, provenance, timestamps, and stale time only. It
  preserves the selected row's identity and canonical evidence.
- `resolveAssociation()` revalidates the current candidates in the transaction and records
  one exact-context decision. It never rewrites or deletes historical rows.
- A stale choice, disappeared candidate, malformed MBID, or attempted silent replacement
  is an error, not a row-order fallback.

## Selective clear

`clear()` resolves one canonical group inside the same transaction that deletes it. It then
deletes only resolution rows whose `chosenAlbumGroupKey` is that group. Decisions choosing
other groups survive, including decisions for the same normalized credit. Library scope is
always part of every query.

Artwork cleanup is separately scoped by library, credit, canonical group, MBID, and slot.
Identity clear does not directly delete content-addressed files. This preserves bytes still
referenced by another group, slot, or library.
