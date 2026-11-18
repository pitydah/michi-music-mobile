package org.michimusic.link

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class AirCatOriginalHttpPolicyTest {
    private fun check(
        offset: Long = 0L,
        status: Int = 200,
        range: String? = null,
        length: Long? = 8L,
        encoding: String? = null,
        etag: String? = "\"revision-A\"",
        expected: String? = null,
    ) = AirCatOriginalHttpPolicy.evaluate(offset, status, range, length, encoding, etag, expected)

    @Test fun initialFullOriginalAccepted() = assertEquals(AirCatOriginalHttpPolicy.Action.STREAM, check().action)
    @Test fun encodedGzipRejected() = assertEquals("AIRC_HTTP_TRANSFORMED_CONTENT", check(encoding = "gzip").refusal)
    @Test fun encodedBrotliRejected() = assertFalse(check(encoding = "br").allowed)
    @Test fun identityEncodingAccepted() = assertTrue(check(encoding = "IdEnTiTy").allowed)
    @Test fun weakEtagRejected() = assertEquals("AIRC_HTTP_STRONG_ETAG_REQUIRED", check(etag = "W/\"revision-A\"").refusal)
    @Test fun absentEtagRejected() = assertFalse(check(etag = null).allowed)
    @Test fun badEtagControlRejected() = assertFalse(check(etag = "\"bad\r\"" ).allowed)
    @Test fun fullResponseWithContentRangeRejected() = assertFalse(check(range = "bytes 0-7/8").allowed)
    @Test fun unsolicitedPartialRejected() = assertFalse(check(status = 206, range = "bytes 0-7/8").allowed)
    @Test fun zeroLengthOriginalRejectedBeforeShaValidation() =
        assertEquals("AIRC_HTTP_EMPTY_ORIGINAL", check(length = 0).refusal)
    @Test fun invalidNegativeLengthRejected() = assertFalse(check(length = -1).allowed)
    @Test fun resumeRangeAccepted() = assertEquals(AirCatOriginalHttpPolicy.Action.STREAM,
        check(offset = 3, status = 206, range = "bytes 3-7/8", length = 5, expected = "\"revision-A\"").action)
    @Test fun resumeRangeWrongOffsetRejected() = assertFalse(check(offset = 4, status = 206, range = "bytes 3-7/8", length = 5, expected = "\"revision-A\"").allowed)
    @Test fun resumeRangeWrongEtagRejected() = assertFalse(check(offset = 3, status = 206, range = "bytes 3-7/8", length = 5, expected = "\"revision-B\"").allowed)
    @Test fun resumeRangeWeakExpectedEtagRejected() = assertFalse(check(offset = 3, status = 206, range = "bytes 3-7/8", length = 5, expected = "W/\"revision-A\"").allowed)
    @Test fun resumeRangeMissingTotalRejected() = assertFalse(check(offset = 3, status = 206, range = "bytes 3-7/*", length = 5, expected = "\"revision-A\"").allowed)
    @Test fun resumeRangeWrongTotalRejected() = assertFalse(check(offset = 3, status = 206, range = "bytes 3-8/8", length = 6, expected = "\"revision-A\"").allowed)
    @Test fun resumeRangeWrongLengthRejected() = assertFalse(check(offset = 3, status = 206, range = "bytes 3-7/8", length = 4, expected = "\"revision-A\"").allowed)
    @Test fun resumeRangeOverflowRejected() = assertFalse(check(offset = Long.MAX_VALUE - 1, status = 206, range = "bytes 9223372036854775806-9223372036854775807/9223372036854775807", expected = "\"revision-A\"").allowed)
    @Test fun resumeFullResponseRequestsRestartWithoutStreaming() = assertEquals(AirCatOriginalHttpPolicy.Action.RESTART_FROM_ZERO,
        check(offset = 3, expected = "\"revision-A\"").action)
    @Test fun redirectNotAFile() = assertFalse(check(status = 302).allowed)
    @Test fun negativeOffsetRejected() = assertFalse(check(offset = -1).allowed)
}
