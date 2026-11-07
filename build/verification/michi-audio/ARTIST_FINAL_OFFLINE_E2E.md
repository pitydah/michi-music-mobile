# Artist Enrichment final offline and Android evidence

## Local/offline continuity

File-backed restart tests confirm:

- identity-only confirmation survives Room close and offline reopen;
- a failed profile update preserves prior biography, artwork, and focus;
- exact ambiguity decisions survive reopen without transferring subset ownership;
- real decoded private artwork and profile facts survive repeated reopen;
- after B is explicitly bound, B-only recovers the same association offline after A
  disappears, with THUMB/HERO focus intact.

No startup path invokes MusicBrainz or another provider. Fresh contexts wait for an explicit
search; recovered unbound albums offer an explicit local bind.

## API36 isolated emulator

Only `emulator-5554` (`Medium_Phone`, API36) was used. Full Normal instrumentation in Gradle
workflow run 0025 completed with runner output 80 tests; XML records 70 cases, 10 designed
skips, zero failures/errors. It covers explicit provider selection, local binding without
HTTP, exact historical ambiguity, late-response isolation, current-panel persistence truth,
real private PNG persistence, both artwork slots/focus, membership changes, reopen, and
selective clear.

The four Normal/F-Droid debug/release APK variants assembled in run 0029. Run 0028 was
interrupted by the 120-second harness limit with no Gradle failure; the identical retry
completed successfully.

## Fixtures versus live providers

The deterministic Android/domain certification uses fixtures and local real image bytes.
Live-provider tests for named artists are opt-in and were among designed skips when live
credentials/network qualification were unavailable. Therefore this report does **not** claim
new live-provider certification for Midnight Oil, A Flock Of Seagulls, or Martika. Their
domain outcomes (full profile/artwork, legitimate no-photo, and partial/no-biography) remain
covered by deterministic fixture tests.

## Unrun physical gates

No physical device, TalkBack session, Bluetooth route, or destructive user-install operation
was used. `PHYSICAL_DEVICE_GO` remains `NOT_RUN`.
