ME-13 release posture
=====================

Verdict
-------
GLOBAL: NO-GO for final certification.
LOCAL EMULATOR: the scalar MP3 Save -> physical postcheck -> exact Room CAS ->
durable receipt -> on-demand reconciliation -> typed restore chain is implemented
and passes the observed tests below.
XIAOMI: the production engine chain (real MediaStore MP3 Save -> independent
postcheck -> exact Room CAS -> journal clear) passed on the physical device.
The manual app-UI traversal on Xiaomi remains NOT RUN / BLOCKED_HARDWARE.

Separate completion measures
----------------------------
P0 code implemented and wired:        95%
  - Save/recovery-first routing, exact MP3 permit, finalizer-to-CAS publication,
    durable V2 receipt, on-demand reconciler, typed restore, recovery UI.
  - The 5% not claimed: no production Save fault-injection harness, and some
    manual UI traversals were not scripted.
W/X matrix emulator-validated:        28/40 PASS = 70.0%
  - W01-W24: 17/24 PASS, 7 NOT RUN.
  - X01-X16: 11/16 PASS, 5 NOT RUN.
  - NOT RUN is never reported as PASS.
Xiaomi physical production engine:      PASS (2026-11-22)
  - Real MediaStore MP3, production MetadataSaveService, independent postcheck,
    exact cached_tracks CAS, indexPublished=true, journal Clear.
  - Evidence: physical-xiaomi-e2e.txt, physical-xiaomi-logcat-excerpt.txt.
Xiaomi physical manual UI traversal:    0% (NOT RUN / BLOCKED_HARDWARE)
  - HyperOS denies automated input injection (INJECT_EVENTS); no human tap run.
  - The engine PASS is not inflated into a UI certification.
Historical baseline 08a0b750:          one real POSTCHECK_VERIFIED save, excluded
                                       from final-HEAD certification.
S7 expanded editor (fields/APIC/FLAC): 0% (disabled by design)
  - The scalar profile still accepts only single-value TITLE/ARTIST/ALBUM/GENRE
    on MediaStore MP3 ID3v2.3/2.4.
  - Artwork, multivalue, FLAC, SAF and MP4 are rejected. No certification is
    transferred between formats.

What is actually connected
--------------------------
- MetadataSaveService no longer has the direct publish()/runBlocking TrackDao
  fallback. New and resumed saves go through
  MetadataFinalizedIndexPublicationCoordinator with a MetadataWritePermit.
- The index CAS is exact: sourceId + trackId + URI + pre-write version/SHA/size.
  A stale row is refused; an already-applied row is recognized without a second
  write.
- V2 receipts are owner-bound and actionable. V1 stays readable but cannot CAS.
  Corrupt receipts are retained.
- MetadataIndexReconciler is activated on Library open and on editor open.
- Restore requires an explicit confirmation, exact MP3/journal permit,
  ROLLBACK_PENDING before opening the source, and an independent original SHA
  readback. Failure keeps both private copies pending.
- UI keeps the ME-12 four tabs, footer Revertir cambios | Guardar, and the
  existing recovery panel. Save is disabled while recovery is pending.

Known gaps before final GO
--------------------------
1. Production Save process-kill matrix: W05, W06, W09, W13 and X04/X14 are not
   demonstrated at their exact product points. The real kill/restart harness
   passed, but only with the synthetic provider.
2. Scripted UI traversal Library -> Editor -> Save and Genres -> Editor -> Save
   (W17/W18) was not run on the emulator. Backend E2E and Compose tests passed.
3. No deterministic MediaStore SIZE-lag test (X01) or 500->600 resize (W21).
4. No forced completeVerified() failure test (X12), and no row-appears-later
   test (X08).
5. Xiaomi engine path is physically certified, but the manual app-UI save and
   Library -> Editor -> Save traversal are still NOT RUN because HyperOS denies
   adb input; that UI certification requires a human tap run.

Safety posture
--------------
- Global write gates remain closed.
- Certified path: MediaStore MP3, ID3v2.3/v2.4, single-value TITLE/ARTIST/ALBUM/
  GENRE only.
- No personal audio was used for destructive tests; the fixture was app-owned
  and disposable.
- No push, pull request, merge or remote CI was performed.
- The original UI worktree remains on me11v-ux-corrective at d223eb74.
