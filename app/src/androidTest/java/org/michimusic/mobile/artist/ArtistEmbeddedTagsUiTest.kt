package org.michimusic.mobile.artist

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI tests must never contact MusicBrainz; a fake probe and fake gateway prove the explicit gesture. */
class ArtistEmbeddedTagsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"

    @Test fun tagProbeRunsOnlyWhenTappedAndProviderBindingWaitsForConfirmation() {
        var scans = 0
        var remote = 0
        val jointCredit = "Patti Austin & James Ingram"
        val uri = "content://media/external/audio/media/12"
        val probe = ArtistTagProbe {
            scans++
            // Shaped exactly like [LocalArtistTagProbe]: per-file evidence plus the same
            // aggregate view. An aggregate-only receipt naming just one member of a joint
            // credit is deliberately rejected (WRONG_CREDIT), so it cannot prove this flow.
            EmbeddedArtistReview(
                inspectedFiles = 1,
                taggedFiles = 1,
                artistMbids = setOf(mbid),
                albumArtistMbids = emptySet(),
                creditedNames = listOf(jointCredit),
                albumArtists = emptyList(),
                trackEvidence = listOf(
                    EmbeddedTrackEvidence(
                        uri = uri,
                        tags = EmbeddedArtistTags(
                            artistMbids = setOf(mbid),
                            creditedNames = listOf(jointCredit),
                        ),
                    ),
                ),
            )
        }
        val gateway = object : ArtistEnrichmentGateway {
            override suspend fun findCandidates(localCredit: String): List<ArtistLookupCandidate> {
                remote++
                return emptyList()
            }
            override suspend fun confirmedProfile(mbid: String): ArtistExternalProfile {
                remote++
                return ArtistExternalProfile(mbid = mbid, name = "Patti Austin")
            }
        }
        rule.setContent {
            ArtistEnrichmentPanel(
                artistCredit = "Patti Austin & James Ingram",
                gateway = gateway,
                libraryId = "lib-local",
                albumIds = listOf("album-local"),
                localAudioUris = listOf(uri),
                tagProbe = probe,
                selectionStore = object : ArtistEnrichmentSelectionStore {
                    override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) = null
                    override suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile) = Unit
                    override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) = Unit
                },
            )
        }
        rule.waitForIdle()
        assertEquals(0, scans)
        assertEquals(0, remote) // Opening the panel is local-only.
        rule.onNodeWithTag("artist_embedded_tags_scan").performClick()
        rule.waitForIdle()
        assertEquals(1, scans)
        assertEquals(0, remote) // Scanning a tag never binds an identity by itself.
        rule.onNodeWithTag("artist_embedded_mbid_confirm").assertExists().performClick()
        rule.waitUntil(5_000) { remote >= 1 }
        assertEquals(1, remote)
    }
}
