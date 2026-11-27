# Physical / emulator proof

- Device: Android emulator `emulator-5554`, `Medium_Phone(AVD) - 16`, API 36.
- HEAD exercised: `28fb3832` (product `0009ed3b` plus harness commit).
- T48 flow: real Library song row menu → four-tab editor → edit TITLE → Revertir → first save → second edit/Revertir to first saved title → final save → Activity recreate → Library and editor show `ME13 T48 Final`.
- Observed assertions: `cached_tracks` persisted at `verifiedAt=0`, empty `verifiedSha256`; audio suffix byte-identical; no journal/backup/stage/owner/preimage artifacts after indexed cleanup.
- Fixtures: disposable MediaStore MP3 owned by the test/editor; no personal audio.
- Xiaomi physical HEAD exact run: **NOT RUN** in this sprint. No claim of physical certification.
- Independent-UID consent fixture (T10/N01): **NOT RUN**. App-owned fixtures do not certify external consent.
