package org.michimusic.data.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Shared synthetic MP3 fixture builder for the ME-13 instrumented proofs. */
internal object Mp3FixtureTags {
    fun build(
        major: Int,
        title: String,
        artist: String,
        album: String,
        genre: String,
        audio: ByteArray,
        extraFrames: ByteArray = ByteArray(0),
        id3v1: Boolean = false,
    ): ByteArray {
        require(major == 3 || major == 4)
        val charset = if (major == 4) Charsets.UTF_8 else Charsets.ISO_8859_1
        val encoding = if (major == 4) 3 else 0
        fun frame(id: String, value: String): ByteArray {
            val text = byteArrayOf(encoding.toByte()) + value.toByteArray(charset)
            return rawFrame(major, id, text)
        }
        val frames = frame("TIT2", title) + frame("TPE1", artist) +
            frame("TALB", album) + frame("TCON", genre) + extraFrames
        val header = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(StandardCharsets.US_ASCII))
            write(byteArrayOf(major.toByte(), 0, 0))
            write(synchsafe(frames.size))
        }.toByteArray()
        val tag = header + frames
        return if (id3v1) tag + audio + id3v1(title, artist, album) else tag + audio
    }

    fun rawFrame(major: Int, id: String, payload: ByteArray): ByteArray {
        require(id.length == 4 && id.all { it in 'A'..'Z' || it in '0'..'9' })
        val size = if (major == 4) synchsafe(payload.size) else byteArrayOf(
            (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(), payload.size.toByte())
        return id.toByteArray(StandardCharsets.US_ASCII) + size + byteArrayOf(0, 0) + payload
    }

    fun textFrame(major: Int, id: String, value: String): ByteArray {
        val charset = if (major == 4) Charsets.UTF_8 else Charsets.ISO_8859_1
        val encoding = if (major == 4) 3 else 0
        return rawFrame(major, id,
            byteArrayOf(encoding.toByte()) + value.toByteArray(charset))
    }

    fun id3v1(title: String, artist: String, album: String): ByteArray {
        fun field(value: String, size: Int): ByteArray =
            value.toByteArray(Charsets.ISO_8859_1).copyOf(size)
        return "TAG".toByteArray(StandardCharsets.US_ASCII) +
            field(title, 30) + field(artist, 30) + field(album, 30) +
            field("2026", 4) + field("ME13", 30) + byteArrayOf(0)
    }

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(), ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(), (value and 0x7f).toByte())
}
