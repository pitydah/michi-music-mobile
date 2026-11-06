package org.michimusic.mobile.artist

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Test

/** Small UI seam: offline cached profile loads without invoking any network gateway. */
class ArtistEnrichmentPersistenceUiTest {
    @get:org.junit.Rule val rule = createComposeRule()
    @Test fun offlineCachedProfileIsVisibleAndCanBeCleared() {
        val mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"
        var fetches = 0
        var cleared = 0
        val profile = ArtistExternalProfile(mbid, "Midnight Oil", "", "Group", "Australia")
        val gateway = object : ArtistEnrichmentGateway {
            override suspend fun findCandidates(localCredit: String): List<ArtistLookupCandidate> {
                fetches++
                return emptyList()
            }
            override suspend fun confirmedProfile(mbid: String): ArtistExternalProfile {
                fetches++
                return profile
            }
        }
        val store = object : ArtistEnrichmentSelectionStore {
            override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) =
                if (cleared == 0) PersistedArtistProfile(profile, stale = false) else null
            override suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile) {}
            override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) { cleared++ }
        }
        rule.setContent {
            ArtistEnrichmentPanel(artistCredit = "Midnight Oil", gateway = gateway,
                libraryId = "lib-one", albumIds = listOf("album-1"), selectionStore = store)
        }
        rule.onNodeWithText("MBID confirmado", substring = true).assertExists()
        assertEquals(1, fetches)
        rule.waitUntil(5000) {
            rule.onAllNodesWithTag("artist_enrichment_clear").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("artist_enrichment_clear").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, cleared)
    }
}
