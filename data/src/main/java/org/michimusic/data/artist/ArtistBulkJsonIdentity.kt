package org.michimusic.data.artist

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * D30. Inspect the ACTUAL response body, never a claimed parsed id supplied by a caller.
 * This is still untrusted JSON data, NOT an authenticated network receipt or a verified
 * association. The D27 staged NO_IO lease cannot dispatch or publish profiles.
 *
 * Json parses the complete value; the small lexical guard below only rejects duplicate
 * top-level "id" spellings which ordinary map-shaped parsers may silently overwrite.
 */
internal object ArtistBulkJsonIdentity {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val uuid = Regex("^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$")

    fun artistMbid(body: ByteArray): String? {
        if (body.isEmpty() || body.size > ArtistBulkProviderResultPolicy.MAX_BODY_BYTES) return null
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (_: Exception) { return null }
        // Reject hidden duplicate keys, deeply nested bodies and non-object roots BEFORE
        // Json.parseToJsonElement, which may coalesce duplicate JSON object properties.
        if (ArtistBulkJsonRootKeyGuard.rootIdCount(decoded) != 1) return null
        val root = try { json.parseToJsonElement(decoded) as? JsonObject } catch (_: Exception) { null }
            ?: return null
        val rawId = (root["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val canonical = rawId.lowercase(Locale.ROOT)
        return canonical.takeIf(uuid::matches)
    }

}
