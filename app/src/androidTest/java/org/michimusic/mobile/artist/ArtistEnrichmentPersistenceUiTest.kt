package org.michimusic.mobile.artist

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
        val profile = ArtistExternalProfile(
            mbid = mbid,
            name = "Midnight Oil",
            type = "Group",
            area = "Australia",
            biography = "Midnight Oil es una banda australiana de rock.",
            biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
            portrait = CommonsPortrait(
                imageUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/midnight-oil.jpg/640px-midnight-oil.jpg",
                commonsPageUrl = "https://commons.wikimedia.org/wiki/File:Midnight_Oil.jpg",
                license = "CC BY-SA 2.0",
                licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
                attribution = "Autor de la fotografía",
            ),
        )
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
        // Compose synchronization can finish before the controller's cached-profile
        // coroutine publishes its first state on a loaded CI emulator. Wait for the
        // observable contract instead of racing it; no production timeout is changed.
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("MBID confirmado por selección", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("MBID confirmado por selección", substring = true).assertExists()
        assertEquals(0, fetches)
        rule.waitUntil(5000) {
            rule.onAllNodesWithTag("artist_enrichment_clear").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("artist_enrichment_clear").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, cleared)
    }
}
