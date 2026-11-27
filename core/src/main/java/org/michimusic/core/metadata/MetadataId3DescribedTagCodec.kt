package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset

/**
 * ME-10D: strictly bounded, typed ID3 COMM / USLT text. Does not represent
 * artwork, language variants, or SYLT as ordinary single-value text tags.
 * The staging path addresses each entry by (kind, language, descriptor):
 * existing entries may be replaced or cleared, and a verified-absent identity
 * may be added as a new frame. Every other language/descriptor is observed as a
 * typed entry and is NOT silently overwritten or flattened into a text value.
 */
object MetadataId3DescribedTagCodec {
    enum class Kind(val frameId: String, val field: MetadataField, val maxChars: Int) {
        COMMENT("COMM", MetadataField.COMMENT, 8192),
        LYRICS("USLT", MetadataField.LYRICS, 65536)
    }
    val fields = Kind.entries.mapTo(linkedSetOf()) { it.field }
    data class Entry(val kind: Kind, val language: String, val description: String, val text: String) {
        val canonical: Boolean get() = language == "eng" && description.isEmpty()
    }
    private const val MAX_PAYLOAD = 160 * 1024
    private const val MAX_DESCRIPTION = 128
    private val languageCode = Regex("[a-z]{3}")

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { null }

    /** Only well-formed, complete values are exposed; null = unknown, not absent. */
    fun decode(kind: Kind, version: Int, payload: ByteArray): Entry? {
        if (version !in 3..4 || payload.size !in 5..MAX_PAYLOAD) return null
        val encoding = payload[0].toInt() and 0xff
        if (encoding !in 0..3 || (version == 3 && encoding >= 2)) return null
        val languageBytes = payload.copyOfRange(1, 4)
        if (languageBytes.any { it.toInt() !in 97..122 }) return null
        val language = String(languageBytes, StandardCharsets.US_ASCII)
        val double = encoding == 1 || encoding == 2
        val start = 4
        val termAt = if (!double) {
            (start until payload.size).firstOrNull { payload[it] == 0.toByte() } ?: return null
        } else {
            (start until payload.size - 1 step 2).firstOrNull {
                payload[it] == 0.toByte() && payload[it + 1] == 0.toByte()
            } ?: return null
        }
        val descBytes = payload.copyOfRange(start, termAt)
        val textBytes = payload.copyOfRange(termAt + if (double) 2 else 1, payload.size)
        if (textBytes.isEmpty() || descBytes.size > 2 * (MAX_DESCRIPTION + 1)) return null
        val charset = when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            else -> StandardCharsets.UTF_8
        }
        if (encoding == 1) {
            // Require a BOM for both independent UTF-16 strings. A blank
            // descriptor has the BOM followed immediately by its delimiter.
            fun hasBom(b: ByteArray) = b.size >= 2 &&
                ((b[0] == 0xff.toByte() && b[1] == 0xfe.toByte()) ||
                 (b[0] == 0xfe.toByte() && b[1] == 0xff.toByte()))
            if (!hasBom(descBytes) || !hasBom(textBytes)) return null
        }
        val description = strict(descBytes, charset) ?: return null
        val text = strict(textBytes, charset)?.trimEnd('\u0000') ?: return null
        if (description.length > MAX_DESCRIPTION || description.any { it.isISOControl() } ||
            text.isBlank() || text.length > kind.maxChars ||
            '\u0000' in text || '\ufffd' in text || '\ufffd' in description ||
            text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return null
        return Entry(kind, language, description, text)
    }

    /** Safe automatic target only for a single English, no-description frame. */
    fun encodeCanonical(kind: Kind, version: Int, text: String): ByteArray {
        require(version == 3 || version == 4)
        require(text.isNotBlank() && text.length <= kind.maxChars && '\u0000' !in text &&
            '\ufffd' !in text && text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' })
        val desc = if (version == 3) byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0) else byteArrayOf(0)
        val content = text.toByteArray(if (version == 3) StandardCharsets.UTF_16 else StandardCharsets.UTF_8)
        val bytes = byteArrayOf(if (version == 3) 1 else 3) + "eng".toByteArray(StandardCharsets.US_ASCII) + desc + content
        require(bytes.size <= MAX_PAYLOAD)
        return bytes
    }
}
