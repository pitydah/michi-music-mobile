package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.ArtistExternalProfile

class ArtistArtworkPolicyTest {
    private val anchor = ArtistIdentityAnchor(
        libraryId = "lib-local",
        creditKey = "credit-key",
        albumGroupKey = "group-key",
        confirmedMbid = "05072650-4b05-4e8b-83d0-dba2576e306a",
        verifiedQid = "Q48284",
        displayCredit = "Midnight Oil",
    )

    private fun candidate(
        source: ArtworkSource = ArtworkSource.COMMONS_P18,
        imageUrl: String = "https://upload.wikimedia.org/wikipedia/commons/7/77/midnight.jpg",
        sourcePage: String = "https://commons.wikimedia.org/wiki/File:Midnight.jpg",
        licenseId: String? = "CC BY-SA 2.0",
        licenseUrl: String? = "https://creativecommons.org/licenses/by-sa/2.0/",
        attribution: String? = "Fab_Photo_29",
        rights: ArtworkRights = ArtworkRights.VERIFIED_ALLOWED,
        evidence: ArtworkEvidence = ArtworkEvidence.VERIFIED_QID_REFERENCE,
        mbid: String = anchor.confirmedMbid,
        qid: String? = anchor.verifiedQid,
    ) = ArtistArtworkCandidate(
        source = source,
        stableAssetId = "commons:midnight.jpg",
        confirmedMbid = mbid,
        verifiedQid = qid,
        canonicalSourcePage = sourcePage,
        imageUrl = imageUrl,
        originalWidth = 1600,
        originalHeight = 900,
        mimeType = "image/jpeg",
        licenseId = licenseId,
        licenseUrl = licenseUrl,
        author = "Fab_Photo_29",
        attribution = attribution,
        evidence = evidence,
        rights = rights,
    )

    @Test
    fun verifiedCommonsCandidatePassesEveryGate() {
        assertTrue(allowedArtworkCandidate(candidate(), anchor))
        assertTrue(allowedArtworkCandidate(
            candidate(imageUrl = "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/77/midnight.jpg/960px.jpg"),
            anchor,
        ))
    }

    @Test
    fun mismatchedIdentityNeverPassesEvenWithAValidLicense() {
        assertFalse(allowedArtworkCandidate(candidate(mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"), anchor))
        assertFalse(allowedArtworkCandidate(candidate(qid = "Q999999"), anchor))
    }

    @Test
    fun nonCommercialAndUnknownLicensesAreRejected() {
        assertFalse(allowedArtworkCandidate(candidate(
            licenseId = "CC BY-NC 2.0",
            licenseUrl = "https://creativecommons.org/licenses/by-nc/2.0/",
        ), anchor))
        assertFalse(allowedArtworkCandidate(candidate(licenseId = "All rights reserved"), anchor))
        assertNull(artworkLicenseLabel("CC BY-ND 3.0", "https://creativecommons.org/licenses/by-nd/3.0/"))
    }

    @Test
    fun meaninglessCommonsAttributionIsRejected() {
        assertFalse(allowedArtworkCandidate(candidate(attribution = "None"), anchor))
        assertFalse(allowedArtworkCandidate(candidate(attribution = " null "), anchor))
    }

    @Test
    fun untrustedImageAndSourceHostsAreRejected() {
        assertFalse(allowedArtworkCandidate(
            candidate(imageUrl = "http://upload.wikimedia.org/x.jpg"), anchor,
        ))
        assertFalse(allowedArtworkCandidate(
            candidate(imageUrl = "https://evil.example/x.jpg"), anchor,
        ))
        assertFalse(allowedArtworkCandidate(
            candidate(sourcePage = "https://example.org/wiki/File:Midnight.jpg"), anchor,
        ))
    }

    @Test
    fun openverseIsManualOnlyAndRequiresFlickrOrigin() {
        val flickr = candidate(
            source = ArtworkSource.OPENVERSE,
            imageUrl = "https://live.staticflickr.com/65535/51904192325_abc_b.jpg",
            sourcePage = "https://www.flickr.com/photos/130010035@N04/51904192325",
            licenseId = "CC BY 2.0",
            licenseUrl = "https://creativecommons.org/licenses/by/2.0/",
            rights = ArtworkRights.REQUIRES_REVIEW,
            evidence = ArtworkEvidence.UNVERIFIED,
            qid = null,
        )
        assertFalse(flickr.isAutoEligible())
        assertTrue(flickr.isManualEligible())
        // The raw Openverse result is unverified and must not pass the selection gate.
        assertFalse(allowedArtworkCandidate(flickr, anchor))
        assertTrue(allowedArtworkCandidate(
            flickr.copy(evidence = ArtworkEvidence.USER_SELECTED, rights = ArtworkRights.VERIFIED_ALLOWED),
            anchor,
        ))
        assertFalse(allowedArtworkCandidate(
            flickr.copy(imageUrl = "https://example.org/photo.jpg"), anchor,
        ))
        assertFalse(allowedArtworkCandidate(
            flickr.copy(canonicalSourcePage = "https://example.org/photos/1"), anchor,
        ))
    }

    @Test
    fun flickrOriginPageMustStateTheExactLicenseForTheSamePhoto() {
        val page = "<!DOCTYPE html><html><script type=\"application/ld+json\">" +
            "{\"@graph\":[{\"@type\":\"ImageObject\"," +
            "\"contentUrl\":\"https://live.staticflickr.com/65535/51904192325_00abed41a8_b.jpg\"," +
            "\"license\":\"https://creativecommons.org/licenses/by/2.0/\"," +
            "\"acquireLicensePage\":\"https://www.flickr.com/photos/130010035@N04/51904192325\"}]}" +
            "</script></html>"
        assertTrue(originPageConfirmsLicense(
            "https://www.flickr.com/photos/130010035@N04/51904192325",
            "https://creativecommons.org/licenses/by/2.0/",
            page,
        ))
        assertFalse(originPageConfirmsLicense(
            "https://www.flickr.com/photos/130010035@N04/51904192325",
            "https://creativecommons.org/licenses/by-sa/2.0/",
            page,
        ))
        assertFalse(originPageConfirmsLicense(
            "https://www.flickr.com/photos/130010035@N04/99999999999",
            "https://creativecommons.org/licenses/by/2.0/",
            page,
        ))
        assertFalse(originPageConfirmsLicense(
            "https://example.org/photos/1",
            "https://creativecommons.org/licenses/by/2.0/",
            page,
        ))

        // Openverse uses the Flickr NSID; Flickr may canonicalize the page to a vanity
        // owner. The exact numeric photo id is the stable same-file evidence.
        val vanityPage = page.replace("130010035@N04", "comedynose")
        assertTrue(originPageConfirmsLicense(
            "https://www.flickr.com/photos/130010035@N04/51904192325",
            "https://creativecommons.org/licenses/by/2.0/",
            vanityPage,
        ))

        // A longer numeric id that merely contains the expected id is a different
        // photograph. Exact path/filename boundaries are mandatory.
        val longerId = "<!DOCTYPE html><html><script type=\"application/ld+json\">" +
            "{\"@graph\":[{\"@type\":\"ImageObject\"," +
            "\"contentUrl\":\"https://live.staticflickr.com/65535/519041923250_00abed41a8_b.jpg\"," +
            "\"license\":\"https://creativecommons.org/licenses/by/2.0/\"," +
            "\"acquireLicensePage\":\"https://www.flickr.com/photos/130010035@N04/519041923250\"}]}" +
            "</script></html>"
        assertFalse(originPageConfirmsLicense(
            "https://www.flickr.com/photos/130010035@N04/51904192325",
            "https://creativecommons.org/licenses/by/2.0/",
            longerId,
        ))

        // A correct licence for a different ImageObject in the same @graph is not
        // evidence for this photo.
        val otherPhoto = "<!DOCTYPE html><html><script type=\"application/ld+json\">" +
            "{\"@graph\":[" +
            "{\"@type\":\"ImageObject\",\"contentUrl\":\"https://live.staticflickr.com/1/99999999999_b.jpg\"," +
            "\"license\":\"https://creativecommons.org/licenses/by/2.0/\"}," +
            "{\"@type\":\"ImageObject\",\"contentUrl\":\"https://live.staticflickr.com/1/51904192325_b.jpg\"," +
            "\"license\":\"https://creativecommons.org/licenses/by-sa/2.0/\"}" +
            "]}</script></html>"
        assertFalse(originPageConfirmsLicense(
            "https://www.flickr.com/photos/130010035@N04/51904192325",
            "https://creativecommons.org/licenses/by/2.0/",
            otherPhoto,
        ))
    }

    @Test
    fun aLicenseLinkForAnotherFlickrPhotoDoesNotConfirmThisCandidate() {
        // The page mentions the expected photo and a CC BY link, but the licence belongs
        // to a different ImageObject. A broad URL grep must never accept this.
        val page = "<!DOCTYPE html><html>" +
            "<link rel=\"canonical\" href=\"https://www.flickr.com/photos/130010035@N04/51904192325\">" +
            "<a href=\"https://creativecommons.org/licenses/by/2.0/\">CC BY 2.0</a>" +
            "<script type=\"application/ld+json\">" +
            "{\"@type\":\"ImageObject\"," +
            "\"contentUrl\":\"https://live.staticflickr.com/65535/11111111111_x_b.jpg\"," +
            "\"license\":\"https://creativecommons.org/licenses/by/2.0/\"," +
            "\"acquireLicensePage\":\"https://www.flickr.com/photos/130010035@N04/11111111111\"}" +
            "</script></html>"
        assertFalse(originPageConfirmsLicense(
            "https://www.flickr.com/photos/130010035@N04/51904192325",
            "https://creativecommons.org/licenses/by/2.0/",
            page,
        ))
    }

    @Test
    fun jsonLdWithoutThePhotoIdIsNotEnough() {
        val page = "<!DOCTYPE html><html><script type=\"application/ld+json\">" +
            "{\"@type\":\"ImageObject\"," +
            "\"contentUrl\":\"https://live.staticflickr.com/65535/51904192325_x_b.jpg\"," +
            "\"license\":\"https://creativecommons.org/licenses/by-sa/2.0/\"," +
            "\"acquireLicensePage\":\"https://www.flickr.com/photos/130010035@N04/11111111111\"}" +
            "</script></html>"
        assertFalse(originPageConfirmsLicense(
            "https://www.flickr.com/photos/130010035@N04/51904192325",
            "https://creativecommons.org/licenses/by-sa/2.0/",
            page,
        ))
    }

    @Test
    fun rankingPrefersVerifiedSourceThenSlotAspectThenResolution() {
        val p18 = candidate()
        val p180 = candidate(source = ArtworkSource.COMMONS_P180, imageUrl = "https://upload.wikimedia.org/x.jpg")
        val pageImage = candidate(source = ArtworkSource.WIKIPEDIA_PAGEIMAGE, imageUrl = "https://upload.wikimedia.org/y.jpg")
        val ranked = listOf(pageImage, p180, p18).sortedWith(artworkRankingComparator(ArtworkSlot.ARTIST_HERO))
        assertEquals(listOf(p18, p180, pageImage), ranked)

        val square = candidate().copy(stableAssetId = "commons:square", originalWidth = 1000, originalHeight = 1000)
        val wide = candidate().copy(stableAssetId = "commons:wide", originalWidth = 1600, originalHeight = 900)
        assertEquals(wide, listOf(square, wide).sortedWith(artworkRankingComparator(ArtworkSlot.ARTIST_HERO)).first())

        val small = candidate().copy(stableAssetId = "commons:small", originalWidth = 800, originalHeight = 450)
        val large = candidate().copy(stableAssetId = "commons:large", originalWidth = 2400, originalHeight = 1350)
        assertEquals(large, listOf(small, large).sortedWith(artworkRankingComparator(ArtworkSlot.ARTIST_HERO)).first())
    }

    @Test
    fun thumbCanServeHeroOnlyWhenItHasWideEnoughPixels() {
        assertTrue(canReuseThumbAsHero(candidate().copy(originalWidth = 1000, originalHeight = 700)))
        assertFalse(canReuseThumbAsHero(candidate().copy(originalWidth = 600, originalHeight = 600)))
        assertFalse(canReuseThumbAsHero(candidate().copy(originalWidth = 900, originalHeight = 1200)))
    }

    @Test
    fun anchorRequiresLocalKeyVerifiedMbidAndConsistentQid() {
        val profile = ArtistExternalProfile(
            mbid = anchor.confirmedMbid,
            name = "Midnight Oil",
            wikidataQid = "Q48284",
        )
        val built = artistIdentityAnchor(
            "lib-local", "Midnight Oil", listOf("album-1", "album-2"), profile,
        )
        assertEquals("Q48284", built?.verifiedQid)
        assertEquals(anchor.confirmedMbid, built?.confirmedMbid)

        assertNull(artistIdentityAnchor(null, "Midnight Oil", listOf("album-1"), profile))
        assertNull(artistIdentityAnchor("lib-local", "Midnight Oil", emptyList(), profile))
        assertNull(artistIdentityAnchor("lib-local", "Midnight Oil", listOf("album-1"), null))
        assertNull(artistIdentityAnchor(
            "lib-local", "Midnight Oil", listOf("album-1"), profile.copy(mbid = "not-an-mbid"),
        ))
    }

    @Test
    fun canonicalCommonsIdNormalizesTheFileTitleWithoutInventingOne() {
        assertEquals("commons:midnight oil.jpg", canonicalCommonsAssetId("File:Midnight_Oil.jpg"))
        assertNull(canonicalCommonsAssetId("File:../escape.jpg"))
        assertNull(canonicalCommonsAssetId(" "))
    }
}
