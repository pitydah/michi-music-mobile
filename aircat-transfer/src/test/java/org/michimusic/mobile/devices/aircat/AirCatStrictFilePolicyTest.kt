package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AirCatStrictFilePolicyTest {
    private val goodHash = "a".repeat(64)

    @Test fun acceptsValidRawFile() = assertNull(AirCatStrictFilePolicy.refusal("raw-remote-id", 1024, goodHash))
    @Test fun rejectsMissingRemoteId() = assertNotNull(AirCatStrictFilePolicy.refusal("  ", 1024, goodHash))
    @Test fun rejectsEmptyDigest() = assertNotNull(AirCatStrictFilePolicy.refusal("remote", 1024, ""))
    @Test fun rejectsNonHexDigest() = assertNotNull(AirCatStrictFilePolicy.refusal("remote", 1024, "z".repeat(64)))
    @Test fun rejectsShortDigest() = assertNotNull(AirCatStrictFilePolicy.refusal("remote", 1024, "a".repeat(63)))
    @Test fun rejectsNegativeSize() = assertNotNull(AirCatStrictFilePolicy.refusal("remote", -1, goodHash))
    @Test fun rejectsZeroSize() = assertNotNull(AirCatStrictFilePolicy.refusal("remote", 0, goodHash))
    @Test fun acceptsUppercaseDigest() = assertNull(AirCatStrictFilePolicy.refusal("remote", 7, "AB".repeat(32)))
}
