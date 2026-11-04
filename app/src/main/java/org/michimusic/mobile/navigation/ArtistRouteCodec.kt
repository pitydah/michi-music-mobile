package org.michimusic.mobile.navigation

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** The route identity is lossless for spaces, slashes, accents and even blank local credits. */
internal fun encodeArtistCredit(credit: String): String =
    "a" + Base64.getUrlEncoder().withoutPadding().encodeToString(credit.toByteArray(Charsets.UTF_8))

/**
 * Reject non-canonical Base64 and malformed UTF-8. Replacement characters could
 * otherwise select a different local artist credit after a navigation round-trip.
 * No network access and no guessing from a partial/ambiguous name.
 */
internal fun decodeArtistCredit(segment: String?): String? {
    if (segment == null || segment.length > 2049 ||
        !segment.matches(Regex("^a[A-Za-z0-9_-]*$"))) return null
    return try {
        val bytes = Base64.getUrlDecoder().decode(segment.substring(1))
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        decoded.takeIf { encodeArtistCredit(it) == segment }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: CharacterCodingException) {
        null
    }
}
