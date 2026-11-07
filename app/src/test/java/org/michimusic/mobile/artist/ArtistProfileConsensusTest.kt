package org.michimusic.mobile.artist

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource
import org.michimusic.mobile.artist.artwork.DownloadedArtwork

class ArtistProfileConsensusTest {
    private val a = "bd6d4f63-5cd0-4d78-a292-167997794c21"
    private val b = "0b8f1d56-75d5-4b34-a216-004f4c4b5720"
    private val photo = CommonsPortrait(
        "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/artist.jpg/320px-artist.jpg",
        "https://commons.wikimedia.org/wiki/File:artist.jpg",
        "CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/", "Fotógrafo",
    )
    private val scopes = listOf(
        ArtistProfileScope("phone", "Midnight Oil", listOf("diesel")),
        ArtistProfileScope("nas", "Midnight Oil", listOf("diesel", "head-injuries")),
    )
    private fun store(profiles: Map<String, PersistedArtistProfile>) = object : ArtistEnrichmentSelectionStore {
        override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) = profiles[libraryId]
        override suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile) = Unit
        override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) = Unit
    }
    private fun profile(id: String, stale: Boolean = false, portrait: CommonsPortrait? = photo) =
        PersistedArtistProfile(ArtistExternalProfile(id, "Midnight Oil", portrait = portrait), stale)

    @Test fun allLibrariesReusesPortraitOnlyWhenEveryLibraryConfirmsSameMbid() = runBlocking {
        val result = resolveArtistProfileConsensus(scopes, store(mapOf(
            "phone" to profile(a), "nas" to profile(a, stale = true, portrait = null),
        )))
        assertEquals(ArtistProfileStatus.CONFIRMED, result.status)
        assertEquals(a, result.saved?.profile?.mbid)
        assertEquals(photo, result.portrait)
        assertTrue(result.saved?.stale == true)
        assertEquals(2, result.contributingLibraries)
    }

    @Test fun homonymousArtistsAreNeverMergedOrPhotographed() = runBlocking {
        val result = resolveArtistProfileConsensus(scopes, store(mapOf(
            "phone" to profile(a), "nas" to profile(b),
        )))
        assertEquals(ArtistProfileStatus.CONFLICT, result.status)
        assertNull(result.portrait)
    }

    @Test fun oneUnconfirmedLibraryBlocksTheCombinedPhoto() = runBlocking {
        val result = resolveArtistProfileConsensus(scopes, store(mapOf("phone" to profile(a))))
        assertEquals(ArtistProfileStatus.MISSING, result.status)
        assertNull(result.saved)
    }

    @Test fun emptyOrDuplicateScopeNeverTriggersUntrustedNameResolution() = runBlocking {
        assertEquals(ArtistProfileStatus.UNRESOLVED,
            resolveArtistProfileConsensus(emptyList(), store(emptyMap())).status)
        assertEquals(ArtistProfileStatus.UNRESOLVED,
            resolveArtistProfileConsensus(scopes + scopes.first(), store(emptyMap())).status)
    }
    @Test fun roomFailureIsErrorNotMissingArtist() = runBlocking {
        val failed = object : ArtistEnrichmentSelectionStore {
            override suspend fun load(libraryId: String, credit: String, albumIds: List<String>): PersistedArtistProfile? {
                throw IllegalStateException("room inaccessible")
            }
            override suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile) = Unit
            override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) = Unit
        }
        val result = resolveArtistProfileConsensus(scopes, failed)
        assertEquals(ArtistProfileStatus.ERROR, result.status)
        assertNull(result.portrait)
    }

    @Test fun aggregateArtworkUsesOnlyConsensusMbidAndPrefersManualSelection() = runBlocking {
        val consensus = resolveArtistProfileConsensus(scopes, store(mapOf(
            "phone" to profile(a), "nas" to profile(a, portrait = null),
        )))
        val artwork = object : ArtistArtworkStore {
            override suspend fun load(anchor: ArtistIdentityAnchor) = listOf(
                ArtistArtworkSelection(
                    slot = ArtworkSlot.ARTIST_THUMB,
                    assetId = "sha256:${anchor.libraryId.padEnd(64, 'a').take(64)}",
                    confirmedMbid = anchor.confirmedMbid,
                    localImagePath = "/private/${anchor.libraryId}.jpg",
                    focus = ArtworkFocus(),
                    manuallyChosen = anchor.libraryId == "phone",
                    revision = if (anchor.libraryId == "phone") 2 else 20,
                    source = ArtworkSource.COMMONS_P18,
                    status = ArtworkLoadStatus.READY,
                ),
            )
            override suspend fun loadAsset(assetId: String) = null
            override suspend fun save(
                anchor: ArtistIdentityAnchor,
                candidate: ArtistArtworkCandidate,
                slot: ArtworkSlot,
                downloaded: DownloadedArtwork,
                focus: ArtworkFocus,
                manuallyChosen: Boolean,
            ): ArtistArtworkSelection = error("not used")
            override suspend fun updateFocus(anchor: ArtistIdentityAnchor, slot: ArtworkSlot, focus: ArtworkFocus) = false
            override suspend fun clear(anchor: ArtistIdentityAnchor) = Unit
            override suspend fun counts() = 0 to 0
        }

        val selected = resolveArtistArtworkConsensus(consensus, artwork)

        assertEquals("/private/phone.jpg", selected?.localImagePath)
        assertEquals(a, selected?.confirmedMbid)
    }

    @Test fun conflictingOrMissingConsensusNeverReadsArtwork() = runBlocking {
        var artworkReads = 0
        val artwork = object : ArtistArtworkStore {
            override suspend fun load(anchor: ArtistIdentityAnchor): List<ArtistArtworkSelection> {
                artworkReads++
                return emptyList()
            }
            override suspend fun loadAsset(assetId: String) = null
            override suspend fun save(
                anchor: ArtistIdentityAnchor,
                candidate: ArtistArtworkCandidate,
                slot: ArtworkSlot,
                downloaded: DownloadedArtwork,
                focus: ArtworkFocus,
                manuallyChosen: Boolean,
            ): ArtistArtworkSelection = error("not used")
            override suspend fun updateFocus(anchor: ArtistIdentityAnchor, slot: ArtworkSlot, focus: ArtworkFocus) = false
            override suspend fun clear(anchor: ArtistIdentityAnchor) = Unit
            override suspend fun counts() = 0 to 0
        }
        val conflict = resolveArtistProfileConsensus(scopes, store(mapOf(
            "phone" to profile(a), "nas" to profile(b),
        )))
        val missing = resolveArtistProfileConsensus(scopes, store(mapOf("phone" to profile(a))))

        assertNull(resolveArtistArtworkConsensus(conflict, artwork))
        assertNull(resolveArtistArtworkConsensus(missing, artwork))
        assertEquals(0, artworkReads)
    }
}
