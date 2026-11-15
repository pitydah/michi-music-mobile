"""K31: fail-closed reception gate tests; no Android SDK, GitHub writes, or network."""
from __future__ import annotations
from contextlib import redirect_stdout
import json
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest import TestCase, main, mock
import sys
import artist_enrichment_integration_gate as gate


class ReceiverGateTest(TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "gradlew").write_text("#!/bin/sh\n")
        (self.root / "AGENTS.md").write_text("# Test fixture only\n")
        for name in gate.REQUIRED:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("candidate\n")
        (self.root / gate.REQUIRED[0]).write_text("ArtistArtworkAutoControlGate.guarding {\n" * 3)
        (self.root / "data/src/main/java/org/michimusic/data/artist/ArtistEnrichmentCache.kt").write_text("MIGRATION_10_11 artist_credit_review_receipt\n")
        def provide(rel, text):
            path = self.root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        provide("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoScheduler.kt",
                "ArtistArtworkAutoControlGate.guarding {\n" * 3 +
                "SOURCE_FINGERPRINT to fingerprint\nsourceId != rootStamp.sourceId\n" +
                "journal.provenPendingDispatchPageForRoot(\njournal.provenNextDeferredDueAtForRoot(\n" +
                "dao.provenManualRetryPageForRoot(\n" +
                "journal.holdUnprovenEventsForRoot(\njournal.captureBindingSourceProofs(\n" +
                "journal.reviveProvenSourceEvents(\njournal.firstProvenBoundAlbumKey(\n" +
                "if (mayCaptureBindingProof)\nArtistArtworkAutoWorker.ROOT_EPOCH to rootStamp.epoch\n" +
                "suspend fun dispatchPending(requiredRootStamp: MusicRootStamp? = null)\n" +
                "currentRootStamp() != enteredRoot\n" +
                "ArtistArtworkDeferredWakeWorker.ROOT_EPOCH to rootStamp.epoch\n" +
                '"$name.e${rootStamp.epoch}"\n' +
                "WorkQuery.Builder.fromTags(listOf(TAG))\n" +
                "WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED\n" +
                "getWorkInfos(activeQuery)\n" +
                "ArtistArtworkDispatchFairness.eligible(\n" +
                ".addTag(requireNotNull(ArtistArtworkDispatchFairness.activeTag(\n" +
                "afterReadyAtMs\nif (accepted)\n")
        provide("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoWorker.kt",
                "provenPendingForRoot(\nSOURCE_FINGERPRINT\nfinishOrphanedPendingForRoot(\n" +
                "journal.provenPendingForRoot(\njournal.countVerifiedSourceBinding(\n" +
                "val action = ArtistArtworkAutoDisposition.decide(\n" +
                "inputData.getLong(ROOT_EPOCH, -1L)\nexpectedRootEpoch = expectedRootEpoch\n" +
                "requiredRootStamp = org.michimusic.data.repository.MusicRootStamp(currentSource, enteredEpoch)\n")
        provide("data/src/main/java/org/michimusic/data/artist/ArtistArtworkCatalogJournal.kt",
                "suspend fun pendingForRoot(\nsuspend fun finishOrphanedPendingForRoot(\n" +
                "INNER JOIN artist_album_credit_binding\n" +
                "suspend fun provenPendingDispatchPageForRoot(\nsuspend fun provenNextDeferredDueAtForRoot(\n" +
                "suspend fun provenManualRetryPageForRoot(\n" +
                "suspend fun countVerifiedSourceBinding(\nsuspend fun captureBindingSourceProofs(\n" +
                'primaryKeys = ["libraryId", "creditKey", "associationId", "albumKey", "sourceId"]\n')
        provide("data/src/main/java/org/michimusic/data/repository/MusicRootPreferences.kt",
                "currentRootStamp()\n" + '.putLong(KEY_ROOT_EPOCH, nextEpoch())\n' * 2)
        provide("app/src/main/java/org/michimusic/mobile/artist/ArtistEnrichmentPersistence.kt",
                "val enteredRoot = rootStampProvider()\nmayCaptureBindingProof = false\n" +
                "if (enteredRoot != rootStampProvider()) return\n")
        provide("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkCoordinator.kt",
                "expectedAutoEpoch = expectedRootEpoch\n")
        provide("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkStore.kt",
                "activeStamp.epoch != expectedRootEpoch\n")
        provide("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoWorkKey.kt",
                "sourceFingerprint: String\n")
        provide("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkCatalogProcessor.kt",
                "resumeReturnedSourceBoundRow(\nreviveExactContextOnCatalogDelta\n" +
                "journal.insertSourceCatalogCredits(\ncurrentRootStamp() != enteredRoot\n" +
                "scheduler.dispatchPending(requiredRootStamp = enteredRoot)\n")
        provide("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoBackfill.kt",
                "suspend fun reviewBoundedSession()\nnextBatch(requiredRootStamp = enteredRoot)\n" +
                "currentRootStamp() == enteredRoot\n" +
                "requiredRootStamp: MusicRootStamp? = null\nrequiredRootStamp != enteredRoot\n" +
                "currentRootStamp() != enteredRoot\n" +
                "La generación de raíz cambió antes del checkpoint\n" +
                "scheduler.dispatchPending(requiredRootStamp = enteredRoot)\n" +
                "journal.provenBindingsAfter(\n")
        provide("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkDispatchFairness.kt",
                "const val MAX_PAGES = 8\nconst val MAX_NEW_WORK = 16\n")
        provide("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkBackfillSession.kt",
                "const val MAX_BATCHES = 4\nconst val MAX_QUEUED_PER_PAGE = 12\n")
        provide("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkDeferredWakeWorker.kt",
                "inputData.getLong(ROOT_EPOCH, -1L)\n" +
                "rootStamp.epoch != enteredRootEpoch\n" +
                "dispatchPending(requiredRootStamp = rootStamp)\n")
        provide("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoDisposition.kt",
                "transientFailure\n")
        provide("app/src/main/java/org/michimusic/mobile/ui/screens/ArtistArtworkAutoSettings.kt",
                "reviewBoundedSession()\n")
        provide("data/src/main/java/org/michimusic/data/artist/ArtistEnrichmentCache.kt",
                "MIGRATION_10_11 artist_credit_review_receipt\n" +
                "artist_artwork_source_catalog_credit\nartist_artwork_source_binding_proof\n" +
                "ArtistArtworkSourceBindingProofRecord::class\n" +
                "suspend fun countProvenAutomatedArtworkEvents(\n")
        schema = self.root / "data/schemas/org.michimusic.data.artist.ArtistEnrichmentCacheDatabase/11.json"
        schema.parent.mkdir(parents=True, exist_ok=True)
        required = {
            "artist_artwork_catalog_baseline": ["libraryId"],
            "artist_artwork_catalog_track": ["libraryId", "trackHash"],
            "artist_artwork_auto_outbox": ["libraryId", "creditKey", "associationId", "albumKey", "sourceId"],
            "artist_credit_review_receipt": ["libraryId", "sourceId", "creditKey", "albumKey", "trackId"],
            "artist_artwork_source_catalog_credit": ["libraryId", "sourceId", "albumKey", "creditKey"],
            "artist_artwork_source_binding_proof": ["libraryId", "sourceId", "albumKey", "creditKey", "associationId"],
        }
        schema.write_text(json.dumps({"database": {"version": 11, "identityHash": "a"*32,
            "entities": [{"tableName": table, "primaryKey": {"columnNames": columns}}
                         for table, columns in required.items()]}}))


    def check(self, *, head=None, run_tests=False):
        output = StringIO()
        with mock.patch.object(gate, "ROOT", self.root), \
             mock.patch.object(gate, "git", return_value=head or gate.EXPECTED_BASE), \
             mock.patch.object(sys, "argv", ["receiver"] + (["--run-tests"] if run_tests else [])), \
             redirect_stdout(output):
            result = gate.main()
        return result, output.getvalue()

    def test_complete_checkout_contract_can_pass_without_marking_android_certified(self):
        code, output = self.check()
        self.assertEqual(0, code)
        self.assertIn("STATIC_INTEGRATION_RECEIPT_PASS", output)
        self.assertNotIn("GRADLE_GATES_PASS", output)

    def test_wrong_git_head_is_never_accepted(self):
        code, output = self.check(head="0" * 40)
        self.assertEqual(1, code)
        self.assertIn("rebase/review required", output)

    def test_missing_compuerta_rejects_checkout(self):
        (self.root / gate.REQUIRED[0]).write_text("no concurrency guard\n")
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("Concurrent settings cancellation gate", output)

    def test_missing_root_scope_is_rejected_before_android_build(self):
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoWorker.kt"
        path.write_text("pending(\n")
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("Worker may consume another source", output)

    def test_missing_room_receipt_rejects_checkout(self):
        (self.root / "data/src/main/java/org/michimusic/data/artist/ArtistEnrichmentCache.kt").write_text("MIGRATION_10_11\n")
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("Room v11", output)

    def test_missing_ksp_schema_must_fail_after_gradle(self):
        schema = self.root / "data/schemas/org.michimusic.data.artist.ArtistEnrichmentCacheDatabase/11.json"
        schema.unlink()
        with mock.patch.object(gate.subprocess, "run", return_value=SimpleNamespace(returncode=0)):
            code, output = self.check(run_tests=True)
        self.assertEqual(1, code)
        self.assertIn("missing or invalid KSP-exported 11.json", output)

    def test_schema_with_incorrect_primary_key_must_fail(self):
        schema = self.root / "data/schemas/org.michimusic.data.artist.ArtistEnrichmentCacheDatabase/11.json"
        data = json.loads(schema.read_text())
        data["database"]["entities"][-1]["primaryKey"]["columnNames"] = ["libraryId"]
        schema.write_text(json.dumps(data))
        with mock.patch.object(gate.subprocess, "run", return_value=SimpleNamespace(returncode=0)):
            code, output = self.check(run_tests=True)
        self.assertEqual(1, code)
        self.assertIn("Wrong Room 11 primary key", output)

    def test_missing_exact_binding_worker_guard_is_no_go(self):
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoWorker.kt"
        path.write_text("provenPendingForRoot(\nSOURCE_FINGERPRINT\n")
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("Orphaned albums", output)

    def test_missing_k31_bound_dispatch_is_fail_closed(self):
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoScheduler.kt"
        path.write_text(path.read_text().replace("journal.provenPendingDispatchPageForRoot(", "journal.pendingDispatchForRoot("))
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("K31 bound-association dispatcher", output)

    def test_missing_k31_root_fence_in_manual_session_is_fail_closed(self):
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoBackfill.kt"
        path.write_text(path.read_text().replace("nextBatch(requiredRootStamp = enteredRoot)", "nextBatch()"))
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("K31 explicit 4-page root-fenced backfill", output)

    def test_k38_without_active_state_query_rejects_checkout(self):
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoScheduler.kt"
        path.write_text(path.read_text().replace("WorkQuery.Builder.fromTags(listOf(TAG))", "getEverythingIncludingFinished()"))
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("K38 fair keyset dispatcher", output)

    def test_k38_without_proven_keyset_rejects_checkout(self):
        path = self.root / "data/src/main/java/org/michimusic/data/artist/ArtistArtworkCatalogJournal.kt"
        path.write_text(path.read_text().replace("suspend fun provenManualRetryPageForRoot(", "unsafeRetry()"))
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("K38 fair keyset dispatcher", output)

    def test_k38_without_workinfo_tag_rejects_checkout(self):
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoScheduler.kt"
        path.write_text(path.read_text().replace(".addTag(requireNotNull(ArtistArtworkDispatchFairness.activeTag(", ".addTag(unsafe("))
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("K38 fair keyset dispatcher", output)

    def test_k36_old_deferred_wake_without_epoch_is_rejected(self):
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkDeferredWakeWorker.kt"
        path.write_text(path.read_text().replace("rootStamp.epoch != enteredRootEpoch", "false"))
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("K36 generation fence missing", output)

    def test_k36_old_backfill_source_guard_is_rejected(self):
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoBackfill.kt"
        path.write_text(path.read_text().replace("requiredRootStamp != enteredRoot", "requiredRootStamp.sourceId != enteredRoot.sourceId"))
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("K36 generation fence missing", output)

    def test_k36_old_dispatch_without_root_stamp_is_rejected(self):
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoScheduler.kt"
        path.write_text(path.read_text().replace("suspend fun dispatchPending(requiredRootStamp: MusicRootStamp? = null)", "suspend fun dispatchPending()"))
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("K36 generation fence missing", output)

    def test_k32_missing_source_id_in_outbox_primary_key_is_no_go(self):
        path = self.root / "data/src/main/java/org/michimusic/data/artist/ArtistArtworkCatalogJournal.kt"
        path.write_text(path.read_text().replace(
            '"albumKey", "sourceId"]', '"albumKey"]'))
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("K32 outbox PK must isolate sourceId", output)

    def test_k32_missing_returned_root_reconciler_is_no_go(self):
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkCatalogProcessor.kt"
        path.write_text(path.read_text().replace("resumeReturnedSourceBoundRow(", "missingRootReturn("))
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("Exact-source outbox recovery", output)

    def test_k32_partial_failure_disposition_is_no_go(self):
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoDisposition.kt"
        path.write_text("old PARTIAL always receives a seven-day cooldown")
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("PARTIAL/no-image classification", output)

    def test_k32_exported_room_outbox_must_include_source_in_pk(self):
        schema = self.root / "data/schemas/org.michimusic.data.artist.ArtistEnrichmentCacheDatabase/11.json"
        doc = json.loads(schema.read_text())
        for entity in doc["database"]["entities"]:
            if entity["tableName"] == "artist_artwork_auto_outbox":
                entity["primaryKey"]["columnNames"].remove("sourceId")
        schema.write_text(json.dumps(doc))
        with mock.patch.object(gate.subprocess, "run", return_value=SimpleNamespace(returncode=0)):
            code, output = self.check(run_tests=True)
        self.assertEqual(1, code)
        self.assertIn("Wrong Room 11 primary key", output)

    def test_k33_missing_source_binding_scheduler_is_no_go(self):
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoScheduler.kt"
        path.write_text(path.read_text().replace("journal.captureBindingSourceProofs(", "bypassProof("))
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("K33 source-binding provenance missing", output)

    def test_k33_missing_source_proof_at_final_write_is_no_go(self):
        path = self.root / "data/src/main/java/org/michimusic/data/artist/ArtistEnrichmentCache.kt"
        path.write_text(path.read_text().replace("suspend fun countProvenAutomatedArtworkEvents(", "unsafeSourceQuery("))
        code, output = self.check()
        self.assertEqual(1, code)
        self.assertIn("K33 source-binding provenance missing", output)

    def test_k33_room_schema_must_have_source_proof_primary_key(self):
        schema = self.root / "data/schemas/org.michimusic.data.artist.ArtistEnrichmentCacheDatabase/11.json"
        doc = json.loads(schema.read_text())
        for entity in doc["database"]["entities"]:
            if entity["tableName"] == "artist_artwork_source_binding_proof":
                entity["primaryKey"]["columnNames"].remove("sourceId")
        schema.write_text(json.dumps(doc))
        with mock.patch.object(gate.subprocess, "run", return_value=SimpleNamespace(returncode=0)):
            code, output = self.check(run_tests=True)
        self.assertEqual(1, code)
        self.assertIn("Wrong Room 11 primary key", output)

    def test_optional_gradle_tasks_only_run_on_explicit_flag(self):
        calls = []
        def fake_run(argv, **kwargs):
            calls.append(argv)
            return SimpleNamespace(returncode=0)
        with mock.patch.object(gate.subprocess, "run", side_effect=fake_run):
            code, output = self.check(run_tests=True)
        self.assertEqual(0, code)
        self.assertEqual(5, len(calls))
        self.assertIn(":data:testDebugUnitTest", calls[0])
        self.assertIn("GRADLE_GATES_PASS", output)
        self.assertIn("ROOM_11_SCHEMA_EXPORT_PASS", output)


if __name__ == "__main__":
    main()
