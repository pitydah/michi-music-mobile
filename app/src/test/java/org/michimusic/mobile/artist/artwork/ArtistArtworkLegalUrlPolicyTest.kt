package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistArtworkLegalUrlPolicyTest {
    @Test fun acceptsExactUnportedCreativeCommonsLicenses() {
        assertEquals("by-4.0", ArtistArtworkLegalUrlPolicy.canonicalKey("https://creativecommons.org/licenses/by/4.0/"))
        assertEquals("by-sa-3.0", ArtistArtworkLegalUrlPolicy.canonicalKey("https://creativecommons.org/licenses/by-sa/3.0/"))
        assertEquals("cc0-1.0", ArtistArtworkLegalUrlPolicy.canonicalKey("https://creativecommons.org/publicdomain/zero/1.0/"))
        assertEquals("publicdomain", ArtistArtworkLegalUrlPolicy.canonicalKey("https://creativecommons.org/publicdomain/mark/1.0/"))
    }

    @Test fun rejectsUrlSuffixAndMissingVersion() {
        for (url in listOf(
            "https://creativecommons.org/licenses/by/4.0/trap/",
            "https://creativecommons.org/licenses/by/",
            "https://creativecommons.org/licenses/by/5.0/",
            "https://creativecommons.org/licenses/by/4.0/deed.es",
            "https://creativecommons.org/licenses/by/4.0/?track=1",
            "https://creativecommons.org/licenses/by/4.0/#fragment",
            "https://creativecommons.org/licenses/by/4.0/CL/",
        )) assertEquals(null, ArtistArtworkLegalUrlPolicy.canonicalKey(url))
    }

    @Test fun rejectsRestrictedAndHostSpoofedLegalUrls() {
        for (url in listOf(
            "https://creativecommons.org/licenses/by-nc/4.0/",
            "https://creativecommons.org/licenses/by-nd/4.0/",
            "https://creativecommons.org:443/licenses/by/4.0/",
            "https://creativecommons.org.evil.test/licenses/by/4.0/",
            "https://creative:password@creativecommons.org/licenses/by/4.0/",
            "http://creativecommons.org/licenses/by/4.0/",
            "https://creativecommons.org/publicdomain/zero/2.0/",
        )) assertEquals(null, ArtistArtworkLegalUrlPolicy.canonicalKey(url))
    }
}
