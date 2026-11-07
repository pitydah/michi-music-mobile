# Artist Enrichment final root cause

Date: 2026-11-07

Initial HEAD: `a10108d6c8d2dd8b4089c8cb20c8514358dd73dd`

Structural implementation HEAD: `04655fb2af366763f12ef454e51dcb0c24f7e6af`

## Defect

The v8 model used a hash of the currently visible album set as the canonical association
key and later tried to recover continuity from album overlap. That coupled three different
facts:

- which MBID was explicitly verified;
- which stable local aggregate owned profile/artwork choices;
- which currently observed album-credit rows belonged to that aggregate.

Overlap could therefore either attribute a homonym's album to the wrong identity or lose a
valid identity when the original album disappeared. Tightening overlap rules could trade one
failure for the other but could not represent a legitimate later binding of B to X.

## Structural correction

Room v9 separates:

1. the existing `artist_enrichment` identity/profile row;
2. `durable_artist_association`, with a stable association ID;
3. `artist_album_credit_binding`, the sole album+normalized-credit authority;
4. `artist_membership_evidence`, which records history without assigning identity;
5. exact-context resolutions, which choose only among associations already present in that
   exact view.

The legacy `albumGroupKey` column now stores the stable association ID. A current view hash
may seed a new ID and key an exact decision, but cannot assign membership.

## Semantic writes

Production writes are explicit: confirm identity, bind albums to a known association,
resolve exact ambiguity, refresh profile, replace a known wrong binding, or clear one
association. Profile refresh cannot bind albums. Replacement supersedes old confirmed
evidence and invalidates intersecting context decisions. Generic compatibility methods do
not choose among existing production associations.

## Migration ruling

Schema v8 could not represent authoritative later membership, so v9 was required. Migration
preserves identity, biography, artwork links/selections/focus, provenance, licence,
revisions, and exact decisions. A legacy album list becomes confirmed membership only when
its hash reproduces the association key and its source records explicit manual selection.
Everything else becomes `INSUFFICIENT` evidence. No name-derived ownership is invented.

## Result

The corrected continuity rule is: X remains visible in B-only offline views only after B has
an authoritative binding to X. An unbound B remains unassigned. X→A and Y→B remain isolated;
an exact A+B choice changes neither binding.
