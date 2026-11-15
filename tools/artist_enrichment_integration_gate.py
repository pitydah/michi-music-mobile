#!/usr/bin/env python3
"""Artist Enrichment K38 — real-checkout receipt gate. Read-only unless --run-tests.

Run after applying the cumulative patch in a *real* Michi Music Mobile checkout.
This does not edit code, run scans, create commits, or mark an APK release-ready.
"""
from __future__ import annotations
import argparse
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXPECTED_BASE = "b15b42c3f9cad5f7bf9da8e43edf4963bd14f090"  # K46 candidate integrated on the MI-26 follow-up
REQUIRED = [
    "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoScheduler.kt",
    "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoControlGate.kt",
    "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoWorker.kt",
    "app/src/main/java/org/michimusic/mobile/artist/artwork/CommonsGalleryArtworkProvider.kt",
    "app/src/main/java/org/michimusic/mobile/artist/ArtistCreditReviewReceiptPersistence.kt",
    "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoBackfill.kt",
    "data/src/main/java/org/michimusic/data/artist/ArtistArtworkCatalogJournal.kt",
    "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoWorkKey.kt",
    "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkCatalogProcessor.kt",
    "tools/test_artist_artwork_root_fence.py",
    "tools/test_artist_artwork_bound_pending.py",
    "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkBackfillSession.kt",
    "app/src/test/java/org/michimusic/mobile/artist/artwork/ArtistArtworkBackfillSessionTest.kt",
    "tools/test_artist_artwork_bound_dispatch.py",
    "data/src/test/java/org/michimusic/data/artist/ArtistArtworkBoundDispatchTest.kt",
    "data/src/test/java/org/michimusic/data/artist/ArtistArtworkSourceProvenanceRoomTest.kt",
    "tools/test_artist_artwork_source_provenance.py",
    "data/src/main/java/org/michimusic/data/artist/ArtistEnrichmentCache.kt",
    "data/src/main/java/org/michimusic/data/repository/MusicRootPreferences.kt",
    "data/src/test/java/org/michimusic/data/repository/MusicRootStampTest.kt",
    "tools/test_artist_artwork_root_epoch.py",
    "tools/test_artist_artwork_generation_boundaries.py",
    "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkDispatchFairness.kt",
    "app/src/test/java/org/michimusic/mobile/artist/artwork/ArtistArtworkDispatchFairnessTest.kt",
    "tools/test_artist_artwork_dispatch_fairness.py",
    "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkDeferredWakeWorker.kt",
]

def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-tests", action="store_true", help="Run real Gradle unit/build gates; no commits")
    args = parser.parse_args()
    failures: list[str] = []
    try:
        base = git("rev-parse", "HEAD")
        if base != EXPECTED_BASE:
            failures.append(f"HEAD {base} differs from candidate base {EXPECTED_BASE}; rebase/review required")
        if not (ROOT / "gradlew").is_file():
            failures.append("gradlew unavailable: not a complete Android checkout")
    except (OSError, subprocess.CalledProcessError) as exc:
        failures.append(f"git/checkout unavailable: {exc}")
    if not (ROOT / "AGENTS.md").is_file():
        failures.append("AGENTS.md missing: cannot verify repository-specific build protocol")
    for path in REQUIRED:
        if not (ROOT / path).is_file():
            failures.append(f"Missing integrated path: {path}")
    scheduler = (ROOT / REQUIRED[0]).read_text() if (ROOT / REQUIRED[0]).is_file() else ""
    if scheduler.count("ArtistArtworkAutoControlGate.guarding {") != 3:
        failures.append("Concurrent settings cancellation gate is not integrated")
    # A source switch is more than a preference change: old WorkManager jobs and
    # stale outbox evidence must not suppress or mutate new-root jobs.
    def candidate(relpath: str) -> str:
        path = ROOT / relpath
        return path.read_text() if path.is_file() else ""
    worker = candidate("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoWorker.kt")
    journal = candidate("data/src/main/java/org/michimusic/data/artist/ArtistArtworkCatalogJournal.kt")
    work_key = candidate("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoWorkKey.kt")
    processor = candidate("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkCatalogProcessor.kt")
    if "SOURCE_FINGERPRINT to fingerprint" not in scheduler or "sourceId != rootStamp.sourceId" not in scheduler:
        failures.append("WorkManager dispatch is not fenced by the active music root")
    if "provenPendingForRoot(" not in worker or "SOURCE_FINGERPRINT" not in worker:
        failures.append("Worker may consume another source's pending events")
    if "sourceFingerprint: String" not in work_key:
        failures.append("Unique WorkManager key is not root-scoped")
    if ("reviveExactContextOnCatalogDelta" not in processor or
        "resumeReturnedSourceBoundRow(" not in processor or
        "suspend fun pendingForRoot(" not in journal):
        failures.append("Exact-source outbox recovery/selection is incomplete")
    if "finishOrphanedPendingForRoot(" not in worker or        "INNER JOIN artist_album_credit_binding" not in journal or        "suspend fun finishOrphanedPendingForRoot(" not in journal:
        failures.append("Orphaned albums can starve confirmed bindings in bounded worker pages")
    # K31: do not let stale outbox rows monopolize a 128-association window;
    # an explicit tap must be bounded and may not switch roots between pages.
    backfill = candidate("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoBackfill.kt")
    backfill_policy = candidate("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkBackfillSession.kt")
    settings = candidate("app/src/main/java/org/michimusic/mobile/ui/screens/ArtistArtworkAutoSettings.kt")
    if ("journal.provenPendingDispatchPageForRoot(" not in scheduler or
        "journal.provenNextDeferredDueAtForRoot(" not in scheduler or
        "dao.provenManualRetryPageForRoot(" not in scheduler or
        "suspend fun provenPendingDispatchPageForRoot(" not in journal or
        "suspend fun provenNextDeferredDueAtForRoot(" not in journal or
        "suspend fun provenManualRetryPageForRoot(" not in journal):
        failures.append("K31 bound-association dispatcher/retry/wakeup is not integrated")
    # K38: WorkManager KEEP is not new work. Skip its ACTIVE jobs before counting
    # sixteen new dispatch slots, with a keyset scan and exact provenance in SQL.
    fairness = candidate("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkDispatchFairness.kt")
    if ("WorkQuery.Builder.fromTags(listOf(TAG))" not in scheduler or
        "WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED" not in scheduler or
        "getWorkInfos(activeQuery)" not in scheduler or
        "ArtistArtworkDispatchFairness.eligible(" not in scheduler or
        ".addTag(requireNotNull(ArtistArtworkDispatchFairness.activeTag(" not in scheduler or
        "afterReadyAtMs" not in scheduler or
        "if (accepted)" not in scheduler or
        "const val MAX_PAGES = 8" not in fairness or
        "const val MAX_NEW_WORK = 16" not in fairness or
        "suspend fun provenPendingDispatchPageForRoot(" not in journal or
        "suspend fun provenManualRetryPageForRoot(" not in journal):
        failures.append("K38 fair keyset dispatcher/WorkManager active-state guard is missing")
    if ("reviewBoundedSession()" not in settings or
        "suspend fun reviewBoundedSession()" not in backfill or
        "nextBatch(requiredRootStamp = enteredRoot)" not in backfill or
        "currentRootStamp() == enteredRoot" not in backfill or
        "const val MAX_BATCHES = 4" not in backfill_policy or
        "const val MAX_QUEUED_PER_PAGE = 12" not in backfill_policy):
        failures.append("K31 explicit 4-page root-fenced backfill is missing or unbounded")
    cache = candidate("data/src/main/java/org/michimusic/data/artist/ArtistEnrichmentCache.kt")
    if ("albumKey\", \"sourceId" not in journal and
        'primaryKeys = ["libraryId", "creditKey", "associationId", "albumKey", "sourceId"]' not in journal):
        failures.append("K32 outbox PK must isolate sourceId; old roots can overwrite new roots")
    disposition = candidate("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoDisposition.kt")
    if ("transientFailure" not in disposition or "val action = ArtistArtworkAutoDisposition.decide(" not in worker):
        failures.append("K32 PARTIAL/no-image classification may suppress retries for seven days")
    if "MIGRATION_10_11" not in cache or "artist_credit_review_receipt" not in cache:
        failures.append("Artist Room v11 migration or review receipt is missing")
    # K33: even verified MBID/album cannot authorize cross-root artwork without
    # source-specific user-binding provenance, validated before HTTP AND at save.
    backfill_source = candidate("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoBackfill.kt")
    if ("journal.holdUnprovenEventsForRoot(" not in scheduler or
        "journal.captureBindingSourceProofs(" not in scheduler or
        "journal.reviveProvenSourceEvents(" not in scheduler or
        "journal.firstProvenBoundAlbumKey(" not in scheduler or
        "journal.provenPendingForRoot(" not in worker or
        "journal.countVerifiedSourceBinding(" not in worker or
        "journal.provenBindingsAfter(" not in backfill_source or
        "suspend fun countVerifiedSourceBinding(" not in journal or
        "suspend fun captureBindingSourceProofs(" not in journal or
        "suspend fun countProvenAutomatedArtworkEvents(" not in cache or
        "artist_artwork_source_catalog_credit" not in cache or
        "artist_artwork_source_binding_proof" not in cache or
        "ArtistArtworkSourceBindingProofRecord::class" not in cache or
        "journal.insertSourceCatalogCredits(" not in processor):
        failures.append("K33 source-binding provenance missing: cross-root auto-artwork must fail closed")
    # K35 RootGeneration fence: no A->B->A proof, job, or write may rely on sourceId alone.
    root_prefs = candidate("data/src/main/java/org/michimusic/data/repository/MusicRootPreferences.kt")
    persistence = candidate("app/src/main/java/org/michimusic/mobile/artist/ArtistEnrichmentPersistence.kt")
    coordinator = candidate("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkCoordinator.kt")
    store = candidate("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkStore.kt")
    if ("currentRootStamp()" not in root_prefs or
        root_prefs.count('.putLong(KEY_ROOT_EPOCH, nextEpoch())') != 2 or
        "val enteredRoot = rootStampProvider()" not in persistence or
        "mayCaptureBindingProof = false" not in persistence or
        "if (enteredRoot != rootStampProvider()) return" not in persistence or
        "if (mayCaptureBindingProof)" not in scheduler or
        "ArtistArtworkAutoWorker.ROOT_EPOCH to rootStamp.epoch" not in scheduler or
        "inputData.getLong(ROOT_EPOCH, -1L)" not in worker or
        "expectedRootEpoch = expectedRootEpoch" not in worker or
        "expectedAutoEpoch = expectedRootEpoch" not in coordinator or
        "activeStamp.epoch != expectedRootEpoch" not in store or
        "currentRootStamp() != enteredRoot" not in processor):
        failures.append("K35 persistent root epoch fence missing from semantic commit/worker/final artwork write")
    # K36: A->B->A can retain sourceId while changing the authorization epoch.
    # Guard every local replay entrypoint, not just the primary provider worker.
    wake = candidate("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkDeferredWakeWorker.kt")
    if ("requiredRootStamp: MusicRootStamp? = null" not in backfill or
        "currentRootStamp() != enteredRoot" not in backfill or
        "La generación de raíz cambió antes del checkpoint" not in backfill or
        "requiredRootStamp != enteredRoot" not in backfill or
        "currentRootStamp() != enteredRoot" not in scheduler or
        "requiredRootStamp: MusicRootStamp? = null" not in scheduler or
        "ArtistArtworkDeferredWakeWorker.ROOT_EPOCH to rootStamp.epoch" not in scheduler or
        '"$name.e${rootStamp.epoch}"' not in scheduler or
        "inputData.getLong(ROOT_EPOCH, -1L)" not in wake or
        "rootStamp.epoch != enteredRootEpoch" not in wake or
        "dispatchPending(requiredRootStamp = rootStamp)" not in wake or
        "scheduler.dispatchPending(requiredRootStamp = enteredRoot)" not in backfill or
        "scheduler.dispatchPending(requiredRootStamp = enteredRoot)" not in processor or
        "requiredRootStamp = org.michimusic.data.repository.MusicRootStamp(currentSource, enteredEpoch)" not in worker):
        failures.append("K36 generation fence missing from backfill/deferred wake/dispatch")
    print("HEAD:", locals().get("base", "unknown"))
    print("CONTRACT: no autoconfirmation, no background scans, no GitHub writes")
    print("Gate: ", "FAIL" if failures else "STATIC_INTEGRATION_RECEIPT_PASS")
    for failure in failures:
        print("BLOCKER:", failure)
    if failures or not args.run_tests:
        return 1 if failures else 0
    # Real Gradle/KSP must generate the schema. We deliberately do not invent or
    # ship a fake identityHash in this patch. The generated 11.json must be checked
    # after the data tests and committed by the integrating developer.
    tasks = [":data:testDebugUnitTest", ":app:testNormalDebugUnitTest", ":app:testFdroidDebugUnitTest",
             ":app:assembleNormalDebug", ":app:assembleFdroidDebug"]
    for task in tasks:
        print("Running", task, flush=True)
        process = subprocess.run([str(ROOT / "gradlew"), "--no-daemon", task], cwd=ROOT)
        if process.returncode:
            print("ANDROID_NO_GO: failing gate", task)
            return process.returncode
    schema = ROOT / "data/schemas/org.michimusic.data.artist.ArtistEnrichmentCacheDatabase/11.json"
    try:
        document = json.loads(schema.read_text())
        database = document["database"]
        entities = {item["tableName"]: item for item in database["entities"]}
        expected = {
            "artist_artwork_catalog_baseline": ["libraryId"],
            "artist_artwork_catalog_track": ["libraryId", "trackHash"],
            "artist_artwork_auto_outbox": ["libraryId", "creditKey", "associationId", "albumKey", "sourceId"],
            "artist_credit_review_receipt": ["libraryId", "sourceId", "creditKey", "albumKey", "trackId"],
            "artist_artwork_source_catalog_credit": ["libraryId", "sourceId", "albumKey", "creditKey"],
            "artist_artwork_source_binding_proof": ["libraryId", "sourceId", "albumKey", "creditKey", "associationId"],
        }
        if database["version"] != 11 or len(database.get("identityHash", "")) != 32:
            raise ValueError("Room 11 version/hash not generated correctly")
        for table, primary_key in expected.items():
            actual = entities[table]["primaryKey"]["columnNames"]
            if actual != primary_key:
                raise ValueError(f"Wrong Room 11 primary key for {table}: {actual}")
    except (OSError, ValueError, KeyError, TypeError) as error:
        print("ANDROID_NO_GO: missing or invalid KSP-exported 11.json:", error)
        return 1
    print("GRADLE_GATES_PASS; ROOM_11_SCHEMA_EXPORT_PASS")
    print("Instrumented migration/device smoke/rights audit remain mandatory before RELEASE_GO")
    return 0

if __name__ == "__main__":
    sys.exit(main())
