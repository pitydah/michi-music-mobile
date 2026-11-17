package org.michimusic.mobile.artist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtworkRights
import org.michimusic.mobile.artist.artwork.ArtworkSource
import org.michimusic.mobile.artist.artwork.ArtworkEvidence
import org.michimusic.mobile.ui.theme.MichiTheme

/**
 * K48-R12-01: opening the picker is a local operation, and the provider query starts only
 * after the user has read and confirmed the disclosure. The counter here is the whole
 * point: zero before consent, exactly one per confirmation, and none after a cancellation.
 */
@RunWith(AndroidJUnit4::class)
class ArtistPhotoConsentGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val credit = "Midnight Oil"
    // artistIdentityAnchor only accepts a canonical MBID, so the fixture uses one.
    private val mbid = "1f2e3d4c-5b6a-4798-8c1d-2e3f4a5b6c7d"

    private fun profile() = ArtistExternalProfile(
        mbid = mbid,
        name = credit,
        type = "Group",
        area = "Australia",
        disambiguation = "Australian rock band",
        wikidataQid = "Q48284",
    )

    private fun candidate() = ArtistArtworkCandidate(
        source = ArtworkSource.OPENVERSE,
        stableAssetId = "openverse:consent",
        confirmedMbid = mbid,
        verifiedQid = "Q48284",
        canonicalSourcePage = "https://www.flickr.com/photos/1/51904192325",
        imageUrl = "https://live.staticflickr.com/1/51904192325_b.jpg",
        originalWidth = 1024,
        originalHeight = 768,
        mimeType = "image/jpeg",
        licenseId = "cc-by-2.0",
        licenseUrl = "https://creativecommons.org/licenses/by/2.0/",
        author = "Charles Millot",
        attribution = "Charles Millot",
        rights = ArtworkRights.REQUIRES_REVIEW,
        evidence = ArtworkEvidence.UNVERIFIED,
    )

    private fun setPicker(
        searching: Boolean = false,
        onSearch: (() -> Unit)? = null,
    ) {
        compose.setContent {
            MichiTheme {
                Box(Modifier.fillMaxWidth()) {
                    ArtistArtworkPickerHost(
                        profile = profile(),
                        artistCredit = credit,
                        libraryId = "lib-consent",
                        albumIds = listOf("album-consent"),
                        initialSelections = emptyMap(),
                        pendingCandidates = emptyList(),
                        associationGroupKey = null,
                        searching = searching,
                        onSearch = onSearch,
                        onSelectionsChanged = {},
                        chooser = { _, _, _ -> error("consent certification never downloads") },
                    )
                }
            }
        }
    }

    @Test
    fun renderingThePickerAsksTheProviderNothing() {
        val calls = AtomicInteger(0)
        setPicker(onSearch = { calls.incrementAndGet() })
        compose.waitForIdle()
        assertEquals("composing the picker must not search", 0, calls.get())
    }

    @Test
    fun theSearchButtonOpensTheDisclosureInsteadOfSearching() {
        val calls = AtomicInteger(0)
        setPicker(onSearch = { calls.incrementAndGet() })

        compose.onNodeWithTag("artist_artwork_openverse_search").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("artist_artwork_openverse_consent").assertIsDisplayed()
        assertEquals("the button opens the disclosure, not the network", 0, calls.get())
    }

    @Test
    fun cancellingTheDisclosureSearchesNothing() {
        val calls = AtomicInteger(0)
        setPicker(onSearch = { calls.incrementAndGet() })

        compose.onNodeWithTag("artist_artwork_openverse_search").performClick()
        compose.onNodeWithTag("artist_artwork_openverse_cancel").performClick()
        compose.waitForIdle()

        assertEquals("a cancelled disclosure never queries", 0, calls.get())
        compose.onNodeWithTag("artist_artwork_openverse_consent").assertDoesNotExist()
        compose.onNodeWithTag("artist_artwork_openverse_search").assertIsDisplayed()
    }

    @Test
    fun confirmingTheDisclosureSearchesExactlyOnce() {
        val calls = AtomicInteger(0)
        setPicker(onSearch = { calls.incrementAndGet() })

        compose.onNodeWithTag("artist_artwork_openverse_search").performClick()
        compose.onNodeWithTag("artist_artwork_openverse_confirm").performClick()
        compose.waitForIdle()

        assertEquals("one confirmation, one query", 1, calls.get())
        compose.onNodeWithTag("artist_artwork_openverse_consent").assertDoesNotExist()
    }

    @Test
    fun aSecondConfirmationRequiresReadingTheDisclosureAgain() {
        val calls = AtomicInteger(0)
        setPicker(onSearch = { calls.incrementAndGet() })

        compose.onNodeWithTag("artist_artwork_openverse_search").performClick()
        compose.onNodeWithTag("artist_artwork_openverse_confirm").performClick()
        compose.waitForIdle()

        // The disclosure is gone, so a second tap only re-opens it: no extra query.
        compose.onNodeWithTag("artist_artwork_openverse_search").performClick()
        compose.waitForIdle()
        assertEquals("re-opening the disclosure is not a query", 1, calls.get())

        compose.onNodeWithTag("artist_artwork_openverse_confirm").performClick()
        compose.waitForIdle()
        assertEquals("a re-confirmed disclosure is a new query", 2, calls.get())
    }

    @Test
    fun theDisclosureNamesTheProvidersTheArtistAndWhatLeavesTheDevice() {
        setPicker(onSearch = {})

        compose.onNodeWithTag("artist_artwork_openverse_search").performClick()
        compose.waitForIdle()

        // Read the sentence from the state the picker renders instead of copying it here:
        // duplicating it let a whole new provider (fanart.tv) ship without this test noticing.
        val disclosure = ArtistArtworkPickerUiState(artistCredit = credit, mbid = mbid).consentText
        listOf("Openverse", "TheAudioDB", "fanart.tv", credit).forEach { named ->
            assertTrue("the disclosure must name $named", disclosure.contains(named))
        }
        assertTrue(
            "the disclosure must say the catalogue declares no licence",
            disclosure.contains("no declaran ninguna"),
        )
        assertTrue(
            "the disclosure must say a photo is only kept after an explicit choice",
            disclosure.contains("elección explícita"),
        )
        assertTrue(
            "the disclosure must say the provider learns the usual connection data",
            disclosure.contains("datos habituales de conexión"),
        )
        compose.onNodeWithText(disclosure).assertIsDisplayed()
    }

    @Test
    fun confirmationIsUnavailableWhileASearchIsInFlight() {
        setPicker(searching = true, onSearch = {})
        // The disclosure cannot even be opened while the previous query runs.
        compose.onNodeWithTag("artist_artwork_openverse_search").assertIsNotEnabled()
    }
}
