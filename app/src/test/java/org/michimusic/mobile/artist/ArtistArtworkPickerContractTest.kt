package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkResolutionReport
import org.michimusic.mobile.artist.artwork.ArtworkRights
import org.michimusic.mobile.artist.artwork.ArtworkSource
import org.michimusic.mobile.artist.artwork.ArtworkEvidence
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.artistIdentityAnchor

/**
 * K48-R07: the picker and the summary make their decisions in pure code, so the two
 * contracts that decide what a user sees - one saved destination versus two, and one
 * column versus two - are pinned here without a device.
 */
class ArtistArtworkPickerContractTest {

    @Test
    fun `a saved destination is reported as saved`() {
        assertEquals(
            "Fotografía guardada para mosaico.",
            singleDestinationMessage(ArtworkLoadStatus.READY, "mosaico"),
        )
    }

    @Test
    fun `a rejected licence is named as a licence problem`() {
        assertEquals(
            "El origen no confirma una licencia admitida.",
            singleDestinationMessage(ArtworkLoadStatus.REJECTED, "ficha"),
        )
    }

    @Test
    fun `an unknown failure keeps its own status instead of claiming success`() {
        assertEquals(
            "No fue posible guardar la fotografía (provider_error).",
            singleDestinationMessage(ArtworkLoadStatus.PROVIDER_ERROR, "ficha"),
        )
    }

    @Test
    fun `both destinations saved is reported once`() {
        assertEquals(
            "Fotografía guardada en mosaico y ficha.",
            bothDestinationsMessage(saved(ArtworkSlot.ARTIST_THUMB), saved(ArtworkSlot.ARTIST_HERO)),
        )
    }

    @Test
    fun `a mosaic saved with a failed card is reported as partial`() {
        assertEquals(
            "Fotografía guardada en mosaico. No se pudo completar la ficha.",
            bothDestinationsMessage(
                saved(ArtworkSlot.ARTIST_THUMB),
                failed(ArtworkSlot.ARTIST_HERO, ArtworkLoadStatus.REJECTED),
            ),
        )
    }

    @Test
    fun `a card saved with a failed mosaic is reported as partial`() {
        assertEquals(
            "Fotografía guardada en ficha. No se pudo completar el mosaico.",
            bothDestinationsMessage(
                failed(ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.PROVIDER_ERROR),
                saved(ArtworkSlot.ARTIST_HERO),
            ),
        )
    }

    @Test
    fun `two failures are never reported as a partial success`() {
        val message = bothDestinationsMessage(
            failed(ArtworkSlot.ARTIST_THUMB, ArtworkLoadStatus.CACHE_ERROR),
            failed(ArtworkSlot.ARTIST_HERO, ArtworkLoadStatus.REJECTED),
        )

        assertEquals("No se pudo guardar la fotografía en ninguno de los destinos.", message)
        assertFalse(message.contains("mosaico y ficha"))
    }

    @Test
    fun `a failure on the second destination never discards the one already saved`() {
        val mosaicOnly = runBlocking {
            saveToBothDestinations(
                chooser = { _, _, slot ->
                    if (slot == ArtworkSlot.ARTIST_HERO) throw IllegalStateException("peer went away")
                    report(ArtworkLoadStatus.READY, slot)
                },
                anchor = anchorFixture(),
                candidate = candidateFixture(),
            )
        }

        assertEquals(ArtworkSlot.ARTIST_THUMB, mosaicOnly.first().slot)
        assertTrue("the mosaic was persisted", mosaicOnly.first().saved)
        assertFalse("the card was not", mosaicOnly.last().saved)
        assertEquals(SlotSaveFailure.THREW, mosaicOnly.last().failure)

        val merged = mergeSavedSelections(
            emptyMap(),
            mosaicOnly,
        )
        assertEquals(
            "the surviving slot is published",
            setOf(ArtworkSlot.ARTIST_THUMB),
            merged.keys,
        )
    }

    @Test
    fun `a failed destination never erases the other slot already on screen`() {
        val existing = mapOf(ArtworkSlot.ARTIST_HERO to selectionFixture(ArtworkSlot.ARTIST_HERO))

        val merged = mergeSavedSelections(
            current = existing,
            outcomes = listOf(
                saved(ArtworkSlot.ARTIST_THUMB),
                failed(ArtworkSlot.ARTIST_HERO, ArtworkLoadStatus.REJECTED),
            ),
        )

        assertTrue("the untouched card stays", merged.containsKey(ArtworkSlot.ARTIST_HERO))
        assertTrue("the new mosaic joins it", merged.containsKey(ArtworkSlot.ARTIST_THUMB))
        assertEquals(2, merged.size)
    }

    @Test
    fun `a destination that reports no persisted selection is never announced as saved`() {
        val outcome = runBlocking {
            saveToDestination(
                chooser = { _, _, _ -> report(null) },
                anchor = anchorFixture(),
                candidate = candidateFixture(),
                slot = ArtworkSlot.ARTIST_THUMB,
            )
        }

        assertFalse(outcome.saved)
        assertEquals(ArtworkLoadStatus.REJECTED, outcome.status)
        assertEquals(SlotSaveFailure.PROVIDER_REJECTED, outcome.failure)
    }

    @Test
    fun `three hundred and twenty dp keeps one column`() {
        assertFalse(artworkSlotsInline(widthDp = 320, fontScale = 1f))
    }

    @Test
    fun `three hundred and sixty dp stacks because two columns would clip the CTA`() {
        assertFalse(artworkSlotsInline(widthDp = 360, fontScale = 1f))
    }

    @Test
    fun `three hundred and ninety dp allows two columns at normal text`() {
        assertTrue(artworkSlotsInline(widthDp = 390, fontScale = 1f))
    }

    @Test
    fun `four hundred and twelve and four hundred and eighty dp allow two columns`() {
        assertTrue(artworkSlotsInline(widthDp = 412, fontScale = 1f))
        assertTrue(artworkSlotsInline(widthDp = 480, fontScale = 1f))
    }

    @Test
    fun `one point three font scale still allows two columns at four hundred and eighty dp`() {
        assertTrue(artworkSlotsInline(widthDp = 480, fontScale = 1.3f))
    }

    @Test
    fun `large font scale prefers one column at every width`() {
        listOf(320, 360, 390, 412, 480).forEach { width ->
            assertFalse(
                "width $width with font scale 1.6 must stack",
                artworkSlotsInline(widthDp = width, fontScale = 1.6f),
            )
            assertFalse(
                "width $width with font scale 2.0 must stack",
                artworkSlotsInline(widthDp = width, fontScale = 2.0f),
            )
        }
    }

    private fun selectionFixture(slot: ArtworkSlot) = ArtistArtworkSelection(
        slot = slot,
        assetId = "openverse:consent",
        confirmedMbid = MBID,
        localImagePath = "/data/local/tmp/never-read.jpg",
        focus = ArtworkFocus(),
        manuallyChosen = true,
        revision = 1,
        status = ArtworkLoadStatus.READY,
    )

    private fun saved(slot: ArtworkSlot) = SlotSaveOutcome(
        slot = slot,
        status = ArtworkLoadStatus.READY,
        persistedSelection = selectionFixture(slot),
        failure = null,
    )

    private fun failed(slot: ArtworkSlot, status: ArtworkLoadStatus) = SlotSaveOutcome(
        slot = slot,
        status = status,
        persistedSelection = null,
        failure = SlotSaveFailure.PROVIDER_REJECTED,
    )

    private fun report(status: ArtworkLoadStatus?, slot: ArtworkSlot? = null): ArtworkResolutionReport =
        ArtworkResolutionReport(
            status = status ?: ArtworkLoadStatus.REJECTED,
            outcomes = emptyList(),
            selectedAssets = emptyMap(),
            selections = if (status == ArtworkLoadStatus.READY && slot != null) {
                mapOf(slot to selectionFixture(slot))
            } else {
                emptyMap()
            },
        )

    private fun anchorFixture() = artistIdentityAnchor(
        libraryId = "lib-consent",
        credit = "Midnight Oil",
        albumIds = listOf("album-consent"),
        profile = profileFixture(),
    )!!

    private fun profileFixture() = ArtistExternalProfile(
        mbid = MBID,
        name = "Midnight Oil",
        type = "Group",
        area = "Australia",
        disambiguation = "",
        wikidataQid = "Q48284",
    )

    private fun candidateFixture() = ArtistArtworkCandidate(
        source = ArtworkSource.OPENVERSE,
        stableAssetId = "openverse:consent",
        confirmedMbid = MBID,
        verifiedQid = "Q48284",
        canonicalSourcePage = "https://example.invalid/page",
        imageUrl = "https://example.invalid/image.jpg",
        originalWidth = 1024,
        originalHeight = 768,
        mimeType = "image/jpeg",
        licenseId = "cc-by-2.0",
        licenseUrl = "https://creativecommons.org/licenses/by/2.0/",
        author = "Charles Millot",
        attribution = "Charles Millot",
        evidence = ArtworkEvidence.UNVERIFIED,
        rights = ArtworkRights.REQUIRES_REVIEW,
    )

    private companion object {
        const val MBID = "1f2e3d4c-5b6a-4798-8c1d-2e3f4a5b6c7d"
    }
}
