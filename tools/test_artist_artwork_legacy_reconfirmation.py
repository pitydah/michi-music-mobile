"""K37 independent source-contract regressions; do not impersonate Android tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


class LegacyReconfirmationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.store = read("app/src/main/java/org/michimusic/mobile/artist/ArtistEnrichmentPersistence.kt")
        cls.controller = read("app/src/main/java/org/michimusic/mobile/artist/ArtistEnrichmentController.kt")
        cls.panel = read("app/src/main/java/org/michimusic/mobile/artist/ArtistEnrichmentPanel.kt")
        cls.worker = read("app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoWorker.kt")

    def test_reconfirmation_requires_existing_exact_album_binding(self):
        segment = self.store.split("override suspend fun reconfirmExistingAlbumBinding(", 1)[1].split(
            "private suspend fun bindAlbumsLocked(", 1)[0]
        self.assertIn("dao.getAlbumCreditBinding(libraryId, albumId, creditKey)", segment)
        self.assertIn("require(bound.associationId == associationId)", segment)
        self.assertLess(segment.index("require(bound.associationId == associationId)"), segment.index("bindAlbumsLocked("))

    def test_reconfirmation_rechecks_identity_and_timestamp_inside_transaction(self):
        segment = self.store.split("override suspend fun reconfirmExistingAlbumBinding(", 1)[1].split(
            "private suspend fun bindAlbumsLocked(", 1)[0]
        self.assertIn("database.withTransaction", segment)
        self.assertIn("identity.confirmedMbid == canonicalMbid", segment)
        self.assertIn("now > bound.confirmedAtMs", segment)
        self.assertIn("mayCaptureBindingProof = true", segment)
        self.assertIn("val enteredRoot = rootStampProvider()", segment)

    def test_ui_is_explicit_and_bounded(self):
        self.assertIn("artist_enrichment_reconfirm_bound_album_$index", self.panel)
        self.assertIn("activeController.reconfirmVisibleAlbum(", self.panel)
        self.assertIn("}.take(8)", self.panel)
        self.assertIn("!uiState.refreshing", self.panel)

    def test_controller_never_reconfirms_unbound_or_ambiguous_identity(self):
        segment = self.controller.split("fun reconfirmVisibleAlbum(", 1)[1].split("fun bindVisibleAlbums(", 1)[0]
        self.assertIn("albumId in current.unboundAlbumIds", segment)
        self.assertIn("current.persistedMbid", segment)
        self.assertIn("current.profilePersisted", segment)
        self.assertIn("bindingStore.reconfirmExistingAlbumBinding", segment)
        self.assertIn("epoch != requestEpoch", segment)

    def test_root_stale_worker_does_not_finish_pending_without_recheck(self):
        segment = self.worker.split("val associations = dao.getDurableAssociationsForCredit", 1)[1].split(
            "val pending = journal.provenPendingForRoot", 1)[0]
        self.assertIn("if (!sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()", segment)
        self.assertLess(segment.index("if (!sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()"), segment.index("journal.finishOrphanedPendingForRoot"))


if __name__ == "__main__":
    unittest.main()
