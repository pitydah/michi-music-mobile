package org.michimusic.link

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * A27: explicit protocol negotiation. The raw endpoint may not silently accept
 * a signed grant it never verified (legacy Micro's /download ignores grants).
 * This acknowledgement is NOT a cryptographic signature or proof against a
 * compromised pinned producer. It is a mandatory server-side contract marker.
 */
object AirCatProducerReceiptPolicy {
    const val REQUEST_HEADER = "X-Michi-AirCat-Grant"
    const val RESPONSE_HEADER = "X-Michi-AirCat-Authorized-Grant-SHA256"

    private val asciiB64Url = Regex("[A-Za-z0-9_-]{1,8192}")

    /** A46: enforce ASCII and canonical *unpadded* Base64url before hashing.
     * Char.isLetterOrDigit() accepts non-ASCII Unicode. Encoding that text as
     * US_ASCII can collapse distinct credentials into the same question marks.
     */
    fun validGrantHeader(header: String): Boolean {
        if (!asciiB64Url.matches(header)) return false
        return runCatching {
            val decoded = Base64.getUrlDecoder().decode(header)
            decoded.isNotEmpty() &&
                Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == header
        }.getOrDefault(false)
    }

    fun expectedReceipt(signedGrantHeader: String): String {
        require(validGrantHeader(signedGrantHeader)) { "AIRC_GRANT_HEADER_NONCANONICAL" }
        val sha = MessageDigest.getInstance("SHA-256").digest(
            signedGrantHeader.toByteArray(StandardCharsets.US_ASCII))
        val hex = "0123456789abcdef"
        return buildString(64) {
            for (byte in sha) {
                append(hex[(byte.toInt() ushr 4) and 0x0f])
                append(hex[byte.toInt() and 0x0f])
            }
        }
    }

    fun recognizesAuthorization(signedGrantHeader: String, responseHeader: String?): Boolean =
        responseHeader != null && responseHeader.matches(Regex("[a-f0-9]{64}")) &&
            runCatching { MessageDigest.isEqual(
                expectedReceipt(signedGrantHeader).toByteArray(StandardCharsets.US_ASCII),
                responseHeader.toByteArray(StandardCharsets.US_ASCII),
            ) }.getOrDefault(false)
}
