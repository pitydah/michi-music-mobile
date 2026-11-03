package org.michimusic.mobile.artist

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtistEnrichmentPanelTest {
    @get:org.junit.Rule val compose = createComposeRule()
    private val mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"

    @Test fun explicitSearchAndSelectionAreRequiredBeforeRemoteProfileAppears() {
        var searches = 0
        var profiles = 0
        val fake = object : ArtistEnrichmentGateway {
            override suspend fun findCandidates(localCredit: String): List<ArtistLookupCandidate> {
                searches++
                assertEquals("Midnight Oil", localCredit)
                return listOf(ArtistLookupCandidate(mbid, "Midnight Oil", area = "AU"))
            }
            override suspend fun confirmedProfile(mbid: String): ArtistExternalProfile {
                profiles++
                return ArtistExternalProfile(mbid, "Midnight Oil", "", "Group", "Australia")
            }
        }
        compose.setContent { ArtistEnrichmentPanel(artistCredit = "Midnight Oil", gateway = fake) }
        compose.waitForIdle()
        assertEquals(0, searches)
        assertEquals(0, profiles)
        compose.onNodeWithTag("artist_enrichment_search").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("artist_candidate_$mbid").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, searches)
        assertEquals(0, profiles)
        compose.onNodeWithTag("artist_candidate_$mbid").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("MBID confirmado", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, profiles)
        compose.onNodeWithText("MBID confirmado", substring = true).assertExists()
    }
}
