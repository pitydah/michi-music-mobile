ME-13 release posture
=====================

Verdict
-------
GLOBAL: NO-GO for final certification.
LOCAL EMULATOR: the scalar MP3 Save -> physical postcheck -> exact Room CAS ->
durable receipt -> on-demand reconciliation -> typed recovery/restore chain is
implemented and passes the observed tests below.
XIAOMI: the production engine chain (real MediaStore MP3 Save -> independent
postcheck -> exact Room CAS -> journal clear) passed on the physical device.
The manual app-UI traversal on Xiaomi remains NOT RUN / BLOCKED_HARDWARE.

Separate completion measures
----------------------------
P0 code implemented and wired:        98%
  - Save/recovery-first routing, exact MP3 permit, finalizer-to-CAS publication,
    durable V2 receipt, on-demand reconciler, typed restore, review-free durable
    owner recovery, resumable cleanup, recovery UI.
  - Remaining 2%: no production Save fault-injection harness at every kill
    point, and no scripted full Library/editor UI traversal.
T01-T48 emulator/unit-validated:       32/48 PASS = 66.7%
  - 16 NOT RUN, 0 FAIL. NOT RUN is never reported as PASS.
  - See t01-t48.csv and t01-t48-report.md.
Xiaomi physical production engine:      PASS (2026-11-22, final product code)
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
- Recovery no longer needs a MetadataReview or a dirty editor draft. A durable
  owner receipt plus intent/backup/stage rebuilds the evidence and resumes the
  exact transaction; a missing or corrupt owner receipt fails closed.
- The index CAS is exact: sourceId + trackId + URI + pre-write version/SHA/size.
  A stale row is refused; an already-applied row is recognized without a second
  write.
- V2 receipts are owner-bound and actionable. V1 stays readable but cannot CAS.
  Corrupt receipts are retained.
- MetadataIndexReconciler is activated on Library open and on editor open.
- Cleanup is resumable from CLEANUP_SAFE: a kill after the phase transition or
  after any private delete continues without a second postcheck.
- MediaStore announced SIZE/DATE_MODIFIED lag is handled by content authority:
  exact bytes and SHA are required; one fresh read is retried when the stamp
  moves during the read; persistent movement or wrong content fails closed.
- Restore requires an explicit confirmation, exact MP3/journal permit,
  ROLLBACK_PENDING before opening the source, and an independent original SHA
  readback. Failure keeps both private copies pending. A source that is already
  original completes RESTORED_VERIFIED without a provider write.
- UI keeps the ME-12 four tabs, footer Revertir cambios | Guardar, and the
  existing recovery panel. Save is disabled while recovery is pending.

Known gaps before final GO
--------------------------
1. Production Save process-kill matrix: T18, T19, T20 and T43 are NOT RUN at
   their exact product points. The real kill/restart harness passed, but only
   with the synthetic provider.
2. T02-T06: no MediaStore end-to-end save for ID3v2.4, ARTIST, ALBUM, GENRE or
   four fields. Unit rewriter evidence exists, but it is not a product save.
3. T10: the real MediaStore consent dialog was not driven automatically.
4. T12/T13/T15/T36: no product-point source-race, playback-open, double-tap or
   Room-down fault injection.
5. T22: no actual reboot; the coordinator test uses a fresh instance in the same
   process.
6. T48: editor cold reopen is proven; full Library plus editor traversal is not.
7. Xiaomi manual app-UI save remains NOT RUN / BLOCKED_HARDWARE.

Safety posture
--------------
- Global write gates remain closed.
- Certified path: MediaStore MP3, ID3v2.3/v2.4, single-value TITLE/ARTIST/ALBUM/
  GENRE only.
- No personal audio was used for destructive tests; the fixture was app-owned
  and disposable.
- No push, pull request, merge or remote CI was performed in this closure.
- The original UI worktree remains on me11v-ux-corrective at d223eb74.
