#!/usr/bin/env python3
"""K35 fail-closed source-epoch contract checks. Not an Android integration test."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PREF = ROOT / 'data/src/main/java/org/michimusic/data/repository/MusicRootPreferences.kt'
BASE = ROOT / 'app/src/main/java/org/michimusic/mobile'
PERSIST = BASE / 'artist/ArtistEnrichmentPersistence.kt'
SCHED = BASE / 'artist/artwork/ArtistArtworkAutoScheduler.kt'
WORKER = BASE / 'artist/artwork/ArtistArtworkAutoWorker.kt'
COORD = BASE / 'artist/artwork/ArtistArtworkCoordinator.kt'
STORE = BASE / 'artist/artwork/ArtistArtworkStore.kt'
CATALOG = BASE / 'artist/artwork/ArtistArtworkCatalogProcessor.kt'

class RootEpochContract(unittest.TestCase):
    def setUp(self):
        self.prefs=PREF.read_text()
        self.persistence=PERSIST.read_text()
        self.scheduler=SCHED.read_text()
        self.worker=WORKER.read_text()
        self.coordinator=COORD.read_text()
        self.store=STORE.read_text()
        self.catalog=CATALOG.read_text()

    def test_epoch_is_persisted_on_both_root_mutation_paths(self):
        self.assertIn('private fun nextEpoch()',self.prefs)
        self.assertIn('fun setCustomRoot(',self.prefs)
        self.assertIn('fun restoreDefault()',self.prefs)
        self.assertEqual(self.prefs.count('.putLong(KEY_ROOT_EPOCH, nextEpoch())'),2)
        self.assertIn('check(old < Long.MAX_VALUE)',self.prefs)

    def test_snapshot_atomically_observes_source_and_generation(self):
        self.assertIn('fun currentRootStamp(): MusicRootStamp = synchronized(preferences)',self.prefs)
        self.assertIn('data class MusicRootStamp(val sourceId: String, val epoch: Long)',self.prefs)
        self.assertIn('preferences.getLong(KEY_ROOT_EPOCH, 0L)',self.prefs)

    def test_initial_confirmation_emits_explicit_signal_from_original_source(self):
        s=self.persistence.split('suspend fun confirmNewIdentity(',1)[1].split('override suspend fun bindAlbumsToAssociation(',1)[0]
        self.assertIn('val enteredRoot = rootStampProvider()',s)
        self.assertIn('val committed = database.withTransaction {',s)
        self.assertIn('mayCaptureBindingProof = true',s)
        self.assertIn('return committed',s)

    def test_bind_operations_capture_before_db_and_validate_after(self):
        for name,end in [('bindAlbumsToAssociation','bindUnboundAlbumsToAssociation'),
                         ('bindUnboundAlbumsToAssociation','private suspend fun bindAlbumsLocked')]:
            s=self.persistence.split('override suspend fun '+name+'(',1)[1].split(end,1)[0]
            self.assertIn('val enteredRoot = rootStampProvider()',s)
            self.assertIn('signalVerifiedAssociation(',s)
            self.assertIn('mayCaptureBindingProof = true',s)

    def test_profile_refresh_is_not_new_binding_permission(self):
        # The synthetic preimage omits portions of untouched original method headers;
        # assert the concrete post-commit wiring, not a fabricated method signature.
        self.assertIn('mayCaptureBindingProof = false',self.persistence)
        self.assertIn('val enteredRoot = rootStampProvider()',self.persistence)

    def test_changed_root_revokes_postcommit_signal(self):
        self.assertIn('if (enteredRoot != rootStampProvider()) return',self.persistence)
        self.assertIn('MusicRootPreferences(app).currentRootStamp()',self.persistence)

    def test_scheduler_captures_proof_before_qid_eligibility(self):
        s=self.scheduler.split('suspend fun onVerifiedAssociationCommitted(',1)[1].split('suspend fun dispatchPending(',1)[0]
        self.assertLess(s.index('journal.captureBindingSourceProofs('),s.index('if (anchor == null'))
        self.assertIn('if (mayCaptureBindingProof)',s)
        self.assertIn('MusicRootPreferences(app).currentRootStamp() != enteredRoot',s)

    def test_schedule_payload_contains_root_generation(self):
        self.assertIn('ArtistArtworkAutoWorker.ROOT_EPOCH to rootStamp.epoch',self.scheduler)
        self.assertIn('sourceId != rootStamp.sourceId',self.scheduler)

    def test_generation_unique_work_does_not_starve_new_root_cycle(self):
        self.assertIn('"$name.e${rootStamp.epoch}"',self.scheduler)
        self.assertIn('ExistingWorkPolicy.KEEP',self.scheduler)
        # SharedPreferences cooldown still uses stable source key, not raw root name.
        self.assertIn('nextRetryAt(app, name)',self.scheduler)

    def test_worker_revalidates_epoch_before_and_after_network(self):
        self.assertIn('inputData.getLong(ROOT_EPOCH, -1L)',self.worker)
        self.assertIn('!sameRootEpoch(expectedSourceId, expectedRootEpoch)',self.worker)
        self.assertIn('expectedRootEpoch = expectedRootEpoch',self.worker)
        self.assertIn('ROOT_EPOCH = "rootEpoch"',self.worker)

    def test_worker_stale_error_does_not_defer_other_generation(self):
        s=self.worker.split('} catch (_: Exception) {',1)[1]
        self.assertIn('if (!sameRootEpoch(currentSource, enteredEpoch))',s)
        self.assertIn('if (!sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()',self.worker)

    def test_coordinator_passes_epoch_to_atomic_store(self):
        self.assertIn('expectedAutoEpoch = expectedRootEpoch',self.coordinator)
        self.assertIn('expectedAutoSource, expectedAutoEpoch)',self.coordinator)

    def test_store_fails_closed_on_missing_epoch(self):
        self.assertIn('expectedRootEpoch == null',self.store)
        self.assertIn('activeStamp.epoch != expectedRootEpoch',self.store)
        self.assertIn('dao.persistArtworkAutomated(',self.store)

    def test_catalog_observation_cannot_checkpoint_from_old_epoch(self):
        self.assertIn('val enteredRoot = MusicRootPreferences(app).currentRootStamp()',self.catalog)
        self.assertGreaterEqual(self.catalog.count('MusicRootPreferences(app).currentRootStamp() != enteredRoot'),4)
        self.assertIn('enteredRoot = enteredRoot',self.catalog)

    def test_no_untrusted_auto_identity_or_periodic_background_job(self):
        for content in (self.scheduler,self.worker,self.catalog):
            self.assertNotIn('PeriodicWorkRequestBuilder',content)
            self.assertNotIn('confirmIdentityFromVerifiedEvidence(',content)

    def test_negative_fixture_eliminating_store_root_guard_fails_contract(self):
        self.assertIn('activeStamp.epoch != expectedRootEpoch',self.store)
        mutated=self.store.replace('activeStamp.epoch != expectedRootEpoch','false')
        self.assertNotIn('activeStamp.epoch != expectedRootEpoch',mutated)

if __name__ == '__main__': unittest.main()
