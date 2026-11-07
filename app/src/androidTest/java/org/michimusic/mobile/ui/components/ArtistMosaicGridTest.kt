package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.ArtistEnrichmentSelectionStore
import org.michimusic.mobile.artist.ArtistExternalProfile
import org.michimusic.mobile.artist.ArtistProfileScope
import org.michimusic.mobile.artist.CommonsPortrait
import org.michimusic.mobile.artist.PersistedArtistProfile
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource
import org.michimusic.mobile.artist.artwork.DownloadedArtwork

@RunWith(AndroidJUnit4::class)
class ArtistMosaicGridTest {
    @get:Rule val compose = createComposeRule()

    private val entries = listOf(
        ArtistMosaicEntry("Midnight Oil", listOf("album-1"), 1, 1),
        ArtistMosaicEntry("Patti Austin & James Ingram", listOf("album-2"), 1, 1),
    )
    private val portrait = CommonsPortrait(
        imageUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/artist.jpg/320px-artist.jpg",
        commonsPageUrl = "https://commons.wikimedia.org/wiki/File:artist.jpg",
        license = "CC BY-SA 4.0",
        licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
        attribution = "Fotógrafo de prueba",
    )

    private class FakeStore(val portrait: CommonsPortrait?) : ArtistEnrichmentSelectionStore {
        var queries = 0
        override suspend fun load(libraryId: String, credit: String, albumIds: List<String>): PersistedArtistProfile? {
            queries++
            return portrait?.let { PersistedArtistProfile(
                ArtistExternalProfile("bd6d4f63-5cd0-4d78-a292-167997794c21", credit, portrait = it),
                stale = false,
            ) }
        }
        override suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile) {}
        override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) {}
    }

    @Test fun responsiveGridIsReadableWithLargeSystemFonts() {
        assertEquals(132f, artistMosaicMinCellWidthDp(1.0f))
        assertEquals(168f, artistMosaicMinCellWidthDp(1.3f))
        assertEquals(240f, artistMosaicMinCellWidthDp(2.0f))
        assertEquals(listOf("Midnight Oil", "Patti Austin & James Ingram"),
            orderedArtistMosaicEntries(entries.reversed()).map { it.credit })
    }

    @Test fun missingPortraitUsesLocalCreditMonogramWithoutInventingIdentity() {
        assertEquals("MO", artistMosaicMonogram("Midnight Oil"))
        assertEquals("PA", artistMosaicMonogram("Patti Austin & James Ingram"))
        assertEquals("♫", artistMosaicMonogram("   "))
    }

    @Test fun tilesOpenTheirOwnArtistsWithoutStartingEnrichment() {
        val opened = mutableListOf<String>()
        val fake = FakeStore(null)
        compose.setContent {
            ArtistMosaicGrid(entries, "library-1", 0, { opened.add(it) }, selectionStore = fake)
        }
        compose.onNodeWithTag("artist_mosaic_grid").assertExists()
        compose.onNodeWithTag("artist_mosaic_tile_Midnight Oil").performClick()
        compose.onNodeWithTag("artist_mosaic_tile_Patti Austin & James Ingram").performClick()
        compose.runOnIdle { assertEquals(entries.map { it.credit }, opened) }
        compose.onNodeWithTag("artist_mosaic_photo_Midnight Oil", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("artist_mosaic_enrich_Midnight Oil", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("artist_mosaic_retry_Midnight Oil", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun verifiedPortraitMetadataWithoutPrivateArtworkUsesMonogram() {
        val fake = FakeStore(portrait)
        compose.setContent {
            ArtistMosaicGrid(entries.take(1), "library-1", 0, {}, selectionStore = fake)
        }
        compose.waitUntil(5000) { fake.queries == 1 }
        compose.onNodeWithTag("artist_mosaic_monogram_Midnight Oil", useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithTag("artist_mosaic_photo_Midnight Oil", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test fun confirmedPortraitUrlNeverSubstitutesForMissingPrivateArtwork() {
        val fake = FakeStore(portrait)
        compose.setContent {
            ArtistMosaicGrid(
                entries.take(1), "library-1", 0, {},
                selectionStore = fake,
                artworkStore = null,
            )
        }

        compose.waitUntil(5_000) { fake.queries == 1 }
        compose.onNodeWithTag("artist_mosaic_monogram_Midnight Oil", useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithTag("artist_mosaic_photo_Midnight Oil", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test fun missingPortraitShowsNoSecondaryCtaAndStillOpensTheArtist() {
        var opened: String? = null
        val store = FakeStore(null)
        compose.setContent {
            ArtistMosaicGrid(
                artists = entries.take(1), libraryId = "library-1", refreshToken = 0,
                onOpenArtist = { opened = it }, selectionStore = store,
            )
        }
        compose.onNodeWithTag("artist_mosaic_enrich_Midnight Oil", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithTag("artist_mosaic_retry_Midnight Oil", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithText("Buscar retrato").assertDoesNotExist()
        compose.onNodeWithText("Reintentar foto").assertDoesNotExist()
        compose.onNodeWithText("Elegir biblioteca").assertDoesNotExist()
        compose.onNodeWithText("Revisar identidad").assertDoesNotExist()
        compose.onNodeWithTag("artist_mosaic_tile_Midnight Oil").performClick()
        compose.runOnIdle { assertEquals("Midnight Oil", opened) }
    }

    @Test fun artworkRevisionReloadsTheMosaicWithoutLeavingTheScreen() {
        val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val profileStore = object : ArtistEnrichmentSelectionStore {
            override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) =
                PersistedArtistProfile(
                    ArtistExternalProfile(mbid, "Midnight Oil", wikidataQid = "Q48284"),
                    stale = false,
                )
            override suspend fun save(
                libraryId: String,
                credit: String,
                albumIds: List<String>,
                profile: ArtistExternalProfile,
            ) {}
            override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) {}
        }
        val artworkStore = object : ArtistArtworkStore {
            var loads = 0
            override suspend fun load(anchor: ArtistIdentityAnchor): List<ArtistArtworkSelection> {
                loads++
                return listOf(ArtistArtworkSelection(
                    slot = ArtworkSlot.ARTIST_THUMB,
                    assetId = "sha256:${"a".repeat(64)}",
                    confirmedMbid = anchor.confirmedMbid,
                    localImagePath = "/tmp/michi-artwork.png",
                    focus = ArtworkFocus(),
                    manuallyChosen = false,
                    revision = 1L,
                    source = ArtworkSource.COMMONS_P18,
                    status = ArtworkLoadStatus.READY,
                ))
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
            override suspend fun clear(anchor: ArtistIdentityAnchor) {}
            override suspend fun counts() = 0 to 0
        }
        var revision by mutableStateOf(0L)
        compose.setContent {
            ArtistMosaicGrid(
                artists = entries.take(1), libraryId = "library-1", refreshToken = 0,
                artworkRevision = revision, onOpenArtist = {}, selectionStore = profileStore,
                artworkStore = artworkStore,
            )
        }
        compose.waitUntil(5000) { artworkStore.loads == 1 }
        compose.onNodeWithTag("artist_mosaic_photo_Midnight Oil", useUnmergedTree = true)
            .assertExists()
        compose.runOnIdle { revision = 1L }
        compose.waitUntil(5000) { artworkStore.loads == 2 }
    }

    @Test fun recompositionDoesNotRepeatTheCacheRead() {
        val store = FakeStore(null)
        var tick by mutableStateOf(0)
        compose.setContent {
            Text(if (tick >= 0) "" else "unreachable")
            ArtistMosaicGrid(entries, "library-1", 0, {}, selectionStore = store)
        }
        compose.waitUntil(5000) { store.queries == 2 }
        compose.runOnIdle { tick++ }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, store.queries) }
    }

    @Test fun tileIsOneAccessibleActionWithATouchTarget() {
        compose.setContent {
            ArtistMosaicGrid(entries.take(1), "library-1", 0, {}, selectionStore = FakeStore(null))
        }
        compose.onNodeWithTag("artist_mosaic_tile_Midnight Oil")
            .assertContentDescriptionContains("Abrir artista Midnight Oil", substring = true)
            .assertHeightIsAtLeast(48.dp)
            .assertIsDisplayed()
    }

    @Test fun portraitFromOneArtistIsNeverShownOnAnotherTile() {
        val creditAware = object : ArtistEnrichmentSelectionStore {
            var queries = 0
            override suspend fun load(libraryId: String, credit: String, albumIds: List<String>): PersistedArtistProfile? {
                queries++
                if (credit != "Midnight Oil") return null
                return PersistedArtistProfile(
                    ArtistExternalProfile("bd6d4f63-5cd0-4d78-a292-167997794c21", credit, portrait = portrait),
                    stale = false,
                )
            }
            override suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile) {}
            override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) {}
        }
        compose.setContent {
            ArtistMosaicGrid(entries, "library-1", 0, {}, selectionStore = creditAware)
        }
        compose.waitUntil(5000) { creditAware.queries == 2 }
        compose.onNodeWithTag("artist_mosaic_photo_Midnight Oil", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithTag("artist_mosaic_photo_Patti Austin & James Ingram", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, creditAware.queries) }
    }

    @Test fun largeFontScaleKeepsTheTileDisplayedWithoutACounterOrCta() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) {
                    ArtistMosaicGrid(entries.take(1), "library-1", 0, {}, selectionStore = FakeStore(null))
                }
            }
        }
        compose.onNodeWithTag("artist_mosaic_tile_Midnight Oil")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Midnight Oil").assertIsDisplayed()
        compose.onNodeWithTag("artist_mosaic_enrich_Midnight Oil", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun allLibrariesShowsPhotoWhenEveryLibraryConfirmsSameMbid() {
        val scopes = listOf(
            ArtistProfileScope("phone", "Midnight Oil", listOf("a")),
            ArtistProfileScope("nas", "Midnight Oil", listOf("b")),
        )
        val fake = FakeStore(portrait)
        val artwork = object : ArtistArtworkStore {
            var loads = 0
            override suspend fun load(anchor: ArtistIdentityAnchor): List<ArtistArtworkSelection> {
                loads++
                return listOf(ArtistArtworkSelection(
                    slot = ArtworkSlot.ARTIST_THUMB,
                    assetId = "sha256:${"b".repeat(64)}",
                    confirmedMbid = anchor.confirmedMbid,
                    localImagePath = "/tmp/aggregate-thumb.png",
                    focus = ArtworkFocus(0.45f, 0.55f),
                    manuallyChosen = anchor.libraryId == "nas",
                    revision = if (anchor.libraryId == "nas") 4L else 2L,
                    source = ArtworkSource.COMMONS_P18,
                    status = ArtworkLoadStatus.READY,
                ))
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
            override suspend fun updateFocus(
                anchor: ArtistIdentityAnchor,
                slot: ArtworkSlot,
                focus: ArtworkFocus,
            ) = false
            override suspend fun clear(anchor: ArtistIdentityAnchor) {}
            override suspend fun counts() = 0 to 0
        }
        compose.setContent {
            ArtistMosaicGrid(entries.take(1).map { it.copy(profileScopes = scopes) }, null, 0, {},
                selectionStore = fake, artworkStore = artwork)
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("artist_mosaic_photo_Midnight Oil", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("artist_mosaic_photo_Midnight Oil", useUnmergedTree = true).assertExists()
        compose.runOnIdle { assertEquals(2, fake.queries) }
        compose.runOnIdle { assertEquals(2, artwork.loads) }
    }

    @Test fun allLibrariesNeverShowsPhotoForConflictingMbids() {
        val scopes = listOf(
            ArtistProfileScope("phone", "Midnight Oil", listOf("a")),
            ArtistProfileScope("nas", "Midnight Oil", listOf("b")),
        )
        var queries = 0
        val fake = object : ArtistEnrichmentSelectionStore {
            override suspend fun load(libraryId: String, credit: String, albumIds: List<String>): PersistedArtistProfile {
                queries++
                val id = if (libraryId == "phone") "bd6d4f63-5cd0-4d78-a292-167997794c21"
                    else "0b8f1d56-75d5-4b34-a216-004f4c4b5720"
                return PersistedArtistProfile(ArtistExternalProfile(id, credit, portrait = portrait), false)
            }
            override suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile) {}
            override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) {}
        }
        compose.setContent {
            ArtistMosaicGrid(entries.take(1).map { it.copy(profileScopes = scopes) }, null, 0, {},
                selectionStore = fake)
        }
        compose.waitUntil(5000) { queries == 2 }
        compose.onNodeWithTag("artist_mosaic_photo_Midnight Oil", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("artist_mosaic_enrich_Midnight Oil", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun allLibrariesReactsToPersistedArtworkWithoutNavigation() {
        val scopes = listOf(
            ArtistProfileScope("phone", "Midnight Oil", listOf("a")),
            ArtistProfileScope("nas", "Midnight Oil", listOf("b")),
        )
        val profiles = FakeStore(portrait)
        val revision = MutableStateFlow(0L)
        val artwork = object : ArtistArtworkStore {
            var available = false
            var loads = 0
            override fun observeLibraryRevision(libraryId: String) = revision
            override suspend fun load(anchor: ArtistIdentityAnchor): List<ArtistArtworkSelection> {
                loads++
                if (!available) return emptyList()
                return listOf(ArtistArtworkSelection(
                    slot = ArtworkSlot.ARTIST_THUMB,
                    assetId = "sha256:${"c".repeat(64)}",
                    confirmedMbid = anchor.confirmedMbid,
                    localImagePath = "/tmp/reactive-aggregate.png",
                    focus = ArtworkFocus(),
                    manuallyChosen = false,
                    revision = revision.value,
                    source = ArtworkSource.COMMONS_P18,
                    status = ArtworkLoadStatus.READY,
                ))
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
        compose.setContent {
            ArtistMosaicGrid(
                entries.take(1).map { it.copy(profileScopes = scopes) },
                libraryId = null,
                refreshToken = 0,
                onOpenArtist = {},
                selectionStore = profiles,
                artworkStore = artwork,
            )
        }
        compose.waitUntil(5_000) { artwork.loads == 2 }
        compose.onNodeWithTag("artist_mosaic_photo_Midnight Oil", useUnmergedTree = true)
            .assertDoesNotExist()

        compose.runOnIdle {
            artwork.available = true
            revision.value = 1L
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("artist_mosaic_photo_Midnight Oil", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun allLibrariesNeverReusePortraitFromASingleLibrary() {
        val fake = FakeStore(portrait)
        compose.setContent {
            ArtistMosaicGrid(entries.take(1), null, 0, {}, selectionStore = fake)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("artist_mosaic_photo_Midnight Oil", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fake.queries) }
    }
    @Test fun cacheFailureKeepsMonogramWithoutInventingIdentityOrASecondCta() {
        var attempts = 0
        var opened: String? = null
        val fake = object : ArtistEnrichmentSelectionStore {
            override suspend fun load(libraryId: String, credit: String, albumIds: List<String>): PersistedArtistProfile? {
                attempts++
                throw IllegalStateException("Room unavailable")
            }
            override suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile) {}
            override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) {}
        }
        compose.setContent {
            ArtistMosaicGrid(entries.take(1), "library-1", 0, { opened = it }, selectionStore = fake)
        }
        compose.waitUntil(5000) { attempts == 1 }
        compose.onNodeWithTag("artist_mosaic_photo_Midnight Oil", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("artist_mosaic_retry_Midnight Oil", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("artist_mosaic_enrich_Midnight Oil", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("artist_mosaic_tile_Midnight Oil").performClick()
        compose.runOnIdle { assertEquals("Midnight Oil", opened) }
    }
}
