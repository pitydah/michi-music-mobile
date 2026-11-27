package org.michimusic.data.artist

import org.junit.Assert.*
import org.junit.Test

/** Requires the real kotlinx.serialization JSON runtime in OpenCode / Android test setup. */
class ArtistBulkJsonIdentityTest {
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private fun parse(s: String) = ArtistBulkJsonIdentity.artistMbid(s.toByteArray(Charsets.UTF_8))

    @Test fun artistMbidFromActualJsonNotCallerHint() {
        assertEquals(mbid, parse("""{"id":"$mbid","name":"a-ha","type":"Group"}"""))
    }
    @Test fun caseFoldCanonicalUuidFromBody() {
        assertEquals(mbid, parse("""{"id":"${mbid.uppercase()}","name":"a-ha"}"""))
    }
    @Test fun duplicateLiteralAndEscapedIdRejected() {
        assertNull(parse("""{"id":"$mbid","\u0069d":"$mbid"}"""))
    }
    @Test fun missingIdOrWrongTopLevelRejected() {
        assertNull(parse("""{"name":"a-ha"}"""))
        assertNull(parse("""[{"id":"$mbid"}]"""))
    }
    @Test fun numericIdOrBadUuidRejected() {
        assertNull(parse("""{"id":1234}"""))
        assertNull(parse("""{"id":"not-a-uuid"}"""))
    }
    @Test fun structurallyBrokenJsonRejected() {
        assertNull(parse("""{"id":"$mbid",,"name":"x"}"""))
    }
    @Test fun malformedUtf8Rejected() {
        assertNull(ArtistBulkJsonIdentity.artistMbid(byteArrayOf(0xc3.toByte(), 0x28)))
    }
    @Test fun rawBodyLimitRejected() {
        assertNull(ArtistBulkJsonIdentity.artistMbid(ByteArray(256 * 1024 + 1)))
    }
    @Test fun escapedNestedIdentityIsNotRoot() {
        assertNull(parse("""{"metadata":{"id":"$mbid"},"name":"a-ha"}"""))
    }
}
