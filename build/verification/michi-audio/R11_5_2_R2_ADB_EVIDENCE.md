# R11.5.2-R2 — ADB and emulator evidence

Device: isolated `Medium_Phone` API 36 emulator (`emulator-5554`). Physical
device/TalkBack/Bluetooth: **NOT_RUN**. No `pm clear`, uninstall or destructive
operation was performed on a physical user device.

## Direct RED/GREEN

- K03 RED: two tests, two failures; semantics found remote hero and mosaic image
  nodes without a private selection.
- K03 GREEN: the same methods returned `OK (2 tests)`.
- K04 RED: same-MBID aggregate local-artwork test timed out because no photo node
  appeared.
- K04 GREEN: the same method returned `OK (1 test)`.
- Complete mosaic class: `OK (16 tests)`.
- Hero + persistence + durable association + manual route: `OK (9 tests)`.

## API 36 and live providers

- Final connected suite at reviewed source commit `c7face3`: **77 tests**,
  **0 failed**, **10 designed opt-in skips**, `BUILD SUCCESSFUL` (workflow run
  0024). Run 0018 supplied the same result before the review follow-up.
- Live opt-in: MusicBrainz/Wikidata/Wikipedia/Commons/Openverse paths **6/6 PASS**
  in 32.287 s.

## Persistence contracts represented

- Identity-only file-backed Room close/reopen: same MBID and association, null
  biography/portrait, no provider needed.
- Durable association E2E: add album, remove album, clear; profile + two private
  slots survive the valid transitions.
- Existing valid PNG/JPEG fixtures verify decode, dimensions, SHA-256, focus and
  shared-byte independent crops.
- Aggregate UI uses only local selection paths; profile URLs cannot create Coil
  requests in hero/mosaic/panel.

Potentially destructive connected instrumentation ran only on the isolated
emulator. Non-destructive update and offline restart evidence from R11.5.2-R
remains applicable to unchanged Room v7/private-file contracts; R2 adds stronger
identity-first, collision and local-render guarantees.
