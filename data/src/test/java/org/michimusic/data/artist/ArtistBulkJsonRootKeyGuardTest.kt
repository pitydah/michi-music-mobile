package org.michimusic.data.artist

import org.junit.Assert.*
import org.junit.Test

/** Real JVM execution without a JSON runtime: tests the D30 lexical duplicate-ID guard. */
class ArtistBulkJsonRootKeyGuardTest {
    private fun count(text: String) = ArtistBulkJsonRootKeyGuard.rootIdCount(text)

    @Test fun oneRootIdIsCounted() { assertEquals(1, count("""{"id":"x"}""")) }
    @Test fun nestedIdIsNotCounted() {
        assertEquals(1, count("""{"metadata":{"id":"inner","notes":[{"id":"another"}]},"id":"outer"}"""))
    }
    @Test fun escapedStringDelimitersDoNotCauseFalseRootCommas() {
        assertEquals(1, count("""{"name":"artist, \\"id\\": fake","id":"x"}"""))
    }
    @Test fun duplicateRootIdIsRejected() {
        assertNull(count("""{"id":"first","name":"test","id":"second"}"""))
    }
    @Test fun escapedDuplicateIdKeyIsRejected() {
        assertNull(count("""{"id":"first","\u0069d":"second"}"""))
    }
    @Test fun escapedIdKeyRecognizedAsRealId() {
        assertEquals(1, count("""{"\u0069d":"value"}"""))
    }
    @Test fun rootArrayIsRejected() { assertNull(count("""[{"id":"x"}]""")) }
    @Test fun missingRootIdReportsZeroNotOne() { assertEquals(0, count("""{"name":"x"}""")) }
    @Test fun unterminatedKeyFailsClosed() { assertNull(count("""{"id:"foo"}""")) }
    @Test fun extraDepthFailsClosed() {
        assertNull(count("""{"id":"x","data":${"[".repeat(49)}1${"]".repeat(49)}}"""))
    }
    @Test fun tooManyRootPropertiesFailsClosed() {
        val text = (1..130).joinToString(",", prefix="{", postfix="}") { "\"k$it\":$it" }
        assertNull(count(text))
    }
    @Test fun nonAsciiKeyDoesNotMasqueradeAsId() { assertEquals(0, count("""{"íd":"x"}""")) }
    @Test fun invalidUnicodeKeyEscapeFailsClosed() { assertNull(count("""{"\u0xx1":"x"}""")) }
    @Test fun emptyObjectHasNoId() { assertEquals(0, count("{}")) }
}
