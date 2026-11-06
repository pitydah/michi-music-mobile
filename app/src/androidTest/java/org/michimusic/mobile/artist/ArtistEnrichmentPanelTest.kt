package org.michimusic.mobile.artist

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtistEnrichmentPanelTest {
    @get:org.junit.Rule val compose = createComposeRule()
    private val mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"

    private fun profile() = ArtistExternalProfile(
        mbid, "Midnight Oil", "", "Group", "Australia",
        biography = "Banda australiana.",
        biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
        biographyOutcome = ArtistMetadataOutcome.SUCCESS,
        portraitOutcome = ArtistMetadataOutcome.NO_P18,
    )

    @Test fun openingStartsAutomaticSearchAndConfirmsSingleExactCandidate() {
        var searches = 0
        var refreshes = 0
        val fake = object : ArtistEnrichmentGateway {
            override suspend fun findCandidates(localCredit: String): List<ArtistLookupCandidate> {
                searches++
                return listOf(ArtistLookupCandidate(mbid, "Midnight Oil", area = "AU"))
            }
            override suspend fun confirmedProfile(mbid: String) = profile()
            override suspend fun refreshProfile(mbid: String): ArtistExternalProfile {
                refreshes++
                return profile()
            }
        }
        compose.setContent { ArtistEnrichmentPanel(artistCredit = "Midnight Oil", gateway = fake) }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("MBID confirmado", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, searches)
        assertEquals(1, refreshes)
        compose.onNodeWithTag("artist_enrichment_search").assertDoesNotExist()
        compose.onNodeWithTag("artist_enrichment_retry_portrait").assertDoesNotExist()
        compose.onNodeWithTag("artist_artwork_openverse_search").assertDoesNotExist()
    }

    @Test fun noPortraitNeverAsksTheUserToRetryOrSearchManually() {
        val fake = object : ArtistEnrichmentGateway {
            override suspend fun findCandidates(localCredit: String) =
                listOf(ArtistLookupCandidate(mbid, "Midnight Oil", area = "AU"))
            override suspend fun confirmedProfile(mbid: String) = profile()
            override suspend fun refreshProfile(mbid: String) = profile()
        }
        compose.setContent { ArtistEnrichmentPanel(artistCredit = "Midnight Oil", gateway = fake) }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("artist_enrichment_status").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Ficha actualizada sin fotografías verificables", substring = true).assertExists()
        compose.onNodeWithTag("artist_enrichment_retry_portrait").assertDoesNotExist()
        compose.onNodeWithTag("artist_artwork_openverse_search").assertDoesNotExist()
    }

    @Test fun failedLookupShowsOneGlobalStateWithoutRetryButton() {
        val fake = object : ArtistEnrichmentGateway {
            override suspend fun findCandidates(localCredit: String): List<ArtistLookupCandidate> {
                throw java.io.IOException("provider unavailable")
            }
            override suspend fun confirmedProfile(mbid: String) = profile()
        }
        compose.setContent { ArtistEnrichmentPanel(artistCredit = "Midnight Oil", gateway = fake) }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("No fue posible completar la ficha", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("artist_enrichment_search").assertDoesNotExist()
    }

    @Test fun recompositionNeverRepeatsTheAutomaticLookup() {
        var searches = 0
        val fake = object : ArtistEnrichmentGateway {
            override suspend fun findCandidates(localCredit: String): List<ArtistLookupCandidate> {
                searches++
                return listOf(
                    ArtistLookupCandidate(mbid, "Midnight Oil", area = "AU"),
                    ArtistLookupCandidate("05072650-4b05-4e8b-83d0-dba2576e306a", "Midnight Oil (tribute)"),
                )
            }
            override suspend fun confirmedProfile(mbid: String) = profile()
        }
        var tick by mutableStateOf(0)
        compose.setContent {
            Text(if (tick >= 0) "" else "unreachable")
            ArtistEnrichmentPanel(artistCredit = "Midnight Oil", gateway = fake)
        }
        compose.waitUntil(5000) { searches == 1 }
        compose.runOnIdle { tick++ }
        compose.waitForIdle()
        assertEquals(1, searches)
    }

    @Test fun automaticConfirmationIsSingleFlightAndHidesTheCandidateWhileItRuns() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var refreshes = 0
        val fake = object : ArtistEnrichmentGateway {
            override suspend fun findCandidates(localCredit: String) =
                listOf(ArtistLookupCandidate(mbid, "Midnight Oil"))
            override suspend fun confirmedProfile(mbid: String) = profile()
            override suspend fun refreshProfile(mbid: String): ArtistExternalProfile {
                refreshes++
                gate.await()
                return profile()
            }
        }
        compose.setContent { ArtistEnrichmentPanel(artistCredit = "Midnight Oil", gateway = fake) }
        compose.waitUntil(5000) { refreshes == 1 }
        compose.onNodeWithTag("artist_candidate_$mbid").assertDoesNotExist()
        gate.complete(Unit)
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("MBID confirmado", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, refreshes)
    }
}
