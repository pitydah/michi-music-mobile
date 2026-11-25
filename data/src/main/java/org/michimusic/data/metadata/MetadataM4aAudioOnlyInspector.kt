package org.michimusic.data.metadata

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Bounded structural gate for the MP4 subset certified by the metadata editor.
 *
 * This is deliberately not a general media parser. It proves that the private
 * document has a non-fragmented MP4 atom tree, at least one audio track, no
 * video track, and records the exact concatenated `mdat` payload digest.
 */
internal object MetadataM4aAudioOnlyInspector {
    private const val MAX_FILE_BYTES = 1024L * 1024L * 1024L
    private const val MAX_ATOMS = 16_384
    private const val BUFFER_BYTES = 64 * 1024

    data class Evidence(
        val audioTracks: Int,
        val mediaPayloadBytes: Long,
        val mediaPayloadSha256: String,
    )

    sealed interface Result {
        data class AudioOnly(val evidence: Evidence) : Result
        data class Refused(val reason: String) : Result
    }

    private data class Atom(
        val type: String,
        val payload: Long,
        val end: Long,
    )

    fun inspect(channel: FileChannel): Result = try {
        val size = channel.size()
        require(size in 24..MAX_FILE_BYTES) { "INVALID_SIZE" }
        var atoms = 0
        var audioTracks = 0
        var videoTracks = 0
        var sawFtyp = false
        var sawMoov = false
        var sawMdat = false
        val mediaDigest = MessageDigest.getInstance("SHA-256")
        var mediaBytes = 0L

        fun next(at: Long, end: Long): Atom {
            require(++atoms <= MAX_ATOMS) { "ATOM_LIMIT" }
            require(at >= 0L && end >= at && at <= end - 8L) { "TRUNCATED_ATOM" }
            val header = read(channel, at, 16.coerceAtMost((end - at).toInt()))
            val size32 = ByteBuffer.wrap(header, 0, 4)
                .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            val headerBytes: Int
            val atomBytes = when (size32) {
                0L -> {
                    headerBytes = 8
                    end - at
                }
                1L -> {
                    require(header.size >= 16) { "TRUNCATED_EXTENDED_ATOM" }
                    headerBytes = 16
                    ByteBuffer.wrap(header, 8, 8).order(ByteOrder.BIG_ENDIAN).long
                }
                else -> {
                    headerBytes = 8
                    size32
                }
            }
            require(atomBytes >= headerBytes && atomBytes <= end - at) {
                "INVALID_ATOM_SIZE"
            }
            return Atom(type, at + headerBytes, at + atomBytes)
        }

        fun handlerInMdia(start: Long, end: Long): String? {
            var at = start
            var found: String? = null
            while (at < end) {
                val atom = next(at, end)
                if (atom.type == "hdlr") {
                    require(found == null && atom.end - atom.payload >= 12L) {
                        "INVALID_HANDLER"
                    }
                    found = String(read(channel, atom.payload + 8L, 4), Charsets.US_ASCII)
                }
                at = atom.end
            }
            return found
        }

        fun handlerInTrak(start: Long, end: Long): String? {
            var at = start
            var found: String? = null
            while (at < end) {
                val atom = next(at, end)
                if (atom.type == "mdia") {
                    require(found == null) { "DUPLICATE_MDIA" }
                    found = handlerInMdia(atom.payload, atom.end)
                }
                at = atom.end
            }
            return found
        }

        fun inspectMoov(start: Long, end: Long) {
            var at = start
            while (at < end) {
                val atom = next(at, end)
                if (atom.type == "trak") {
                    when (handlerInTrak(atom.payload, atom.end)) {
                        "soun" -> audioTracks++
                        "vide" -> videoTracks++
                        null -> error("TRACK_WITHOUT_HANDLER")
                    }
                }
                at = atom.end
            }
        }

        var at = 0L
        while (at < size) {
            val atom = next(at, size)
            when (atom.type) {
                "ftyp" -> {
                    require(!sawFtyp && at == 0L) { "INVALID_FTYP" }
                    sawFtyp = true
                }
                "moov" -> {
                    require(!sawMoov) { "DUPLICATE_MOOV" }
                    sawMoov = true
                    inspectMoov(atom.payload, atom.end)
                }
                "mdat" -> {
                    sawMdat = true
                    mediaBytes += digestRange(channel, atom.payload, atom.end, mediaDigest)
                }
                "moof", "mfra" -> error("FRAGMENTED_MP4_NOT_CERTIFIED")
            }
            at = atom.end
        }
        require(sawFtyp && sawMoov && sawMdat) { "REQUIRED_ATOM_MISSING" }
        require(audioTracks > 0) { "AUDIO_TRACK_MISSING" }
        require(videoTracks == 0) { "VIDEO_TRACK_PRESENT" }
        require(mediaBytes > 0L) { "EMPTY_MEDIA_PAYLOAD" }
        Result.AudioOnly(
            Evidence(
                audioTracks,
                mediaBytes,
                mediaDigest.digest().joinToString("") {
                    (it.toInt() and 255).toString(16).padStart(2, '0')
                },
            ),
        )
    } catch (error: Exception) {
        Result.Refused(error.message ?: error.javaClass.simpleName)
    }

    private fun read(channel: FileChannel, offset: Long, bytes: Int): ByteArray {
        require(bytes >= 0 && offset >= 0L && bytes.toLong() <= channel.size() - offset) {
            "READ_OUT_OF_RANGE"
        }
        val buffer = ByteBuffer.allocate(bytes)
        var at = offset
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, at)
            require(count > 0) { "TRUNCATED_READ" }
            at += count
        }
        return buffer.array()
    }

    private fun digestRange(
        channel: FileChannel,
        start: Long,
        end: Long,
        digest: MessageDigest,
    ): Long {
        require(start in 0..end && end <= channel.size()) { "INVALID_MEDIA_RANGE" }
        val buffer = ByteBuffer.allocate(BUFFER_BYTES)
        var at = start
        while (at < end) {
            buffer.clear()
            buffer.limit(minOf(BUFFER_BYTES.toLong(), end - at).toInt())
            val count = channel.read(buffer, at)
            require(count > 0) { "TRUNCATED_MEDIA_PAYLOAD" }
            at += count
            buffer.flip()
            digest.update(buffer)
        }
        return end - start
    }
}
