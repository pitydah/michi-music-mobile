package org.michimusic.data.metadata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.michimusic.data.cache.CachedTrack

/**
 * F3: durable Room preimage captured BEFORE the destructive provider boundary.
 * NO_ROW is an explicit variant; a cancelled/failed query is never promoted to
 * NO_ROW. The sidecar is private, bounded, versioned and checksummed.
 */
internal sealed interface MetadataIndexPreimage {
    data object NoRow : MetadataIndexPreimage
    data class Existing(
        val sourceId: String, val trackId: String, val uri: String,
        val size: Long, val verifiedAt: Long, val verifiedSha256: String,
        val verifiedSize: Long, val verifiedAlgorithm: String,
        val title: String, val artist: String, val album: String,
        val albumArtist: String, val genre: String,
        val trackNumber: Int, val year: Int,
        val metadataRevision: Long = 0,
    ) : MetadataIndexPreimage
}

internal fun CachedTrack.asMetadataPreimage() = MetadataIndexPreimage.Existing(
    sourceId = sourceId, trackId = id, uri = filepath, size = size,
    verifiedAt = verifiedAt, verifiedSha256 = verifiedSha256,
    verifiedSize = verifiedSize, verifiedAlgorithm = verifiedAlgorithm,
    title = title, artist = artist, album = album, albumArtist = albumArtist,
    genre = genre, trackNumber = trackNumber, year = year,
    metadataRevision = metadataRevision,
)

internal class MetadataIndexPreimageStore(private val root: File) {
    sealed interface Result {
        data class Read(val preimage: MetadataIndexPreimage) : Result
        data class Refused(val code: String) : Result
    }

    fun file(transactionId: String): File = File(root, "preimage-${validated(transactionId)}.bin")

    fun write(
        entry: MetadataRecoveryJournalV1.Entry,
        preimage: MetadataIndexPreimage,
    ): Boolean = try {
        ensureRoot()
        val target = file(entry.id)
        if (Files.isSymbolicLink(target.toPath())) false else {
            val tmp = File(root, "preimage-${UUID.randomUUID()}.tmp")
            val bytes = encode(entry, preimage)
            try {
                FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                try {
                    FileChannel.open(root.toPath(), StandardOpenOption.READ)
                        .use { it.force(true) }
                } catch (_: Exception) { /* reported limitation, not authorization */ }
                decode(entry, Files.readAllBytes(target.toPath())) != null
            } finally { tmp.delete() }
        }
    } catch (_: Exception) { false }

    fun read(entry: MetadataRecoveryJournalV1.Entry): Result {
        val target = file(entry.id)
        return try {
            if (Files.isSymbolicLink(target.toPath()) ||
                !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                target.length() !in 1..MAX_FILE)
                Result.Refused("PREIMAGE_MISSING_OR_SUSPICIOUS")
            else decode(entry, Files.readAllBytes(target.toPath()))
                ?.let { Result.Read(it) }
                ?: Result.Refused("PREIMAGE_CORRUPT")
        } catch (_: Exception) { Result.Refused("PREIMAGE_UNREADABLE") }
    }

    fun delete(entry: MetadataRecoveryJournalV1.Entry): Boolean = try {
        val target = file(entry.id)
        if (Files.isSymbolicLink(target.toPath())) false
        else !target.exists() || target.delete()
    } catch (_: Exception) { false }

    private fun ensureRoot() {
        if (!root.exists()) root.mkdirs()
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
    }

    private fun encode(
        entry: MetadataRecoveryJournalV1.Entry,
        preimage: MetadataIndexPreimage,
    ): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeUTF(entry.id); out.writeUTF(entry.sourceId)
            out.writeUTF(entry.trackId); out.writeUTF(entry.uri)
            when (preimage) {
                MetadataIndexPreimage.NoRow -> out.writeByte(0)
                is MetadataIndexPreimage.Existing -> {
                    out.writeByte(1)
                    out.writeUTF(preimage.sourceId); out.writeUTF(preimage.trackId)
                    out.writeUTF(preimage.uri); out.writeLong(preimage.size)
                    out.writeLong(preimage.verifiedAt)
                    out.writeUTF(preimage.verifiedSha256)
                    out.writeLong(preimage.verifiedSize)
                    out.writeUTF(preimage.verifiedAlgorithm)
                    out.writeUTF(preimage.title); out.writeUTF(preimage.artist)
                    out.writeUTF(preimage.album); out.writeUTF(preimage.albumArtist)
                    out.writeUTF(preimage.genre); out.writeInt(preimage.trackNumber)
                    out.writeInt(preimage.year)
                    out.writeLong(preimage.metadataRevision)
                }
            }
        }
        val data = payload.toByteArray()
        require(data.size in 1..MAX_FILE - 32)
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return data + digest
    }

    private fun decode(
        entry: MetadataRecoveryJournalV1.Entry,
        bytes: ByteArray,
    ): MetadataIndexPreimage? = try {
        if (bytes.size < 36) null else {
            val data = bytes.copyOfRange(0, bytes.size - 32)
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            if (!digest.contentEquals(bytes.copyOfRange(bytes.size - 32, bytes.size))) null
            else DataInputStream(ByteArrayInputStream(data)).use { input ->
                if (input.readInt() != MAGIC) return null
                val version = input.readInt()
                if (version !in 1..VERSION) return null
                if (input.readUTF() != entry.id || input.readUTF() != entry.sourceId ||
                    input.readUTF() != entry.trackId || input.readUTF() != entry.uri) return null
                when (input.readByte().toInt()) {
                    0 -> MetadataIndexPreimage.NoRow
                    1 -> MetadataIndexPreimage.Existing(
                        sourceId = input.readUTF(), trackId = input.readUTF(),
                        uri = input.readUTF(), size = input.readLong(),
                        verifiedAt = input.readLong(), verifiedSha256 = input.readUTF(),
                        verifiedSize = input.readLong(), verifiedAlgorithm = input.readUTF(),
                        title = input.readUTF(), artist = input.readUTF(),
                        album = input.readUTF(), albumArtist = input.readUTF(),
                        genre = input.readUTF(), trackNumber = input.readInt(),
                        year = input.readInt(),
                        metadataRevision = if (version >= 2) input.readLong() else 0L,
                    )
                    else -> null
                }
            }
        }
    } catch (_: Exception) { null }

    private fun validated(id: String): String {
        require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        return id
    }

    private companion object {
        const val MAGIC = 0x4D453134
        const val VERSION = 2
        const val MAX_FILE = 64L * 1024L
    }
}
