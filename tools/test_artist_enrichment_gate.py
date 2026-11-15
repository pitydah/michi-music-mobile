"""Deterministic, no-Android regression tests for the non-mutating handoff gate."""
from __future__ import annotations

import pathlib
import subprocess
import tempfile
import json
import unittest

import artist_enrichment_gate as gate


class IntegrationGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name) / "repo"
        self.root.mkdir()
        self.git("init", "-q")
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "user.name", "CI")
        for path, contents in {
            "AGENTS.md": "# rules\n",
            "gradlew": "#!/bin/sh\n",
            "settings.gradle.kts": "include(\":app\")\n",
            "gradle/libs.versions.toml": 'room = "2.7.1"\nksp = "2.1.10-1.0.31"\n',
            "app/sample.txt": "before\n",
        }.items():
            p = self.root / path
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(contents)
        self.git("add", ".")
        self.git("commit", "-qm", "baseline")
        self.head = self.git("rev-parse", "HEAD").strip()
        (self.root / "app/sample.txt").write_text("after\n")
        self.patch = pathlib.Path(self.temp.name) / "candidate.patch"
        self.patch.write_bytes(self.git_bytes("diff", "--", "app/sample.txt"))
        self.git("checkout", "--", "app/sample.txt")

    def git_bytes(self, *args: str) -> bytes:
        return subprocess.check_output(["git", "-C", str(self.root), *args])

    def git(self, *args: str) -> str:
        return self.git_bytes(*args).decode()

    def inspect(self, stage: str, *, sha: str | None = None) -> dict:
        return gate.audit(stage, self.root, self.patch, sha or self.head)

    def test_cumulative_patch_budget_accepts_130_paths_and_rejects_161(self):
        # K40 already contains 130 paths. This is a real delivery gate, not
        # an Android compilation or image-coverage assertion.
        def synthetic_patch(count: int) -> str:
            return "".join(
                f"diff --git a/tools/check_{i}.txt b/tools/check_{i}.txt\n"
                f"new file mode 100644\nindex 0000000..a123456\n"
                f"--- /dev/null\n+++ b/tools/check_{i}.txt\n"
                "@@ -0,0 +1 @@\n+ok\n" for i in range(count)
            )
        for count, accepted in ((130, True), (160, True), (161, False)):
            fixture = pathlib.Path(self.temp.name) / f"files_{count}.patch"
            fixture.write_text(synthetic_patch(count))
            _, paths, checks = gate.patch_inventory(fixture)
            self.assertEqual(count, len(paths))
            budget = next(c for c in checks if c["gate"] == "PATCH_PATH_COUNT")
            self.assertEqual(accepted, budget["pass"], budget)

    def test_preapply_accepts_correct_clean_base_without_writes(self):
        before = self.git("status", "--porcelain")
        report = self.inspect("preapply")
        self.assertEqual("STATIC_PRE_APPLY_GO", report["phase"], report)
        self.assertFalse(report["android_certified"])
        self.assertEqual(before, self.git("status", "--porcelain"))

    def test_dirty_or_wrong_head_is_blocked(self):
        self.assertEqual("BLOCKED_PRE_APPLY", self.inspect("preapply", sha="0" * 40)["phase"])
        (self.root / "app/sample.txt").write_text("dirty\n")
        self.assertEqual("BLOCKED_PRE_APPLY", self.inspect("preapply")["phase"])

    def test_duplicate_and_traversal_patch_paths_fail(self):
        original = self.patch.read_bytes()
        self.patch.write_bytes(original + original)
        self.assertEqual("BLOCKED_PRE_APPLY", self.inspect("preapply")["phase"])
        self.patch.write_bytes(original.replace(b"a/app/sample.txt", b"a/app/../sample.txt"))
        self.assertEqual("BLOCKED_PRE_APPLY", self.inspect("preapply")["phase"])

    def test_postapply_requires_real_applied_patch_and_schema(self):
        self.assertEqual("BLOCKED_POST_APPLY", self.inspect("postapply")["phase"])
        subprocess.run(["git", "-C", str(self.root), "apply", str(self.patch)], check=True)
        missing = self.inspect("postapply")
        self.assertEqual("BLOCKED_POST_APPLY", missing["phase"])
        self.assertFalse(next(x for x in missing["checks"] if x["gate"] == "ROOM_V11_SCHEMA_SHAPE_ONLY")["pass"])
        schema = self.root / "data/schemas/org.michimusic.data.artist.ArtistEnrichmentCacheDatabase/11.json"
        schema.parent.mkdir(parents=True)
        schema.write_text(json.dumps({"formatVersion": 1, "database": {
            "version": 11, "identityHash": "0123456789abcdef",
            "entities": [{"tableName": "artist_credit_review_receipt",
               "fields": [{"columnName": c} for c in ("libraryId", "sourceId", "creditKey", "albumKey",
                   "trackId", "artistMbid", "recordingMbid", "releaseMbid", "role", "verifiedAtMs")],
               "primaryKey": {"columnNames": ["libraryId", "sourceId", "creditKey", "albumKey", "trackId"]}}]
        }}))
        report = self.inspect("postapply")
        self.assertEqual("STATIC_POST_APPLY_GO", report["phase"], report)
        self.assertFalse(report["android_certified"])

    def test_concurrent_patch_file_overlap_is_blocked(self):
        peer = pathlib.Path(self.temp.name) / "genre.patch"
        peer.write_bytes(self.patch.read_bytes())
        blocked = gate.audit("preapply", self.root, self.patch, self.head, (peer,))
        self.assertEqual("BLOCKED_PRE_APPLY", blocked["phase"])
        self.assertIn("app/sample.txt", next(c for c in blocked["checks"]
                                         if c["gate"] == "PEER_PATCH_COMPATIBILITY")["detail"])
        peer.write_bytes(peer.read_bytes().replace(b"app/sample.txt", b"data/sample.txt"))
        # Different path cannot be an accidental filename collision. This is a
        # static filename check, not a claim of semantic merge compatibility.
        allowed = gate.audit("preapply", self.root, self.patch, self.head, (peer,))
        self.assertEqual("STATIC_PRE_APPLY_GO", allowed["phase"], allowed)


    def test_inventory_detects_shared_path_and_intersecting_hunks(self):
        peer = pathlib.Path(self.temp.name) / "genre.patch"
        peer.write_bytes(self.patch.read_bytes())
        report = gate.audit("inventory", self.root / "nonexistent", self.patch, self.head, (peer,))
        self.assertEqual("BLOCKED_CONCURRENT_PATCH_OVERLAP", report["phase"])
        self.assertEqual(["app/sample.txt"], report["peer_collisions"][0]["shared_paths"])
        self.assertEqual(1, len(report["peer_collisions"][0]["hunk_intersections"]))
        self.assertFalse(report["android_certified"])

    def test_peer_can_include_safe_dot_github_path(self):
        peer = pathlib.Path(self.temp.name) / "ci.patch"
        peer.write_bytes(self.patch.read_bytes().replace(b"app/sample.txt", b".github/workflows/ci.yml"))
        report = gate.audit("inventory", self.root / "nonexistent", self.patch, self.head, (peer,))
        self.assertEqual("STATIC_INVENTORY_GO", report["phase"], report)

    def test_patch_mismatched_markers_blocked(self):
        contents = self.patch.read_bytes().replace(b"+++ b/app/sample.txt", b"+++ b/data/other.txt")
        self.patch.write_bytes(contents)
        report = self.inspect("inventory")
        self.assertEqual("BLOCKED_CONCURRENT_PATCH_OVERLAP", report["phase"])
        self.assertFalse(next(c for c in report["checks"] if c["gate"] == "PATCH_SAFE_SECTIONS")["pass"])

    def test_symlink_and_rename_metadata_blocked(self):
        original = self.patch.read_bytes()
        self.patch.write_bytes(original.replace(b"--- a/app/sample.txt", b"new file mode 120000\n--- a/app/sample.txt"))
        self.assertEqual("BLOCKED_CONCURRENT_PATCH_OVERLAP", self.inspect("inventory")["phase"])
        self.patch.write_bytes(original.replace(b"--- a/app/sample.txt", b"rename from app/tricky.txt\n--- a/app/sample.txt"))
        self.assertEqual("BLOCKED_CONCURRENT_PATCH_OVERLAP", self.inspect("inventory")["phase"])

    def test_merged_metadata_artist_contract_fails_closed(self):
        for name, required in gate.MERGED_SOURCE_INVARIANTS.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("\n".join(required) + "\n")
        good = gate.merged_source_contract(self.root)
        self.assertTrue(good["pass"], good)
        repo = self.root / "data/src/main/java/org/michimusic/data/repository/LocalMediaRepository.kt"
        repo.write_text(repo.read_text().replace("else if (!needsProbe) null", "else if (false) null"))
        self.assertFalse(gate.merged_source_contract(self.root)["pass"])
        repo.write_text(repo.read_text().replace("else if (false) null", "else if (!needsProbe) null"))
        model = self.root / "core/src/main/java/org/michimusic/core/models/Models.kt"
        model.write_text(model.read_text().replace("@Transient val genreValues:", ""))
        self.assertFalse(gate.merged_source_contract(self.root)["pass"])

    def test_no_implicit_backfill_contract_rejects_enable_side_effect(self):
        sched = self.root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoScheduler.kt"
        ui = self.root / "app/src/main/java/org/michimusic/mobile/ui/screens/ArtistArtworkAutoSettings.kt"
        sched.parent.mkdir(parents=True, exist_ok=True)
        ui.parent.mkdir(parents=True, exist_ok=True)
        sched.write_text("fun setEnabled() { dispatchPending() }\n")
        good_ui = ("ArtistArtworkAutoBackfill(app)\nartist_artwork_backfill_batch\n" +
                   "suspend fun syncPreferenceState() {}\n" +
                   "finally { try { syncPreferenceState() } finally { busy = false } }\n" * 3)
        ui.write_text(good_ui)
        self.assertTrue(gate.no_implicit_backfill_contract(self.root)["pass"])
        sched.write_text(sched.read_text() + "enqueueInitialBackfill()\n")
        self.assertFalse(gate.no_implicit_backfill_contract(self.root)["pass"])
        sched.write_text(sched.read_text().replace("enqueueInitialBackfill()\n", ""))
        ui.write_text(good_ui.replace("finally { try { syncPreferenceState() } finally { busy = false } }", "finally { busy = false }", 1))
        self.assertFalse(gate.no_implicit_backfill_contract(self.root)["pass"])

    def test_commons_batch_contract_protects_budget_and_provider_wiring(self):
        prefix = "app/src/main/java/org/michimusic/mobile/artist/artwork/"
        def write(name: str, body: str):
            path = self.root / (prefix + name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body)
        write("CommonsDepictionBatchPolicy.kt",
              "const val MAX_PROBE_FILES = 12\nvalidWikidataQid(artistQid)\ndepictsByMediaId\n")
        write("CommonsCategoryArtworkProvider.kt", "commons.depictsBatch(members, qid)\n")
        write("CommonsArtworkProviders.kt", """suspend fun depictsBatch() {}
.addQueryParameter("action", "wbgetentities")
internal class CommonsP180Provider
.addQueryParameter("srlimit", "12")
commons.depictsBatch(distinct, qid)
internal class WikipediaPageImageProvider
""")
        self.assertTrue(gate.commons_batch_contract(self.root)["pass"])
        path = self.root / (prefix + "CommonsArtworkProviders.kt")
        path.write_text(path.read_text().replace("commons.depictsBatch(distinct, qid)",
                                                "commons.depicts(file, qid)"))
        self.assertFalse(gate.commons_batch_contract(self.root)["pass"])
        path.write_text(path.read_text().replace("commons.depicts(file, qid)",
                                                "commons.depictsBatch(distinct, qid)"))
        write("CommonsDepictionBatchPolicy.kt",
              "const val MAX_PROBE_FILES = 100\nvalidWikidataQid(artistQid)\ndepictsByMediaId\n")
        self.assertFalse(gate.commons_batch_contract(self.root)["pass"])

    def test_schema_without_review_receipt_is_not_static_go(self):
        schema = self.root / "data/schemas/org.michimusic.data.artist.ArtistEnrichmentCacheDatabase/11.json"
        schema.parent.mkdir(parents=True, exist_ok=True)
        schema.write_text(json.dumps({"database": {"version": 11, "identityHash": "1" * 32,
            "entities": [{"tableName": "artist_enrichment"}]}}))
        self.assertFalse(gate.room_review_schema_shape(schema))
        schema.write_text(json.dumps({"database": {"version": 11, "identityHash": "1" * 32,
            "entities": [{"tableName": "artist_credit_review_receipt", "fields": [
                {"columnName": c} for c in ("libraryId", "sourceId", "creditKey", "albumKey", "trackId",
                "artistMbid", "recordingMbid", "releaseMbid", "role", "verifiedAtMs")],
                "primaryKey": {"columnNames": ["trackId", "albumKey"]}}]}}))
        self.assertFalse(gate.room_review_schema_shape(schema))

    def test_review_receipt_gate_requires_exact_ui_and_room_wiring(self):
        locations = {
            "app/src/main/java/org/michimusic/mobile/artist/ArtistEnrichmentPanel.kt":
                "reviewReceipts.save(reviewScope, claim, result)\nreviewReceipts.load(reviewScope, claim)\nArtistStructuredCreditClaimPlanner.plan(artistCredit, evidence)",
            "app/src/main/java/org/michimusic/mobile/artist/ArtistCreditReviewReceiptPersistence.kt":
                "ArtistCreditReviewReceiptPolicy.create\ndb.withTransaction\nsourceId() != scope.sourceId",
            "data/src/main/java/org/michimusic/data/artist/ArtistEnrichmentCache.kt":
                "ArtistCreditReviewReceiptRecord::class\nCREATE TABLE IF NOT EXISTS `artist_credit_review_receipt`\nsuspend fun getCreditReviewReceipt\nsuspend fun putCreditReviewReceipt",
        }
        for name, body in locations.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body)
        self.assertTrue(gate.review_receipt_contract(self.root)["pass"])
        path = self.root / "app/src/main/java/org/michimusic/mobile/artist/ArtistEnrichmentPanel.kt"
        path.write_text(path.read_text().replace("reviewReceipts.save(reviewScope, claim, result)", ""))
        self.assertFalse(gate.review_receipt_contract(self.root)["pass"])

    def test_missing_patch_is_blocked(self):
        self.patch.unlink()
        self.assertEqual("BLOCKED_PRE_APPLY", self.inspect("preapply")["phase"])


if __name__ == "__main__":
    unittest.main()
