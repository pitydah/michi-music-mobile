package org.michimusic.data.metadata

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataRecoveryPhase

class MetadataIndexPreimageStoreTest {
    private val tx = "d999d999-1234-4abc-8abc-999d999d999d"
    private val entry = MetadataRecoveryJournalV1.Entry(
        tx, MetadataRecoveryPhase.STAGE_READY, "lib_device_local", "track-1",
        "content://media/external_primary/audio/media/7", 100L, 5L,
    )
    private val existing = MetadataIndexPreimage.Existing(
        sourceId = entry.sourceId, trackId = entry.trackId, uri = entry.uri,
        size = 100L, verifiedAt = 123L, verifiedSha256 = "a".repeat(64),
        verifiedSize = 100L, verifiedAlgorithm = "sha256",
        title = "Antes", artist = "Artista", album = "Álbum", albumArtist = "",
        genre = "Rock", trackNumber = 1, year = 1990,
        metadataRevision = 12,
    )

    @Test fun roundTripKeepsExactPreimageAndNoRowVariant() {
        val root = Files.createTempDirectory("me14-preimage-").toFile()
        try {
            val store = MetadataIndexPreimageStore(root)
            assertTrue(store.write(entry, existing))
            val read = store.read(entry)
            assertTrue(read is MetadataIndexPreimageStore.Result.Read)
            assertEquals(existing,
                (read as MetadataIndexPreimageStore.Result.Read).preimage)
            assertTrue(store.delete(entry))
            assertTrue(store.write(entry, MetadataIndexPreimage.NoRow))
            assertEquals(MetadataIndexPreimage.NoRow,
                (store.read(entry) as MetadataIndexPreimageStore.Result.Read).preimage)
        } finally { root.deleteRecursively() }
    }

    @Test fun tamperedOrMissingSidecarFailsClosed() {
        val root = Files.createTempDirectory("me14-preimage-").toFile()
        try {
            val store = MetadataIndexPreimageStore(root)
            assertTrue(store.write(entry, existing))
            val file = store.file(entry.id)
            val bytes = file.readBytes()
            bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
            file.writeBytes(bytes)
            assertEquals("PREIMAGE_CORRUPT",
                (store.read(entry) as MetadataIndexPreimageStore.Result.Refused).code)
            assertTrue(file.delete())
            assertEquals("PREIMAGE_MISSING_OR_SUSPICIOUS",
                (store.read(entry) as MetadataIndexPreimageStore.Result.Refused).code)
        } finally { root.deleteRecursively() }
    }

    @Test fun foreignTransactionIdentityCannotReadAnotherSidecar() {
        val root = Files.createTempDirectory("me14-preimage-").toFile()
        try {
            val store = MetadataIndexPreimageStore(root)
            assertTrue(store.write(entry, existing))
            val foreign = entry.copy(
                id = "e999e999-1234-4abc-8abc-999e999e999e",
                trackId = "other",
            )
            val read = store.read(foreign)
            assertTrue(read is MetadataIndexPreimageStore.Result.Refused)
        } finally { root.deleteRecursively() }
    }

    @Test fun legacyV1PreimageRemainsReadableWithConservativeRevision() {
        val root = Files.createTempDirectory("me14-preimage-v1-").toFile()
        try {
            val store = MetadataIndexPreimageStore(root)
            store.file(entry.id).writeBytes(legacyV1Bytes())
            val read = store.read(entry) as MetadataIndexPreimageStore.Result.Read
            assertEquals(existing.copy(metadataRevision = 0), read.preimage)
        } finally { root.deleteRecursively() }
    }

    private fun legacyV1Bytes(): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(0x4D453134)
            out.writeInt(1)
            out.writeUTF(entry.id)
            out.writeUTF(entry.sourceId)
            out.writeUTF(entry.trackId)
            out.writeUTF(entry.uri)
            out.writeByte(1)
            out.writeUTF(existing.sourceId)
            out.writeUTF(existing.trackId)
            out.writeUTF(existing.uri)
            out.writeLong(existing.size)
            out.writeLong(existing.verifiedAt)
            out.writeUTF(existing.verifiedSha256)
            out.writeLong(existing.verifiedSize)
            out.writeUTF(existing.verifiedAlgorithm)
            out.writeUTF(existing.title)
            out.writeUTF(existing.artist)
            out.writeUTF(existing.album)
            out.writeUTF(existing.albumArtist)
            out.writeUTF(existing.genre)
            out.writeInt(existing.trackNumber)
            out.writeInt(existing.year)
        }
        val data = payload.toByteArray()
        return data + MessageDigest.getInstance("SHA-256").digest(data)
    }
}
