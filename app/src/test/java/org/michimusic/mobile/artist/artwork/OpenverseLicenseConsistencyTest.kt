package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenverseLicenseConsistencyTest {
    @Test fun exactByVersionIsAccepted() {
        assertEquals("CC BY 4.0", OpenverseLicenseConsistency.acceptedLabel(
            "by", "4.0", "https://creativecommons.org/licenses/by/4.0/"))
    }
    @Test fun exactBySaVersionIsAccepted() {
        assertEquals("CC BY-SA 4.0", OpenverseLicenseConsistency.acceptedLabel(
            "by-sa", "4.0", "https://creativecommons.org/licenses/by-sa/4.0/"))
    }
    @Test fun missingVersionCanBeSafelyDerivedFromCanonicalUrl() {
        assertEquals("CC BY-SA 4.0", OpenverseLicenseConsistency.acceptedLabel(
            "by-sa", null, "https://creativecommons.org/licenses/by-sa/4.0/"))
    }
    @Test fun wrongLicenseFamilyIsRejected() {
        assertEquals(null, OpenverseLicenseConsistency.acceptedLabel(
            "by", "4.0", "https://creativecommons.org/licenses/by-sa/4.0/"))
        assertEquals(null, OpenverseLicenseConsistency.acceptedLabel(
            "pdm", null, "https://creativecommons.org/licenses/by/4.0/"))
    }
    @Test fun wrongVersionIsRejected() {
        assertEquals(null, OpenverseLicenseConsistency.acceptedLabel(
            "by", "3.0", "https://creativecommons.org/licenses/by/4.0/"))
    }
    @Test fun restrictedLicensesAndUnknownUrlAreRejected() {
        for (restricted in listOf("by-nc", "by-nd", "by-nc-sa", "all-rights-reserved")) {
            assertEquals(null, OpenverseLicenseConsistency.acceptedLabel(
                restricted, "4.0", "https://creativecommons.org/licenses/by/4.0/"))
        }
        assertEquals(null, OpenverseLicenseConsistency.acceptedLabel(
            "by", "4.0", "https://example.com/licenses/by/4.0"))
    }
    @Test fun cc0CannotBeUpgradedByIndexLabel() {
        assertEquals("CC0", OpenverseLicenseConsistency.acceptedLabel(
            "cc0", "1.0", "https://creativecommons.org/publicdomain/zero/1.0/"))
        assertEquals(null, OpenverseLicenseConsistency.acceptedLabel(
            "by", "4.0", "https://creativecommons.org/publicdomain/zero/1.0/"))
    }
}
