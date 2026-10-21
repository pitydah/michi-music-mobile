package org.michimusic.data.local

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.nio.charset.Charset

object ReplayGainReader {

    private const val ID3_HEADER = "ID3"
    private const val FLAC_HEADER = "fLaC"
    private const val TXXX_FRAME = "TXXX"
    private const val VORBIS_REPLAYGAIN_TRACK = "REPLAYGAIN_TRACK_GAIN"
    private const val VORBIS_REPLAYGAIN_ALBUM = "REPLAYGAIN_ALBUM_GAIN"
    private const val ID3_REPLAYGAIN_TRACK = "REPLAYGAIN_TRACK_GAIN"
    private const val ID3_REPLAYGAIN_ALBUM = "REPLAYGAIN_ALBUM_GAIN"

    // Safe ceiling for ID3 / Vorbis comment block allocations to prevent OOM on corrupt files
    private const val MAX_TAG_SIZE = 16 * 1024 * 1024 // 16 MB
    private const val MAX_COMMENT_SIZE = 256 * 1024 // 256 KB

    data class ReplayGainData(
        val trackGain: Float = Float.NaN,
        val albumGain: Float = Float.NaN,
    )

    fun read(source: SeekableAudioSource): ReplayGainData {
        return try {
            val header = ByteArray(4)
            if (source.read(header) < 4) return ReplayGainData()

            val headerStr = String(header, Charsets.ISO_8859_1)
            when {
                headerStr.startsWith(ID3_HEADER) -> {
                    source.seek(3)
                    readId3v2(source)
                }
                headerStr.startsWith(FLAC_HEADER) -> {
                    source.seek(4)
                    readFlac(source)
                }
                else -> ReplayGainData()
            }
        } catch (_: Exception) {
            ReplayGainData()
        }
    }

    fun read(filepath: String): ReplayGainData {
        val file = File(filepath)
        if (!file.exists() || !file.canRead()) return ReplayGainData()
        return try {
            FileSeekableSource(file).use { read(it) }
        } catch (_: Exception) {
            ReplayGainData()
        }
    }

    fun read(context: Context?, uriString: String): ReplayGainData {
        if (uriString.startsWith("content://")) {
            val ctx = context ?: return ReplayGainData()
            return try {
                val uri = uriString.toUri()
                ContentUriSeekableSource(ctx.contentResolver, uri).use { read(it) }
            } catch (_: Exception) {
                ReplayGainData()
            }
        }
        return read(uriString)
    }

    private fun readId3v2(source: SeekableAudioSource): ReplayGainData {
        val version = source.readByte()
        if (version < 3) return ReplayGainData()
        source.readByte() // revision
        source.readByte() // flags
        val size = readSyncsafeInt(source)
        if (size <= 0 || size > MAX_TAG_SIZE) return ReplayGainData()

        val tagEnd = source.position() + size
        var trackGain = Float.NaN
        var albumGain = Float.NaN

        while (source.position() + 10 <= tagEnd) {
            val frameId = ByteArray(4)
            if (source.read(frameId) < 4) break
            val id = String(frameId, Charsets.ISO_8859_1)

            val frameSize = if (version == 3) readBigEndianInt(source) else readSyncsafeInt(source)
            source.readByte() // flags (2 bytes)
            source.readByte()

            if (frameSize <= 1 || frameSize > MAX_TAG_SIZE || source.position() + frameSize > tagEnd) {
                val remaining = tagEnd - source.position()
                val skip = frameSize.toLong().coerceAtMost(remaining)
                if (skip > 0) source.seek(source.position() + skip)
                continue
            }

            val frameData = ByteArray(frameSize)
            if (source.read(frameData) < frameSize) break

            if (id == TXXX_FRAME) {
                val encoding = frameData[0].toInt() and 0xFF
                val nullTerminator = findNull(frameData, 1)
                if (nullTerminator < 0 || nullTerminator >= frameData.size) continue
                val description = String(frameData, 1, nullTerminator - 1, encodingCharset(encoding))
                val valueStart = nullTerminator + 1
                if (valueStart <= frameData.size) {
                    val valueBytes = frameData.copyOfRange(valueStart, frameData.size)
                    val value = trimTrailingNulls(String(valueBytes, encodingCharset(encoding)))

                    when (description) {
                        ID3_REPLAYGAIN_TRACK -> trackGain = parseGain(value)
                        ID3_REPLAYGAIN_ALBUM -> albumGain = parseGain(value)
                    }
                }
            }

            if (trackGain.isFinite() && albumGain.isFinite()) break
        }

        return ReplayGainData(trackGain, albumGain)
    }

    private fun readFlac(source: SeekableAudioSource): ReplayGainData {
        var trackGain = Float.NaN
        var albumGain = Float.NaN

        while (true) {
            val headerByte = source.readByte()
            if (headerByte < 0) break
            val isLast = (headerByte and 0x80) != 0
            val blockType = headerByte and 0x7F
            val blockSize = readBigEndianInt24(source)
            if (blockSize < 0 || blockSize > MAX_TAG_SIZE) break

            if (blockType == 4) {
                val blockStart = source.position()
                val vendorLen = readLittleEndianInt(source)
                if (vendorLen < 0 || vendorLen > MAX_COMMENT_SIZE || source.skipBytes(vendorLen) < vendorLen) break
                val numComments = readLittleEndianInt(source)
                if (numComments < 0 || numComments > 10_000) break

                for (i in 0 until numComments) {
                    val commentLen = readLittleEndianInt(source)
                    if (commentLen < 0 || commentLen > MAX_COMMENT_SIZE) break
                    val commentBytes = ByteArray(commentLen)
                    if (source.read(commentBytes) < commentLen) break
                    val comment = String(commentBytes, Charsets.UTF_8)

                    val eqIdx = comment.indexOf('=')
                    if (eqIdx > 0) {
                        val key = comment.substring(0, eqIdx)
                        val value = comment.substring(eqIdx + 1)
                        when (key) {
                            VORBIS_REPLAYGAIN_TRACK -> trackGain = parseGain(value)
                            VORBIS_REPLAYGAIN_ALBUM -> albumGain = parseGain(value)
                        }
                    }
                }

                if (source.position() < blockStart + blockSize) {
                    source.seek(blockStart + blockSize)
                }
            } else {
                source.seek(source.position() + blockSize)
            }

            if (isLast) break
        }

        return ReplayGainData(trackGain, albumGain)
    }

    private fun parseGain(value: String): Float {
        val cleaned = value.trim().replace("dB", "").trim().toFloatOrNull()
        return cleaned ?: Float.NaN
    }

    private fun readSyncsafeInt(source: SeekableAudioSource): Int {
        val b = ByteArray(4)
        if (source.read(b) < 4) return 0
        return (b[0].toInt() and 0x7F).shl(21) or
                (b[1].toInt() and 0x7F).shl(14) or
                (b[2].toInt() and 0x7F).shl(7) or
                (b[3].toInt() and 0x7F)
    }

    private fun readBigEndianInt(source: SeekableAudioSource): Int {
        val b = ByteArray(4)
        if (source.read(b) < 4) return 0
        return (b[0].toInt() and 0xFF).shl(24) or
                (b[1].toInt() and 0xFF).shl(16) or
                (b[2].toInt() and 0xFF).shl(8) or
                (b[3].toInt() and 0xFF)
    }

    private fun readBigEndianInt24(source: SeekableAudioSource): Int {
        val b = ByteArray(3)
        if (source.read(b) < 3) return 0
        return (b[0].toInt() and 0xFF).shl(16) or
                (b[1].toInt() and 0xFF).shl(8) or
                (b[2].toInt() and 0xFF)
    }

    private fun readLittleEndianInt(source: SeekableAudioSource): Int {
        val b = ByteArray(4)
        if (source.read(b) < 4) return 0
        return (b[0].toInt() and 0xFF) or
                (b[1].toInt() and 0xFF).shl(8) or
                (b[2].toInt() and 0xFF).shl(16) or
                (b[3].toInt() and 0xFF)
    }

    private fun findNull(data: ByteArray, start: Int): Int {
        for (i in start until data.size) {
            if (data[i].toInt() == 0) return i
        }
        return -1
    }

    private fun trimTrailingNulls(s: String): String {
        var end = s.length
        while (end > 0 && s[end - 1] == '\u0000') end--
        return s.substring(0, end)
    }

    private fun encodingCharset(encoding: Int): Charset = when (encoding) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }
}
