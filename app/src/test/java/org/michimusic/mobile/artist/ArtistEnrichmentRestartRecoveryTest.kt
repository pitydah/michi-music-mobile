package org.michimusic.mobile.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtworkEvidence
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkRights
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource
import org.michimusic.mobile.artist.artwork.DownloadedArtwork
import org.michimusic.mobile.artist.artwork.RoomArtistArtworkStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Process-death analogue: close both Room handles, reopen the same file and verify
 * the confirmed profile and its artwork metadata/files are still recoverable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtistEnrichmentRestartRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val credit = "Midnight Oil"
    private val albums = listOf("album-diesel-and-dust")

    @Test
    fun confirmedProfileAndArtworkSurviveClosingAndReopeningTheDatabase() = runBlocking {
        val name = "restart-recovery-${System.nanoTime()}.db"
        val artworkFile = File(context.filesDir, "artist-artwork/restart-test.jpg")
        artworkFile.parentFile?.mkdirs()
        artworkFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        try {
            var database = Room.databaseBuilder(
                context, ArtistEnrichmentCacheDatabase::class.java, name,
            ).allowMainThreadQueries().build()
            val selectionStore = RoomArtistEnrichmentSelectionStore(database) { 100L }
            val artworkStore = RoomArtistArtworkStore(database, context) { 100L }
            val profile = ArtistExternalProfile(
                mbid = mbid,
                name = credit,
                biography = "Banda australiana.",
                biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
                biographyOutcome = ArtistMetadataOutcome.SUCCESS,
                wikidataQid = "Q48284",
            )
            selectionStore.save("lib_device_local", credit, albums, profile)
            val anchor = requireNotNull(
                org.michimusic.mobile.artist.artwork.artistIdentityAnchor(
                    "lib_device_local", credit, albums, profile,
                ),
            )
            artworkStore.save(
                anchor = anchor,
                candidate = ArtistArtworkCandidate(
                    source = ArtworkSource.COMMONS_P18,
                    stableAssetId = "commons:restart-test.jpg",
                    confirmedMbid = mbid,
                    verifiedQid = "Q48284",
                    canonicalSourcePage = "https://commons.wikimedia.org/wiki/File:Restart_test.jpg",
                    imageUrl = "https://thumb.wikimedia.org/restart-test.jpg",
                    originalWidth = 1600,
                    originalHeight = 900,
                    mimeType = "image/jpeg",
                    licenseId = "CC BY-SA 2.0",
                    licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
                    author = "Author",
                    attribution = "Author",
                    evidence = ArtworkEvidence.VERIFIED_QID_REFERENCE,
                    rights = ArtworkRights.VERIFIED_ALLOWED,
                ),
                slot = ArtworkSlot.ARTIST_HERO,
                downloaded = DownloadedArtwork(
                    relativePath = "artist-artwork/restart-test.jpg",
                    sha256 = "a".repeat(64),
                    width = 1600,
                    height = 900,
                    mimeType = "image/jpeg",
                ),
                focus = ArtworkFocus(0.5f, 0.45f),
                manuallyChosen = true,
            )
            database.close()

            database = Room.databaseBuilder(
                context, ArtistEnrichmentCacheDatabase::class.java, name,
            ).allowMainThreadQueries().build()
            val reopenedProfile = RoomArtistEnrichmentSelectionStore(database) { 100L }
                .load("lib_device_local", credit, albums)
            assertNotNull(reopenedProfile)
            assertEquals(mbid, reopenedProfile?.profile?.mbid)
            assertEquals("Banda australiana.", reopenedProfile?.profile?.biography)

            val reopenedArtwork = RoomArtistArtworkStore(database, context) { 100L }
                .load(anchor)
            assertTrue(reopenedArtwork.isNotEmpty())
            val hero = reopenedArtwork.single { it.slot == ArtworkSlot.ARTIST_HERO }
            assertTrue(hero.manuallyChosen)
            assertEquals(0.45f, hero.focus.y, 0.0001f)
            assertEquals("CC BY-SA 2.0", hero.licenseId)
            assertTrue(File(requireNotNull(hero.localImagePath)).isFile)
            database.close()
        } finally {
            context.deleteDatabase(name)
            artworkFile.delete()
        }
    }
}
