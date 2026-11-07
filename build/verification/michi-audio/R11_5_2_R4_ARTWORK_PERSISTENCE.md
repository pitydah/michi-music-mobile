# R11.5.2-R4 artwork and offline persistence

## Durable contract

Room stores artwork ownership, provenance, licence, attribution, slot, focus, SHA-256, and
private relative path. The filesystem stores content-addressed bytes. A READY image is
usable only after private-root, non-symlink, size, SHA-256, decode, and pixel-limit checks.
Remote URLs and image-loader caches are never authority.

THUMB and HERO have independent links, selections, revisions, and focus. Clear removes
scoped ownership, not shared asset bytes. Reference-aware pruning is separate because
SQLite and filesystem operations cannot be one atomic transaction.

## Evidence

- Run 0011 used a real decodable 2×2 PNG and real SHA-256. Clearing one group preserved the
  other group's READY selection, focus, private file, and shared asset row.
- Run 0012 passed persistence, restart, transaction, and artwork tests 36/36.
- Run 0019 and final run 0031 passed API 36 instrumentation with real PNG, Room close/reopen,
  changed membership, both slots, and independent focus.
- Missing/corrupt bytes fail truthfully without erasing identity, biography, attribution,
  or a valid sibling slot.
- Startup hydration and recomposition perform no provider request. Search, refresh, local
  tag confirmation, and artwork discovery require explicit user actions.

`ARTIST_PERSISTENCE_TRUTH_GO = PASS`, `ARTIST_ARTWORK_GO = PASS`, and
`ARTIST_OFFLINE_GO = PASS`.
