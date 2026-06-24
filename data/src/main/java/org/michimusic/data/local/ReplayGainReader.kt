package org.michimusic.data.local

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

object ReplayGainReader {

    private const val ID3_HEADER = "ID3"
    private const val FLAC_HEADER = "fLaC"
    private const val TXXX_FRAME = "TXXX"
    private const val VORBIS_REPLAYGAIN_TRACK = "REPLAYGAIN_TRACK_GAIN"
    private const val VORBIS_REPLAYGAIN_ALBUM = "REPLAYGAIN_ALBUM_GAIN"
    private const val ID3_REPLAYGAIN_TRACK = "REPLAYGAIN_TRACK_GAIN"
    private const val ID3_REPLAYGAIN_ALBUM = "REPLAYGAIN_ALBUM_GAIN"

    data class ReplayGainData(
        val trackGain: Float = Float.NaN,
        val albumGain: Float = Float.NaN,
    )

    fun read(filepath: String): ReplayGainData {
        val file = File(filepath)
        if (!file.exists() || !file.canRead()) return ReplayGainData()

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(4)
                if (raf.read(header) < 4) return ReplayGainData()

                val headerStr = String(header, Charsets.ISO_8859_1)
                when {
                    headerStr.startsWith(ID3_HEADER) -> readId3v2(raf)
                    headerStr.startsWith(FLAC_HEADER) -> readFlac(raf)
                    else -> ReplayGainData()
                }
            }
        } catch (_: Exception) {
            ReplayGainData()
        }
    }

    private fun readId3v2(raf: RandomAccessFile): ReplayGainData {
        val version = raf.readByte().toInt() and 0xFF
        raf.readByte() // revision
        raf.readByte() // flags
        val size = readSyncsafeInt(raf)

        if (version < 3) return ReplayGainData()

        val tagEnd = raf.filePointer + size
        var trackGain = Float.NaN
        var albumGain = Float.NaN

        while (raf.filePointer + 10 <= tagEnd) {
            val frameId = ByteArray(4)
            if (raf.read(frameId) < 4) break
            val id = String(frameId, Charsets.ISO_8859_1)

            val frameSize = if (version == 3) readBigEndianInt(raf) else readSyncsafeInt(raf)
            raf.readByte() // flags (2 bytes for v3+, skip all)
            raf.readByte()

            if (frameSize <= 1) {
                if (raf.filePointer < tagEnd) raf.seek(raf.filePointer + frameSize)
                continue
            }

            val frameData = ByteArray(frameSize)
            if (raf.read(frameData) < frameSize) break

            if (id == TXXX_FRAME) {
                val encoding = frameData[0].toInt() and 0xFF
                val nullTerminator = findNull(frameData, 1)
                if (nullTerminator < 0) continue
                val description = String(frameData, 1, nullTerminator - 1, encodingCharset(encoding))
                val valueStart = nullTerminator + 1
                val valueBytes = frameData.copyOfRange(valueStart, frameData.size)
                val value = trimTrailingNulls(String(valueBytes, encodingCharset(encoding)))

                when (description) {
                    ID3_REPLAYGAIN_TRACK -> trackGain = parseGain(value)
                    ID3_REPLAYGAIN_ALBUM -> albumGain = parseGain(value)
                }
            }

            if (trackGain.isFinite() && albumGain.isFinite()) break
        }

        return ReplayGainData(trackGain, albumGain)
    }

    private fun readFlac(raf: RandomAccessFile): ReplayGainData {
        var trackGain = Float.NaN
        var albumGain = Float.NaN

        while (true) {
            val isLast = (raf.readByte().toInt() and 0x80) != 0
            val blockType = raf.readByte().toInt() and 0x7F
            val blockSize = readBigEndianInt24(raf)

            if (blockType == 4) {
                val blockStart = raf.filePointer
                val vendorLen = readLittleEndianInt(raf)
                if (raf.skipBytes(vendorLen) < vendorLen) break
                val numComments = readLittleEndianInt(raf)

                for (i in 0 until numComments) {
                    val commentLen = readLittleEndianInt(raf)
                    val commentBytes = ByteArray(commentLen)
                    if (raf.read(commentBytes) < commentLen) break
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

                if (raf.filePointer < blockStart + blockSize) {
                    raf.seek(blockStart + blockSize)
                }
            } else {
                raf.seek(raf.filePointer + blockSize)
            }

            if (isLast) break
        }

        return ReplayGainData(trackGain, albumGain)
    }

    private fun parseGain(value: String): Float {
        val cleaned = value.trim().replace("dB", "").trim().toFloatOrNull()
        return cleaned ?: Float.NaN
    }

    private fun readSyncsafeInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.read(b)
        return (b[0].toInt() and 0x7F).shl(21) or
                (b[1].toInt() and 0x7F).shl(14) or
                (b[2].toInt() and 0x7F).shl(7) or
                (b[3].toInt() and 0x7F)
    }

    private fun readBigEndianInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.read(b)
        return (b[0].toInt() and 0xFF).shl(24) or
                (b[1].toInt() and 0xFF).shl(16) or
                (b[2].toInt() and 0xFF).shl(8) or
                (b[3].toInt() and 0xFF)
    }

    private fun readBigEndianInt24(raf: RandomAccessFile): Int {
        val b = ByteArray(3)
        raf.read(b)
        return (b[0].toInt() and 0xFF).shl(16) or
                (b[1].toInt() and 0xFF).shl(8) or
                (b[2].toInt() and 0xFF)
    }

    private fun readLittleEndianInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.read(b)
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
