#!/usr/bin/env python3
"""K36 source-integrity regressions for root-generation fencing.

These checks run against the production Kotlin source in the applied cumulative patch;
not a substitute for Android WorkManager/Room instrumented tests.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/org/michimusic/mobile/artist/artwork'

class K36GenerationBoundaries(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.backfill = (SRC / 'ArtistArtworkAutoBackfill.kt').read_text()
        cls.scheduler = (SRC / 'ArtistArtworkAutoScheduler.kt').read_text()
        cls.deferred = (SRC / 'ArtistArtworkDeferredWakeWorker.kt').read_text()
        cls.worker = (SRC / 'ArtistArtworkAutoWorker.kt').read_text()

    def test_one_user_gesture_seals_root_generation(self):
        s = self.backfill.split('suspend fun reviewBoundedSession()', 1)[1].split('suspend fun nextBatch(',1)[0]
        self.assertIn('val enteredRoot = MusicRootPreferences(app).currentRootStamp()', s)
        self.assertIn('currentRootStamp() == enteredRoot', s)
        self.assertIn('requiredRootStamp = enteredRoot', s)
        self.assertNotIn('activeSourceId() == root', s)

    def test_each_page_does_not_accept_same_source_new_epoch(self):
        s = self.backfill.split('suspend fun nextBatch(',1)[1].split('suspend fun restartReview()',1)[0]
        self.assertIn('requiredRootStamp: MusicRootStamp? = null', self.backfill)
        self.assertIn('requiredRootStamp != enteredRoot', s)
        self.assertGreaterEqual(s.count('currentRootStamp() != enteredRoot'), 4)
        self.assertNotIn('activeSourceId() != expectedSourceId', s)

    def test_cursor_must_not_advance_after_revocation(self):
        s = self.backfill.split('val committed = db.withTransaction',1)[1]
        self.assertLess(s.index('currentRootStamp() != enteredRoot'), s.index('storage.edit().putString("${key}_album"'))
        self.assertIn('La generación de raíz cambió antes del checkpoint', s)

    def test_restart_of_backfill_checks_exact_generation(self):
        s = self.backfill.split('suspend fun restartReview()',1)[1].split('companion object',1)[0]
        self.assertIn('currentRootStamp() != enteredRoot', s)
        self.assertIn('enteredRoot.sourceId', s)

    def test_dispatch_pending_uses_fenced_root(self):
        s = self.scheduler.split('suspend fun dispatchPending(',1)[1].split('private fun scheduleDeferredWake(',1)[0]
        self.assertIn('requiredRootStamp: MusicRootStamp? = null', self.scheduler)
        self.assertIn('requiredRootStamp != enteredRoot', s)
        self.assertIn('enqueue(row.libraryId, row.creditKey, row.associationId, source, enteredRoot)',s)
        self.assertIn('currentRootStamp() != enteredRoot', s)
        self.assertIn('scheduleDeferredWake(MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID, enteredRoot',s)

    def test_negative_fixture_old_source_only_guard_rejected(self):
        s = self.backfill.replace('requiredRootStamp != enteredRoot', 'requiredRootStamp.sourceId != enteredRoot.sourceId')
        self.assertNotIn('requiredRootStamp != enteredRoot',s)
        self.assertIn('requiredRootStamp != enteredRoot', self.backfill)

    def test_explicit_retry_does_not_rearm_obsolete_epoch(self):
        s = self.scheduler.split('suspend fun retryPendingExplicitly()',1)[1].split('internal fun enqueue(',1)[0]
        self.assertIn('val enteredRoot = MusicRootPreferences(app).currentRootStamp()',s)
        self.assertGreaterEqual(s.count('currentRootStamp() != enteredRoot'), 2)
        self.assertIn('source, enteredRoot)',s)

    def test_originated_calls_do_not_lose_the_entered_root(self):
        assert 'dispatchPending(requiredRootStamp = enteredRoot)' in self.scheduler
        assert 'scheduler.dispatchPending(requiredRootStamp = enteredRoot)' in self.backfill
        processor = (SRC / 'ArtistArtworkCatalogProcessor.kt').read_text()
        assert 'scheduler.dispatchPending(requiredRootStamp = enteredRoot)' in processor

    def test_deferred_wake_payload_has_epoch_and_generation_name(self):
        s = self.scheduler.split('private fun scheduleDeferredWake(',1)[1].split('suspend fun retryPendingExplicitly()',1)[0]
        self.assertIn('ArtistArtworkDeferredWakeWorker.ROOT_EPOCH to rootStamp.epoch',s)
        self.assertIn('"$name.e${rootStamp.epoch}"',s)
        self.assertIn('currentRootStamp() != rootStamp',s)

    def test_deferred_worker_rejects_stale_and_missing_epoch(self):
        self.assertIn('inputData.getLong(ROOT_EPOCH, -1L)',self.deferred)
        self.assertIn('enteredRootEpoch < 0L || rootStamp.epoch != enteredRootEpoch',self.deferred)
        self.assertIn('sourceFingerprint(libraryId, sourceId)',self.deferred)
        self.assertIn('dispatchPending(requiredRootStamp = rootStamp)',self.deferred)

    def test_regular_worker_cannot_drain_different_root_generation(self):
        self.assertEqual(2,self.worker.count('requiredRootStamp = org.michimusic.data.repository.MusicRootStamp(currentSource, enteredEpoch)'))
        self.assertIn('if (!sameRootEpoch(currentSource, enteredEpoch))',self.worker)

    def test_regular_enqueue_checks_required_stamp_immediately_before_workmanager(self):
        s = self.scheduler.split('internal fun enqueue(',1)[1].split('suspend fun setEnabled(',1)[0]
        self.assertIn('requiredRootStamp != rootStamp',s)
        self.assertIn('currentRootStamp() != rootStamp',s)
        self.assertIn('isPaused(app)',s)

    def test_no_periodic_worker_or_extra_scan_introduced(self):
        for s in (self.backfill,self.scheduler,self.deferred):
            self.assertNotIn('PeriodicWorkRequest',s)
            self.assertNotIn('MediaStore.',s)
            self.assertNotIn('queryTracks(',s)

if __name__ == '__main__': unittest.main()
