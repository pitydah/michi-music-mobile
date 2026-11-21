package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * ID3v2.4 text-frame values are NUL-separated after the encoding byte.
 * ID3v2.3 remains deliberately single-valued: slash splitting is lossy.
 * This codec is shared by private staging and readback; never touches audio.
 */
object MetadataId3TextListCodec {
    private const val MAX_VALUES = 32
    private const val MAX_CHARS = 65536

    fun decode(version: Int, payload: ByteArray): List<String>? {
        if (version !in 3..4 || payload.size < 2 || payload.size > 256 * 1024) return null
        val encoding = payload[0].toInt() and 255
        val text = payload.copyOfRange(1, payload.size)
        val charset = when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> {
                if (text.size < 2 || !((text[0] == 0xff.toByte() && text[1] == 0xfe.toByte()) ||
                    (text[0] == 0xfe.toByte() && text[1] == 0xff.toByte()))) return null
                StandardCharsets.UTF_16
            }
            2 -> if (version == 4) StandardCharsets.UTF_16BE else return null
            3 -> if (version == 4) StandardCharsets.UTF_8 else return null
            else -> return null
        }
        val decoded = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(text)).toString()
        } catch (_: CharacterCodingException) { return null }
        // At most one terminating NUL is accepted; two would conceal an empty
        // last value and could make a physical roundtrip falsely appear equal.
        if (decoded.endsWith("\u0000\u0000")) return null
        val withoutTerminator = if (decoded.endsWith('\u0000')) decoded.dropLast(1)
            else decoded
        val values = if (version == 4) withoutTerminator.split('\u0000')
            else listOf(withoutTerminator)
        if (values.isEmpty() || values.size > MAX_VALUES || values.any {
            it.isBlank() || it.length > MAX_CHARS || '\uFFFD' in it || '\u0000' in it ||
                it.any { c -> c.code < 32 && c != '\n' && c != '\t' }
        }) return null
        return values
    }

    fun encode(version: Int, values: List<String>): ByteArray? {
        if (version !in 3..4 || values.isEmpty() || values.size > MAX_VALUES ||
            (version == 3 && values.size != 1) || values.any {
                it.isBlank() || it.length > MAX_CHARS || '\u0000' in it || '\uFFFD' in it ||
                    it.any { c -> c.code < 32 && c != '\n' && c != '\t' }
            }) return null
        val joined = values.joinToString("\u0000")
        val bytes = if (version == 3) joined.toByteArray(StandardCharsets.UTF_16)
            else joined.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > 256 * 1024) return null
        return byteArrayOf(if (version == 3) 1 else 3) + bytes
    }
}
