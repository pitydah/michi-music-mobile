package org.michimusic.mobile.artist.artwork
import org.junit.Assert.assertEquals
import org.junit.Test

class CommonsLicenseAliasesTest {
    @Test fun punctuationOnlyLicenseAliasesMatchExactCreativeCommonsCode() {
        assertEquals("CC BY-SA 4.0", CommonsLicenseConsistency.acceptedLabel(
            "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/"))
        assertEquals("CC BY 3.0", CommonsLicenseConsistency.acceptedLabel(
            "CC_BY_3.0", "https://creativecommons.org/licenses/by/3.0/"))
    }
    @Test fun incompatibleOrNonCommercialAliasesAreStillRejected() {
        assertEquals(null, CommonsLicenseConsistency.acceptedLabel(
            "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by/4.0/"))
        assertEquals(null, CommonsLicenseConsistency.acceptedLabel(
            "CC-BY-NC-4.0", "https://creativecommons.org/licenses/by/4.0/"))
        assertEquals(null, CommonsLicenseConsistency.acceptedLabel(
            "CC-BY-SA-9.0", "https://creativecommons.org/licenses/by-sa/9.0/"))
    }
}
