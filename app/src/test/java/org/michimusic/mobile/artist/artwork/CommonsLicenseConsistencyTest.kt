package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommonsLicenseConsistencyTest {
    @Test fun canonicalRightsAcceptOnlyPublishedOpenFamilies() {
        assertEquals("CC BY 4.0", CommonsLicenseConsistency.acceptedLabel(
            "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/"))
        assertEquals("CC BY-SA 2.5", CommonsLicenseConsistency.acceptedLabel(
            "CC BY-SA 2.5", "https://creativecommons.org/licenses/by-sa/2.5/"))
        assertEquals("CC BY-SA 3.0", CommonsLicenseConsistency.acceptedLabel(
            null, "https://creativecommons.org/licenses/by-sa/3.0/"))
    }
    @Test fun equivalentPublicDomainMetadataDoesNotBlockVerifiedRights() {
        assertEquals("CC0", CommonsLicenseConsistency.acceptedLabel(
            "CC0 1.0", "https://creativecommons.org/publicdomain/zero/1.0/"))
        assertEquals("Public domain", CommonsLicenseConsistency.acceptedLabel(
            "Public Domain Mark 1.0", "https://creativecommons.org/publicdomain/mark/1.0/"))
        assertEquals("CC0", CommonsLicenseConsistency.acceptedLabel(
            null, "https://creativecommons.org/publicdomain/zero/1.0/"))
    }
    @Test fun neverUpgradeContradictoryOrUnknownRights() {
        val url = "https://creativecommons.org/licenses/by/4.0/"
        for (label in listOf("CC BY-SA 4.0", "CC BY 3.0", "CC BY-NC 4.0", "Public domain"))
            assertNull(CommonsLicenseConsistency.acceptedLabel(label, url))
        for (restrictedUrl in listOf(
            "https://creativecommons.org/licenses/by-nc/4.0/",
            "https://creativecommons.org/licenses/by-nd/4.0/",
            "https://creativecommons.org/licenses/by-sa/9.9/",
            "https://creativecommons.org/licenses/by/5.0/",
            "https://creativecommons.org/licenses/by-sa/3.0/es/",
            "https://creativecommons.org/licenses/by-sa/4.0/?extra=unknown",
            "https://creativecommons.org/licenses/by/4.0/evil",
            "https://creativecommons.org.evil.tld/licenses/by/4.0/",
            "http://creativecommons.org/licenses/by/4.0/",
            "https://creativecommons.org/licenses/by/4.0/#frag",
        )) assertNull(CommonsLicenseConsistency.acceptedLabel(null, restrictedUrl))
    }
}
