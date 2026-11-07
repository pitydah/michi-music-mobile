# R11.5.2-R2 — Artist Enrichment permanence seal

> **Deprecated:** Historical R2 record only. The normative contract is now
> [`ARTIST_ENRICHMENT_FINAL_DOMAIN_CONTRACT.md`](ARTIST_ENRICHMENT_FINAL_DOMAIN_CONTRACT.md),
> with separate identity, persistence/artwork, and UI-consumer references in this directory.

Status: implemented locally on `feat/ui2-r11-artist-artwork-federation`.
Baseline: `f45669ac82a6c678c3c5db98e223dd9cfb95a795`.

## Durable authority

Room is the sole durable authority for artist identity and artwork selections.
Provider responses are optional enrichment; Compose state and Coil cache are not
persistence. Private files under `filesDir/artist-artwork/` are the only render
source for confirmed artwork.

## Identity-first confirmation

An explicit valid MBID selection is committed before any provider request. The
minimal row records the concrete library, canonical association, local credit,
canonical MBID, explicit-selection source, confirmation time, current membership
evidence and pending enrichment. It contains no invented biography, QID, portrait
or success outcome.

If the identity commit fails, no provider is called and the UI must not claim
persistence. If MusicBrainz, Wikipedia or Commons fails afterward, the committed
MBID remains available after process death and offline restart. A different MBID
cannot silently replace an association; explicit unlink/clear is required.

## Collision-safe association resolution

`albumGroupKey` is the canonical historical association and
`membershipFingerprint` is mutable membership evidence. DAO resolution returns
all membership matches; it never uses `LIMIT 1`. Exact, membership and overlap
candidates are deduplicated by canonical group. More than one canonical candidate
is `ArtistAssociationAmbiguousException`; no row is selected or deleted. The UI
exposes an explicit ambiguity state and does not start name discovery.

Same-MBID rows in different libraries remain isolated. Homonyms and explicit MBID
changes never merge by credit/name. Room remains schema version 7; R2 needs no
migration.

## Permanent artwork

Confirmed panel, mosaic and ficha rendering accepts only a READY selection whose
verified private path is readable. Profile portrait URLs remain provenance and
attribution only. Candidate-gallery URLs are explicitly temporary discovery
previews and never imply offline persistence.

Missing or corrupt local bytes produce monogram/`CACHE_ERROR`; identity,
biography, attribution and the other valid slot remain. Opening, scrolling,
recomposition, tab changes, restart and Coil cache cleanup do not download a
confirmed image. THUMB and HERO may share bytes while retaining independent focus.

## “Todas las bibliotecas”

Aggregate artwork is eligible only after every participating concrete library has
a confirmed profile for the same MBID. Missing, error, duplicate scope or conflict
fails closed to monogram. After consensus, local THUMB selections are loaded per
association; manual selection wins, then highest persisted revision. No provider
is consulted. Per-library revision flows refresh visible aggregate tiles, and
effect cancellation prevents a previous scope result from leaking into a new one.

## UI3 freeze

UI3 must consume the existing controller/Room/artwork authority and must not add a
screen-owned fetch pipeline, URL fallback or name-based identity. Artist Enrichment
may be frozen only when all four R2 gates and final regression/CI evidence pass.
