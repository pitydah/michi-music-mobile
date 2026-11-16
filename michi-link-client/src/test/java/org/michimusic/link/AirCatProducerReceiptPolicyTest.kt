package org.michimusic.link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirCatProducerReceiptPolicyTest {
    private val header = "YWlyY2F0LWdyYW50"
    @Test fun canonicalBase64UrlHeaderAccepted() {
        assertTrue(AirCatProducerReceiptPolicy.validGrantHeader(header))
    }
    @Test fun nonAsciiHomoglyphRejectedBeforeHashing() {
        assertFalse(AirCatProducerReceiptPolicy.validGrantHeader("YWlyY2F0LWdyYW50é"))
    }
    @Test fun paddedBase64UrlRejected() {
        assertFalse(AirCatProducerReceiptPolicy.validGrantHeader("YQ=="))
    }
    @Test fun invalidLengthModFourRejected() {
        assertFalse(AirCatProducerReceiptPolicy.validGrantHeader("A"))
    }
    @Test fun canonicalRoundTripRejectsTrailingNoncanonicalBits() {
        assertFalse(AirCatProducerReceiptPolicy.validGrantHeader("YR"))
    }

    @Test fun generatedReceiptIsHex256() {
        val hash = AirCatProducerReceiptPolicy.expectedReceipt(header)
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("[a-f0-9]{64}")))
    }
    @Test fun producerRecognizesExactAuthorizedGrant() {
        assertTrue(AirCatProducerReceiptPolicy.recognizesAuthorization(
            header, AirCatProducerReceiptPolicy.expectedReceipt(header)))
    }
    @Test fun missingReceiptRefusesLegacyRaw() {
        assertFalse(AirCatProducerReceiptPolicy.recognizesAuthorization(header,null))
    }
    @Test fun differentGrantCannotBorrowReceipt() {
        assertFalse(AirCatProducerReceiptPolicy.recognizesAuthorization(
            header+"A", AirCatProducerReceiptPolicy.expectedReceipt(header)))
    }
    @Test fun uppercaseReceiptRefused() {
        assertFalse(AirCatProducerReceiptPolicy.recognizesAuthorization(
            header, AirCatProducerReceiptPolicy.expectedReceipt(header).uppercase()))
    }
    @Test fun emptyGrantCannotProduceReceipt() {
        assertTrue(runCatching { AirCatProducerReceiptPolicy.expectedReceipt("") }.isFailure)
    }
    @Test fun headerInjectionCannotProduceReceipt() {
        assertTrue(runCatching { AirCatProducerReceiptPolicy.expectedReceipt("abc\r\nX:other") }.isFailure)
    }
    @Test fun mismatchedPrefixRefused() {
        assertFalse(AirCatProducerReceiptPolicy.recognizesAuthorization(
            header, "sha256/"+AirCatProducerReceiptPolicy.expectedReceipt(header)))
    }
}
