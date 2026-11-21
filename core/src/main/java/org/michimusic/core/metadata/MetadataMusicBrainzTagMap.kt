package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.util.Locale

/**
 * ME-10C: shared physical names for MusicBrainz identifiers. This is a strict,
 * bounded representation of the _selected_ IDs, not a musical identification engine.
 * Unknown TXXX descriptions and Vorbis comments are preserved outside this map.
 */
object MetadataMusicBrainzTagMap {
    private val tagIds = linkedMapOf(
        MetadataField.MUSICBRAINZ_RECORDING_ID to "MusicBrainz Track Id",
        MetadataField.MUSICBRAINZ_RELEASE_ID to "MusicBrainz Album Id",
        MetadataField.MUSICBRAINZ_RELEASE_TRACK_ID to "MusicBrainz Release Track Id",
        MetadataField.MUSICBRAINZ_RELEASE_GROUP_ID to "MusicBrainz Release Group Id",
        MetadataField.MUSICBRAINZ_WORK_ID to "MusicBrainz Work Id",
        MetadataField.MUSICBRAINZ_ARTIST_ID to "MusicBrainz Artist Id",
        MetadataField.MUSICBRAINZ_LABEL_ID to "MusicBrainz Label Id",
    )
    val id3Fields: Set<MetadataField> = tagIds.keys
    private val vorbis = linkedMapOf(
        MetadataField.MUSICBRAINZ_RECORDING_ID to setOf("MUSICBRAINZ_TRACKID", "MUSICBRAINZ_RECORDINGID"),
        MetadataField.MUSICBRAINZ_RELEASE_ID to setOf("MUSICBRAINZ_ALBUMID", "MUSICBRAINZ_RELEASEID"),
        MetadataField.MUSICBRAINZ_RELEASE_TRACK_ID to setOf("MUSICBRAINZ_RELEASETRACKID"),
        MetadataField.MUSICBRAINZ_RELEASE_GROUP_ID to setOf("MUSICBRAINZ_RELEASEGROUPID"),
        MetadataField.MUSICBRAINZ_WORK_ID to setOf("MUSICBRAINZ_WORKID"),
        MetadataField.MUSICBRAINZ_ARTIST_ID to setOf("MUSICBRAINZ_ARTISTID"),
        MetadataField.MUSICBRAINZ_LABEL_ID to setOf("MUSICBRAINZ_LABELID"),
    )
    val flacKeys: Map<MetadataField, Set<String>> = vorbis
    val id3Names: Map<MetadataField, String> = tagIds
    private val aliases: Map<String, MetadataField> = buildMap {
        for ((field, name) in tagIds) put(canon(name), field)
        put("musicbrainz recording id", MetadataField.MUSICBRAINZ_RECORDING_ID)
        put("musicbrainz release id", MetadataField.MUSICBRAINZ_RELEASE_ID)
        for ((field, keys) in vorbis) for (key in keys) put(canon(key), field)
    }
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private fun canon(description: String) = description.trim().lowercase(Locale.ROOT)
        .replace(Regex("[ _-]+"), " ")
    fun fromId3Description(description: String): MetadataField? = aliases[canon(description)]
    fun fromFlacKey(key: String): MetadataField? =
        vorbis.entries.firstOrNull { key.uppercase(Locale.ROOT) in it.value }?.key
    fun valid(field: MetadataField, values: List<String>): Boolean = field in tagIds &&
        values.size <= (if (field.multiple) 32 else 1) && values.all { value ->
            value == value.lowercase(Locale.ROOT) && uuid.matches(value)
        }

    /**
     * UFID can also carry a recording MBID under MusicBrainz's owner URL.
     * `null` means its owner could not be read and recording absence is NOT proven.
     * Existing UFID bytes are never rewritten by this staging codec.
     */
    fun ufidMayBeMusicBrainz(payload: ByteArray): Boolean? {
        if (payload.size !in 3..8192) return null
        val delimiter = payload.indexOf(0.toByte())
        if (delimiter < 1 || delimiter >= payload.lastIndex || delimiter > 255) return null
        val owner = payload.copyOfRange(0, delimiter)
        if (owner.any { it.toInt() !in 33..126 }) return null
        val value = String(owner, StandardCharsets.US_ASCII).lowercase(Locale.ROOT)
        return value == "http://musicbrainz.org" || value == "https://musicbrainz.org" ||
            value == "musicbrainz.org"
    }

    /** TXXX's description and value are independent of text frame value splitting. */
    data class Id3UserText(val description: String, val value: String)

    private fun charset(version: Int, encoding: Int, content: ByteArray): Charset? = when (encoding) {
        0 -> StandardCharsets.ISO_8859_1
        1 -> if (content.size >= 2 && ((content[0] == 0xff.toByte() && content[1] == 0xfe.toByte()) ||
            (content[0] == 0xfe.toByte() && content[1] == 0xff.toByte()))) StandardCharsets.UTF_16 else null
        2 -> if (version == 4) StandardCharsets.UTF_16BE else null
        3 -> if (version == 4) StandardCharsets.UTF_8 else null
        else -> null
    }

    /** Invalid UTF, NUL cardinality, or size returns null; never guess a target. */
    fun decodeId3(payload: ByteArray, version: Int): Id3UserText? {
        if (version !in 3..4 || payload.size !in 4..8192) return null
        val bytes = payload.copyOfRange(1, payload.size)
        val decoder = charset(version, payload[0].toInt() and 255, bytes)?.newDecoder()
            ?.onMalformedInput(CodingErrorAction.REPORT)
            ?.onUnmappableCharacter(CodingErrorAction.REPORT) ?: return null
        val decoded = try { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: CharacterCodingException) { return null }
        val split = decoded.indexOf('\u0000')
        if (split <= 0 || split >= decoded.lastIndex) return null
        val description = decoded.substring(0, split)
        val value = decoded.substring(split + 1).trimEnd('\u0000')
        if (description.isBlank() || description.length > 128 ||
            value.isEmpty() || value.length > 2048 || '\u0000' in value ||
            '\uFFFD' in decoded || decoded.any { it.code < 32 && it != '\u0000' }) return null
        return Id3UserText(description, value)
    }

    /** Only single ID values are emitted for ID3; multi-ID requires separate certification. */
    fun encodeId3(version: Int, field: MetadataField, value: String): ByteArray {
        require(version == 3 || version == 4)
        require(valid(field, listOf(value)))
        val text = id3Names.getValue(field) + "\u0000" + value
        val encoding = if (version == 3) 1 else 3
        return byteArrayOf(encoding.toByte()) + text.toByteArray(
            if (version == 3) StandardCharsets.UTF_16 else StandardCharsets.UTF_8)
    }
}
