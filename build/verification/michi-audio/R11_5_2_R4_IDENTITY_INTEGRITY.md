# R11.5.2-R4 identity integrity

## Authority and precedence

`RoomArtistEnrichmentSelectionStore` is the sole durable identity/profile/decision
authority. The key is concrete library, normalized credit, canonical album group, and
confirmed MBID. Current membership is an observation, not ownership.

Resolution precedence is:

1. a valid decision for the exact membership fingerprint;
2. exact canonical group evidence;
3. exactly one canonical row whose immutable album evidence overlaps the view;
4. ambiguity or no result.

An overlap row is trusted only when `hash(albumIdsJson) == albumGroupKey`. Mutable legacy
membership, row order, names, remote URLs, and equal MBIDs across separate canonical groups
cannot choose ownership.

## K08 and K09 proof

- X→A and Y→B survive an exact A+B→X choice; querying B still returns Y.
- A+B+C does not inherit A+B's exact decision.
- Unknown C receives no identity.
- Profile refresh after A→A+C preserves A as canonical evidence.
- Same-MBID groups, separate libraries, artwork slots, and focus remain isolated.
- Clear removes one identity and only decisions that chose its canonical group.
- Independent groups, libraries, profiles, decisions, and shared content-addressed artwork
  bytes survive.

Focused proof: workflow runs 0004, 0006, 0011, 0022, and 0023. File-backed concurrency
tests also verify `PRAGMA integrity_check=ok` and zero `foreign_key_check` rows.

Gate decisions:

- `ARTIST_IDENTITY_GO = PASS`
- `ARTIST_HOMONYM_INTEGRITY_GO = PASS`
- `ARTIST_ASSOCIATION_GO = PASS`
- `ARTIST_SELECTIVE_CLEAR_GO = PASS`
