package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistArtworkAutoPolicyTest {
    private val anchor = ArtistIdentityAnchor(
        libraryId = "library-a",
        creditKey = "artist-credit",
        albumGroupKey = "persisted-association-id",
        confirmedMbid = "05072650-4b05-4e8b-83d0-dba2576e306a",
        verifiedQid = "Q48284",
        displayCredit = "Midnight Oil",
    )
    private fun conditions() = AutoArtworkConditions(
        enabled = true,
        paused = false,
        associationNeedsConfirmation = false,
        networkAvailable = true,
        unmeteredOnly = true,
        onUnmeteredNetwork = true,
        batteryNotLow = true,
        storageNotLow = true,
        nowEpochMs = 1_000L,
    )
    private fun selection(
        slot: ArtworkSlot,
        manual: Boolean = false,
        status: ArtworkLoadStatus = ArtworkLoadStatus.READY,
        mbid: String = anchor.confirmedMbid,
    ) = ArtistArtworkSelection(
        slot = slot,
        assetId = "sha256:${"a".repeat(64)}",
        confirmedMbid = mbid,
        localImagePath = "/private/artist-artwork/image.png",
        focus = ArtworkFocus(),
        manuallyChosen = manual,
        revision = 1,
        status = status,
    )

    @Test fun missingSlotsAreScheduledOnlyForVerifiedIdentity() {
        assertEquals(
            AutoArtworkDecision.Eligible(setOf(ArtworkSlot.ARTIST_THUMB, ArtworkSlot.ARTIST_HERO)),
            ArtistArtworkAutoPolicy.evaluate(anchor, emptyList(), conditions()),
        )
        assertEquals(
            AutoArtworkDecision.Blocked(AutoArtworkBlock.IDENTITY_NOT_CONFIRMED),
            ArtistArtworkAutoPolicy.evaluate(null, emptyList(), conditions()),
        )
        assertEquals(
            AutoArtworkDecision.Blocked(AutoArtworkBlock.QID_NOT_VERIFIED),
            ArtistArtworkAutoPolicy.evaluate(anchor.copy(verifiedQid = null), emptyList(), conditions()),
        )
        assertEquals(
            AutoArtworkDecision.Blocked(AutoArtworkBlock.QID_NOT_VERIFIED),
            ArtistArtworkAutoPolicy.evaluate(anchor.copy(verifiedQid = "Qinvalid"), emptyList(), conditions()),
        )
    }

    @Test fun cachedThumbAndManualHeroNeverTriggerReplacement() {
        val selections = listOf(
            selection(ArtworkSlot.ARTIST_THUMB),
            selection(ArtworkSlot.ARTIST_HERO, manual = true, status = ArtworkLoadStatus.CACHE_ERROR),
        )
        assertEquals(
            AutoArtworkDecision.Blocked(AutoArtworkBlock.NOTHING_TO_FETCH),
            ArtistArtworkAutoPolicy.evaluate(anchor, selections, conditions()),
        )
    }

    @Test fun wrongIdentityDoesNotSatisfySlot() {
        val selections = listOf(selection(ArtworkSlot.ARTIST_THUMB, mbid = "11111111-1111-4111-8111-111111111111"))
        assertEquals(
            AutoArtworkDecision.Eligible(setOf(ArtworkSlot.ARTIST_THUMB, ArtworkSlot.ARTIST_HERO)),
            ArtistArtworkAutoPolicy.evaluate(anchor, selections, conditions()),
        )
    }

    @Test fun uncertainAlbumBindingsNeverStartArtwork() {
        assertEquals(
            AutoArtworkDecision.Blocked(AutoArtworkBlock.ASSOCIATION_NEEDS_CONFIRMATION),
            ArtistArtworkAutoPolicy.evaluate(anchor, emptyList(), conditions().copy(associationNeedsConfirmation = true)),
        )
    }

    @Test fun optOutPauseNetworkPowerStorageAndCooldownBlockTheJob() {
        val variants = listOf(
            conditions().copy(enabled = false) to AutoArtworkBlock.DISABLED,
            conditions().copy(paused = true) to AutoArtworkBlock.PAUSED,
            conditions().copy(networkAvailable = false) to AutoArtworkBlock.NETWORK_UNAVAILABLE,
            conditions().copy(onUnmeteredNetwork = false) to AutoArtworkBlock.NETWORK_UNAVAILABLE,
            conditions().copy(batteryNotLow = false) to AutoArtworkBlock.BATTERY_LOW,
            conditions().copy(storageNotLow = false) to AutoArtworkBlock.STORAGE_LOW,
            conditions().copy(nextRetryAtEpochMs = 1_001L) to AutoArtworkBlock.COOLDOWN,
        )
        for ((variant, expected) in variants) {
            assertEquals(
                AutoArtworkDecision.Blocked(expected),
                ArtistArtworkAutoPolicy.evaluate(anchor, emptyList(), variant),
            )
        }
    }

    @Test fun onlyMissingHeroIsEligible() {
        val selections = listOf(selection(ArtworkSlot.ARTIST_THUMB))
        assertEquals(
            AutoArtworkDecision.Eligible(setOf(ArtworkSlot.ARTIST_HERO)),
            ArtistArtworkAutoPolicy.evaluate(anchor, selections, conditions()),
        )
    }
    @Test fun previousIdentityManualSelectionNeverTriggersReplacement() {
        val previousMbid = "11111111-1111-4111-8111-111111111111"
        val prior = listOf(selection(ArtworkSlot.ARTIST_THUMB, manual = true, mbid = previousMbid))
        val action = ArtistArtworkAutoPolicy.evaluate(anchor, prior, conditions(),
            manualSlots = setOf(ArtworkSlot.ARTIST_HERO))
        assertEquals(AutoArtworkDecision.Blocked(AutoArtworkBlock.NOTHING_TO_FETCH), action)
    }

    @Test fun manualSlotWithoutCurrentProfileRowIsStillProtected() {
        val action = ArtistArtworkAutoPolicy.evaluate(anchor, emptyList(), conditions(),
            manualSlots = setOf(ArtworkSlot.ARTIST_THUMB))
        assertEquals(AutoArtworkDecision.Eligible(setOf(ArtworkSlot.ARTIST_HERO)), action)
    }
}
