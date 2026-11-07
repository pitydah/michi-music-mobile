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
    fun identityOnlyConfirmationSurvivesRoomCloseAndOfflineReopen() = runBlocking {
        val name = "identity-first-recovery-${System.nanoTime()}.db"
        try {
            var database = Room.databaseBuilder(
                context, ArtistEnrichmentCacheDatabase::class.java, name,
            ).allowMainThreadQueries().build()
            val store = RoomArtistEnrichmentSelectionStore(database) { 100L }

            val committed = store.saveIdentity(
                "lib_device_local", credit, albums, mbid,
            )
            assertEquals(mbid, committed.profile.mbid)
            assertTrue(committed.profile.biography == null)
            assertTrue(committed.profile.portrait == null)
            val canonicalGroup = requireNotNull(committed.associationGroupKey)
            database.close()

            database = Room.databaseBuilder(
                context, ArtistEnrichmentCacheDatabase::class.java, name,
            ).allowMainThreadQueries().build()
            val reopened = requireNotNull(
                RoomArtistEnrichmentSelectionStore(database) { 200L }
                    .load("lib_device_local", credit, albums),
            )
            assertEquals(mbid, reopened.profile.mbid)
            assertEquals(canonicalGroup, reopened.associationGroupKey)
            assertTrue(reopened.profile.biography == null)
            assertTrue(reopened.profile.portrait == null)
            assertTrue(!reopened.profilePersisted)
            database.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun failedProfileUpdateReopensWithPreviousBiographyArtworkAndFocus() = runBlocking {
        val name = "identity-profile-failure-${System.nanoTime()}.db"
        val artworkFile = File(context.filesDir, "artist-artwork/profile-failure.jpg")
        artworkFile.parentFile?.mkdirs()
        artworkFile.writeBytes(byteArrayOf(9, 8, 7, 6))
        try {
            var database = Room.databaseBuilder(
                context, ArtistEnrichmentCacheDatabase::class.java, name,
            ).allowMainThreadQueries().build()
            val durableStore = RoomArtistEnrichmentSelectionStore(database) { 100L }
            val oldProfile = ArtistExternalProfile(
                mbid = mbid,
                name = credit,
                biography = "Biografía anterior guardada.",
                biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
                biographyOutcome = ArtistMetadataOutcome.SUCCESS,
                wikidataQid = "Q48284",
            )
            durableStore.save("lib_device_local", credit, albums, oldProfile)
            val persisted = requireNotNull(durableStore.load("lib_device_local", credit, albums))
            val anchor = requireNotNull(
                org.michimusic.mobile.artist.artwork.artistIdentityAnchor(
                    "lib_device_local", credit, albums, oldProfile, persisted.associationGroupKey,
                ),
            )
            RoomArtistArtworkStore(database, context) { 100L }.save(
                anchor = anchor,
                candidate = ArtistArtworkCandidate(
                    source = org.michimusic.mobile.artist.artwork.ArtworkSource.COMMONS_P18,
                    stableAssetId = "commons:profile-failure.jpg",
                    confirmedMbid = mbid,
                    verifiedQid = "Q48284",
                    canonicalSourcePage = "https://commons.wikimedia.org/wiki/File:Profile_failure.jpg",
                    imageUrl = "https://thumb.wikimedia.org/profile-failure.jpg",
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
                    relativePath = "artist-artwork/profile-failure.jpg",
                    sha256 = "b".repeat(64),
                    width = 1600,
                    height = 900,
                    mimeType = "image/jpeg",
                ),
                focus = ArtworkFocus(0.35f, 0.7f),
                manuallyChosen = true,
            )

            val failingProfileStore = object : ArtistEnrichmentSelectionStore,
                ArtistIdentitySelectionStore {
                override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) =
                    durableStore.load(libraryId, credit, albumIds)

                override suspend fun saveIdentity(
                    libraryId: String,
                    credit: String,
                    albumIds: List<String>,
                    mbid: String,
                    associationGroupKey: String?,
                ) = durableStore.saveIdentity(
                    libraryId, credit, albumIds, mbid, associationGroupKey,
                )

                override suspend fun save(
                    libraryId: String,
                    credit: String,
                    albumIds: List<String>,
                    profile: ArtistExternalProfile,
                ) = error("fallo de persistencia del perfil")

                override suspend fun clear(
                    libraryId: String,
                    credit: String,
                    albumIds: List<String>,
                ) = Unit
            }
            failingProfileStore.saveIdentityFirst(
                "lib_device_local", credit, albums, mbid, persisted.associationGroupKey,
            )
            val newProfile = oldProfile.copy(biography = "Biografía nueva no guardada.")
            assertTrue(
                runCatching {
                    failingProfileStore.save("lib_device_local", credit, albums, newProfile)
                }.isFailure,
            )
            database.close()

            database = Room.databaseBuilder(
                context, ArtistEnrichmentCacheDatabase::class.java, name,
            ).allowMainThreadQueries().build()
            val reopened = requireNotNull(
                RoomArtistEnrichmentSelectionStore(database) { 200L }
                    .load("lib_device_local", credit, albums),
            )
            assertEquals(mbid, reopened.profile.mbid)
            assertEquals("Biografía anterior guardada.", reopened.profile.biography)
            val hero = RoomArtistArtworkStore(database, context) { 200L }.load(anchor).single()
            assertEquals(ArtworkSlot.ARTIST_HERO, hero.slot)
            assertEquals(0.35f, hero.focus.x, 0.0001f)
            assertEquals(0.7f, hero.focus.y, 0.0001f)
            assertTrue(hero.manuallyChosen)
            database.close()
        } finally {
            context.deleteDatabase(name)
            artworkFile.delete()
        }
    }

    @Test
    fun explicitAmbiguityResolutionSurvivesReopenAndKeepsArtworkScopedToTheChosenGroup() = runBlocking {
        val name = "association-resolution-recovery-${System.nanoTime()}.db"
        val otherMbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"
        val albumA = listOf("album-a")
        val albumB = listOf("album-b")
        val merged = albumA + albumB
        val fileA = File(context.filesDir, "artist-artwork/resolution-a.jpg")
        val fileB = File(context.filesDir, "artist-artwork/resolution-b.jpg")
        fileA.parentFile?.mkdirs()
        fileA.writeBytes(byteArrayOf(1, 1, 1))
        fileB.writeBytes(byteArrayOf(2, 2, 2))
        try {
            var database = Room.databaseBuilder(
                context, ArtistEnrichmentCacheDatabase::class.java, name,
            ).allowMainThreadQueries().build()
            val store = RoomArtistEnrichmentSelectionStore(database) { 100L }
            val profileA = ArtistExternalProfile(mbid = mbid, name = credit, wikidataQid = "Q48284")
            val profileB = ArtistExternalProfile(mbid = otherMbid, name = "$credit historical", wikidataQid = "Q5")
            store.save("lib_device_local", credit, albumA, profileA)
            store.save("lib_device_local", credit, albumB, profileB)
            val groupA = requireNotNull(store.load("lib_device_local", credit, albumA)?.associationGroupKey)
            val groupB = requireNotNull(store.load("lib_device_local", credit, albumB)?.associationGroupKey)
            val artworkStore = RoomArtistArtworkStore(database, context) { 100L }
            suspend fun saveArtwork(
                profile: ArtistExternalProfile,
                group: String,
                albumIds: List<String>,
                suffix: String,
            ) {
                val anchor = requireNotNull(
                    org.michimusic.mobile.artist.artwork.artistIdentityAnchor(
                        "lib_device_local", credit, albumIds, profile, group,
                    ),
                )
                artworkStore.save(
                    anchor = anchor,
                    candidate = ArtistArtworkCandidate(
                        source = ArtworkSource.COMMONS_P18,
                        stableAssetId = "commons:resolution-$suffix.jpg",
                        confirmedMbid = profile.mbid,
                        verifiedQid = profile.wikidataQid,
                        canonicalSourcePage = "https://commons.wikimedia.org/wiki/File:Resolution_$suffix.jpg",
                        imageUrl = "https://thumb.wikimedia.org/resolution-$suffix.jpg",
                        originalWidth = 1000,
                        originalHeight = 1000,
                        mimeType = "image/jpeg",
                        licenseId = "CC BY-SA 2.0",
                        licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
                        author = "Author $suffix",
                        attribution = "Author $suffix",
                        evidence = ArtworkEvidence.VERIFIED_QID_REFERENCE,
                        rights = ArtworkRights.VERIFIED_ALLOWED,
                    ),
                    slot = ArtworkSlot.ARTIST_HERO,
                    downloaded = DownloadedArtwork(
                        relativePath = "artist-artwork/resolution-$suffix.jpg",
                        sha256 = suffix.repeat(64),
                        width = 1000,
                        height = 1000,
                        mimeType = "image/jpeg",
                    ),
                    focus = ArtworkFocus(if (suffix == "a") 0.25f else 0.75f, 0.5f),
                    manuallyChosen = true,
                )
            }
            saveArtwork(profileA, groupA, albumA, "a")
            saveArtwork(profileB, groupB, albumB, "b")

            assertEquals(2, store.ambiguousAssociations("lib_device_local", credit, merged).size)
            store.resolveAssociation("lib_device_local", credit, merged, groupB, otherMbid)
            database.close()

            database = Room.databaseBuilder(
                context, ArtistEnrichmentCacheDatabase::class.java, name,
            ).allowMainThreadQueries().build()
            val reopenedStore = RoomArtistEnrichmentSelectionStore(database) { 200L }
            val selected = requireNotNull(reopenedStore.load("lib_device_local", credit, merged))
            assertEquals(otherMbid, selected.profile.mbid)
            assertEquals(groupB, selected.associationGroupKey)
            val selectedAnchor = requireNotNull(
                org.michimusic.mobile.artist.artwork.artistIdentityAnchor(
                    "lib_device_local", credit, merged, selected.profile, selected.associationGroupKey,
                ),
            )
            val selectedArtwork = RoomArtistArtworkStore(database, context) { 200L }.load(selectedAnchor)
            assertEquals(1, selectedArtwork.size)
            assertEquals("Author b", selectedArtwork.single().attribution)
            assertEquals(0.75f, selectedArtwork.single().focus.x, 0.0001f)
            val creditKey = requireNotNull(scopedArtistCreditKey("lib_device_local", credit, merged)).first
            assertEquals(2, database.artistEnrichmentDao().getAllForCredit("lib_device_local", creditKey).size)
            database.close()
        } finally {
            context.deleteDatabase(name)
            fileA.delete()
            fileB.delete()
        }
    }

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

    @Test
    fun albumMembershipChangeKeepsTheCanonicalAssociationAndBothArtworkSlots() = runBlocking {
        val name = "restart-recovery-album-${System.nanoTime()}.db"
        val artworkFile = File(context.filesDir, "artist-artwork/restart-album.jpg")
        artworkFile.parentFile?.mkdirs()
        artworkFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val changedAlbums = albums + "album-new-recording"
        try {
            var database = Room.databaseBuilder(
                context, ArtistEnrichmentCacheDatabase::class.java, name,
            ).allowMainThreadQueries().build()
            val profile = ArtistExternalProfile(
                mbid = mbid,
                name = credit,
                biography = "Banda australiana.",
                biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
                biographyOutcome = ArtistMetadataOutcome.SUCCESS,
                wikidataQid = "Q48284",
            )
            val selectionStore = RoomArtistEnrichmentSelectionStore(database) { 100L }
            selectionStore.save("lib_device_local", credit, albums, profile)
            val original = requireNotNull(selectionStore.load("lib_device_local", credit, albums))
            val canonicalGroup = requireNotNull(original.associationGroupKey)
            val anchor = requireNotNull(
                org.michimusic.mobile.artist.artwork.artistIdentityAnchor(
                    "lib_device_local", credit, albums, profile, canonicalGroup,
                ),
            )
            val artworkStore = RoomArtistArtworkStore(database, context) { 100L }
            val candidate = ArtistArtworkCandidate(
                source = ArtworkSource.COMMONS_P18,
                stableAssetId = "commons:restart-album.jpg",
                confirmedMbid = mbid,
                verifiedQid = "Q48284",
                canonicalSourcePage = "https://commons.wikimedia.org/wiki/File:Restart_album.jpg",
                imageUrl = "https://thumb.wikimedia.org/restart-album.jpg",
                originalWidth = 1600,
                originalHeight = 900,
                mimeType = "image/jpeg",
                licenseId = "CC BY-SA 2.0",
                licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
                author = "Author",
                attribution = "Author",
                evidence = ArtworkEvidence.VERIFIED_QID_REFERENCE,
                rights = ArtworkRights.VERIFIED_ALLOWED,
            )
            val downloaded = DownloadedArtwork(
                relativePath = "artist-artwork/restart-album.jpg",
                sha256 = "a".repeat(64),
                width = 1600,
                height = 900,
                mimeType = "image/jpeg",
            )
            ArtworkSlot.entries.forEach { slot ->
                artworkStore.save(
                    anchor = anchor,
                    candidate = candidate,
                    slot = slot,
                    downloaded = downloaded,
                    focus = if (slot == ArtworkSlot.ARTIST_HERO) ArtworkFocus(0.5f, 0.45f)
                    else ArtworkFocus(0.4f, 0.6f),
                    manuallyChosen = slot == ArtworkSlot.ARTIST_HERO,
                )
            }
            database.close()

            database = Room.databaseBuilder(
                context, ArtistEnrichmentCacheDatabase::class.java, name,
            ).allowMainThreadQueries().build()
            val reopened = RoomArtistEnrichmentSelectionStore(database) { 100L }
            val recovered = requireNotNull(reopened.load("lib_device_local", credit, changedAlbums))
            assertEquals(mbid, recovered.profile.mbid)
            assertEquals(canonicalGroup, recovered.associationGroupKey)
            assertTrue(recovered.needsConfirmation)

            // Persisting refreshed provider facts updates the SAME canonical row without
            // turning the temporary expanded library view into ownership evidence.
            reopened.save(
                "lib_device_local", credit, changedAlbums, recovered.profile,
                associationGroupKey = recovered.associationGroupKey,
            )
            database.close()

            database = Room.databaseBuilder(
                context, ArtistEnrichmentCacheDatabase::class.java, name,
            ).allowMainThreadQueries().build()
            val afterRefresh = RoomArtistEnrichmentSelectionStore(database) { 100L }
            val exact = requireNotNull(afterRefresh.load("lib_device_local", credit, changedAlbums))
            assertEquals(canonicalGroup, exact.associationGroupKey)
            assertTrue(exact.needsConfirmation)
            val recoveredAnchor = requireNotNull(
                org.michimusic.mobile.artist.artwork.artistIdentityAnchor(
                    "lib_device_local", credit, changedAlbums, exact.profile,
                    exact.associationGroupKey,
                ),
            )
            val slots = RoomArtistArtworkStore(database, context) { 100L }
                .load(recoveredAnchor)
                .associateBy { it.slot }
            assertEquals(setOf(ArtworkSlot.ARTIST_THUMB, ArtworkSlot.ARTIST_HERO), slots.keys)
            assertEquals(0.6f, slots.getValue(ArtworkSlot.ARTIST_THUMB).focus.y, 0.0001f)
            assertEquals(0.45f, slots.getValue(ArtworkSlot.ARTIST_HERO).focus.y, 0.0001f)
            assertTrue(slots.getValue(ArtworkSlot.ARTIST_HERO).manuallyChosen)
            database.close()
        } finally {
            context.deleteDatabase(name)
            artworkFile.delete()
        }
    }
}
