"""K39 static gates for stage/rights regressions. Kotlin JVM tests are complementary.

These tests do not certify Android, HTTP provider endpoints, or Room.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/org/michimusic/mobile/artist/artwork"

class K39ProviderStages(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.coordinator = (SRC / "ArtistArtworkCoordinator.kt").read_text()
        cls.policy = (SRC / "ArtistArtworkStagePolicy.kt").read_text()
        cls.manual = (SRC / "OpenverseArtworkProvider.kt").read_text()
        cls.rights = (SRC / "OpenverseLicenseConsistency.kt").read_text()
        cls.queries = (SRC / "OpenverseManualQueryPolicy.kt").read_text()

    def test_no_eager_primary_provider_loop(self):
        self.assertNotIn('for (provider in automaticProviders)', self.coordinator)
        self.assertIn('ArtistArtworkStagePolicy.visitUntilComplete(', self.coordinator)
        self.assertIn('hasMissing = { needs(ArtworkSlot.ARTIST_THUMB) || needs(ArtworkSlot.ARTIST_HERO) }', self.coordinator)

    def test_stage_order_keeps_primary_then_category_and_gallery(self):
        self.assertIn('val primary = automaticProviders.filterNot { it is DisabledArtworkProvider }', self.coordinator)
        self.assertIn('val stages = primary.flatMap { provider ->', self.coordinator)
        self.assertIn('provider, CommonsP180ContinuationProvider(http, commons),', self.coordinator)
        self.assertLess(self.coordinator.index('CommonsP180ContinuationProvider(http, commons)'),
                        self.coordinator.index('CommonsCategoryArtworkProvider(http, wikidata, commons),'))
        self.assertLess(self.coordinator.index('CommonsCategoryArtworkProvider(http, wikidata, commons),',self.coordinator.index('val stages =')),
                        self.coordinator.index('CommonsGalleryArtworkProvider(http, wikidata, commons),',self.coordinator.index('val stages =')))

    def test_budget_and_shared_asset_memoization(self):
        self.assertIn('MAX_ATTEMPTS_PER_SLOT: Int = 3', self.policy)
        self.assertIn('val attemptedBySlot = mutableMapOf<ArtworkSlot, MutableSet<String>>()', self.coordinator)
        self.assertIn('distinct.putIfAbsent(it.stableAssetId, it)', self.coordinator)
        self.assertIn('downloads[candidate.stableAssetId]?.let { return it }', self.coordinator)

    def test_force_refresh_checks_current_attempt_not_just_previous_ready(self):
        self.assertIn('return forceRefresh || saved[slot]?.status != ArtworkLoadStatus.READY', self.coordinator)
        self.assertIn('refreshedSlots += slot', self.coordinator)

    def test_stale_manual_selection_stays_protected(self):
        self.assertIn('.filter { it.manuallyChosen }', self.coordinator)
        self.assertNotIn('it.manuallyChosen && it.confirmedMbid.equals', self.coordinator)

    def test_manual_one_slot_is_partial_not_ready(self):
        self.assertIn('status = when (selections.values.count { it.status == ArtworkLoadStatus.READY })', self.coordinator)
        self.assertIn('1 -> ArtworkLoadStatus.PARTIAL', self.coordinator)

    def test_indexed_openverse_licence_requires_consistent_family_and_version(self):
        self.assertIn('OpenverseLicenseConsistency.acceptedLabel', self.manual)
        self.assertIn('if (declaredVersion != null && declaredVersion != actualVersion) return null', self.rights)
        self.assertIn('if (!key.startsWith(prefix)) return null', self.rights)

    def test_index_result_is_not_automatically_authoritative(self):
        self.assertIn('rights = ArtworkRights.REQUIRES_REVIEW', self.manual)
        self.assertIn('evidence = ArtworkEvidence.UNVERIFIED', self.manual)
        self.assertIn('originPageConfirmsLicense(', self.coordinator)

    def test_manual_search_variants_are_opt_in_and_bounded(self):
        self.assertIn('OpenverseManualQueryPolicy.queries(query, anchor.displayCredit)', self.manual)
        self.assertIn('const val MAX_TERMS = 2', self.queries)
        self.assertIn('if (primary.isBlank()) return emptyList()', self.queries)
        self.assertIn('is ProviderArtworkResult.Failure -> return result', self.manual)

    def test_openverse_cancellation_propagated(self):
        self.assertIn('catch (cancelled: CancellationException)', self.manual)
        self.assertIn('throw cancelled', self.manual)

if __name__ == "__main__":
    unittest.main()
