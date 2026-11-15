package org.michimusic.data.repository

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures prove the EXISTING parsers expose MBIDs; no extra media query is required. */
class MusicBrainzEmbeddedExtractionTest {
    private val artistId = "bd6d4f63-5cd0-4d78-a292-167997794c21"
    private val albumId = "05072650-4b05-4e8b-83d0-dba2576e306a"

    private fun be(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        .putInt(value).array()
    private fun le(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value).array()
    private fun bytes(vararg parts: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        parts.forEach(output::write)
        return output.toByteArray()
    }
    private fun atom(type: String, payload: ByteArray): ByteArray =
        bytes(be(payload.size + 8), type.toByteArray(Charsets.ISO_8859_1), payload)
    private fun synchsafe(n: Int) = byteArrayOf((n shr 21).toByte(),
        ((n shr 14) and 127).toByte(), ((n shr 7) and 127).toByte(), (n and 127).toByte())

    private fun txxx(name: String, id: String): ByteArray {
        val data = bytes(byteArrayOf(3), "$name\u0000$id".toByteArray(Charsets.UTF_8))
        return bytes("TXXX".toByteArray(Charsets.US_ASCII), be(data.size), byteArrayOf(0,0), data)
    }
    @Test fun id3v23TxxxKeepsArtistAndAlbumArtistSeparate() {
        val frames = bytes(txxx("MusicBrainz Artist Id", artistId),
            txxx("MusicBrainz Album Artist Id", albumId),
            txxx("MusicBrainz Track Id", artistId),
            txxx("MusicBrainz Album Id", albumId))
        val body = bytes("ID3".toByteArray(), byteArrayOf(3,0,0), synchsafe(frames.size), frames)
        val found = EmbeddedTagFieldsReader.read(ByteArrayInputStream(body))!!
        assertEquals(listOf(artistId), found.musicBrainzArtistIds)
        assertEquals(listOf(albumId), found.musicBrainzAlbumArtistIds)
        assertEquals(listOf(artistId), found.musicBrainzRecordingIds)
        assertEquals(listOf(albumId), found.musicBrainzReleaseIds)
    }

    @Test fun flacVorbisCommentsKeepRolesWithoutNameMatching() {
        val entries = listOf("MUSICBRAINZ_ARTISTID=$artistId",
            "MUSICBRAINZ_ALBUMARTISTID=$albumId",
            "MUSICBRAINZ_RECORDINGID=$artistId",
            "MUSICBRAINZ_RELEASEID=$albumId").map { it.toByteArray() }
        val vendor = "michi-test".toByteArray()
        val comments = bytes(le(vendor.size), vendor, le(entries.size), *entries.map { bytes(le(it.size),it) }.toTypedArray())
        val h = byteArrayOf(0x84.toByte(), (comments.size shr 16).toByte(),
            (comments.size shr 8).toByte(), comments.size.toByte())
        val found = EmbeddedTagFieldsReader.read(ByteArrayInputStream(bytes("fLaC".toByteArray(),h,comments)))!!
        assertEquals(listOf(artistId), found.musicBrainzArtistIds)
        assertEquals(listOf(albumId), found.musicBrainzAlbumArtistIds)
        assertEquals(listOf(artistId), found.musicBrainzRecordingIds)
        assertEquals(listOf(albumId), found.musicBrainzReleaseIds)
    }

    private fun freeform(name: String, value: String, namespace: String = "com.apple.iTunes") = atom("----",
        bytes(atom("mean", bytes(byteArrayOf(0,0,0,0),namespace.toByteArray())),
            atom("name", bytes(byteArrayOf(0,0,0,0),name.toByteArray())),
            atom("data", bytes(ByteArray(8),value.toByteArray()))))

    @Test fun mp4FreeformWorksForSeekableAndSequentialReaders() {
        val ftyp = atom("ftyp", bytes("M4A ".toByteArray(), ByteArray(4)))
        val atoms = bytes(ftyp, atom("moov", atom("udta", atom("meta", bytes(
            ByteArray(4), atom("ilst", bytes(
                freeform("MusicBrainz Artist Id",artistId),
                freeform("MusicBrainz Album Artist Id",albumId),
                freeform("MusicBrainz Track Id",artistId),
                freeform("MusicBrainz Album Id",albumId),
                freeform("MusicBrainz Artist Id", "not-a-uuid", "untrusted.namespace"))))))))
        val inline = EmbeddedTagFieldsReader.read(ByteArrayInputStream(atoms))!!
        assertEquals(listOf(artistId), inline.musicBrainzArtistIds)
        assertEquals(listOf(albumId), inline.musicBrainzAlbumArtistIds)
        assertEquals(listOf(artistId), inline.musicBrainzRecordingIds)
        assertEquals(listOf(albumId), inline.musicBrainzReleaseIds)
        val file = File.createTempFile("michi-mbid", ".m4a")
        try {
            file.writeBytes(atoms)
            RandomAccessFile(file, "r").use { raf ->
                val seekable = SeekableEmbeddedTags.read(raf.channel)!!
                assertEquals(listOf(artistId), seekable.musicBrainzArtistIds)
                assertEquals(listOf(albumId), seekable.musicBrainzAlbumArtistIds)
                assertEquals(listOf(artistId), seekable.musicBrainzRecordingIds)
                assertEquals(listOf(albumId), seekable.musicBrainzReleaseIds)
            }
        } finally { file.delete() }
    }
}
