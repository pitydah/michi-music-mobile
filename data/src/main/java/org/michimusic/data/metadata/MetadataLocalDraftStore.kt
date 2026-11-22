package org.michimusic.data.metadata

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One certified field operation in a locally retained proposal. */
@Serializable
data class StoredMetadataOperation(
    val field: String,
    val clear: Boolean = false,
    val values: List<String> = emptyList(),
)

/**
 * A private, restart-safe proposal. It never contains bytes, cursors, streams or
 * Android objects, and it is never authority for a physical write.
 */
@Serializable
data class StoredMetadataProposal(
    val schemaVersion: Int = 1,
    val draftId: String,
    val sourceId: String,
    val trackId: String,
    val trackUri: String,
    val expectedSize: Long,
    val evidenceToken: String,
    val version: Long,
    val operations: List<StoredMetadataOperation> = emptyList(),
    val provisional: Map<String, List<String>> = emptyMap(),
    val savedAtEpochMs: Long,
)

/** Exact private-draft identity check; evidence changes require revalidation. */
fun StoredMetadataProposal.matchesIdentity(
    sourceId: String,
    trackId: String,
    trackUri: String,
    expectedSize: Long,
    evidenceToken: String,
): Boolean = this.sourceId == sourceId &&
    this.trackId == trackId &&
    this.trackUri == trackUri &&
    this.expectedSize == expectedSize &&
    this.evidenceToken == evidenceToken

sealed interface LocalDraftWrite {
    data class Saved(val draftId: String, val version: Long) : LocalDraftWrite
    data class Rejected(val code: String) : LocalDraftWrite
}

interface MetadataLocalDraftStore {
    suspend fun save(expectedVersion: Long?, draft: StoredMetadataProposal): LocalDraftWrite
    suspend fun find(sourceId: String, trackId: String): StoredMetadataProposal?
    suspend fun delete(draftId: String, expectedVersion: Long): Boolean
}

/**
 * App-private JSON store used only by the editor. Writes are serialized per store
 * instance, written to a same-directory temporary file, forced, and atomically
 * moved. A corrupt file is never overwritten silently and never promoted to
 * verified evidence.
 */
class FileMetadataLocalDraftStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : MetadataLocalDraftStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val saveLock = kotlinx.coroutines.sync.Mutex()

    override suspend fun save(
        expectedVersion: Long?,
        draft: StoredMetadataProposal,
    ): LocalDraftWrite = withContext(Dispatchers.IO) {
        saveLock.withLock {
            try {
                // MediaStore-backed local tracks legitimately carry a blank sourceId;
                // trackId + URI + size remain the exact document identity.
                if (draft.trackId.isBlank()) {
                    return@withLock LocalDraftWrite.Rejected("INVALID_IDENTITY")
                }
                if (!draft.trackUri.startsWith("content://") || draft.expectedSize <= 0L) {
                    return@withLock LocalDraftWrite.Rejected("INVALID_SOURCE")
                }
                if (draft.operations.size > MAX_OPERATIONS ||
                    draft.operations.any { it.values.size > MAX_VALUES } ||
                    draft.operations.sumOf { operation ->
                        operation.values.sumOf(String::length)
                    } > MAX_TOTAL_CHARS ||
                    draft.provisional.values.sumOf { values -> values.sumOf(String::length) } >
                    MAX_TOTAL_CHARS) {
                    return@withLock LocalDraftWrite.Rejected("DRAFT_TOO_LARGE")
                }
                ensureRoot()
                val file = fileFor(draft.sourceId, draft.trackId)
                val existing = readFile(file)
                if (file.exists() && existing == null) {
                    return@withLock LocalDraftWrite.Rejected("CORRUPT_DRAFT")
                }
                if (existing != null && expectedVersion != null && existing.version != expectedVersion) {
                    return@withLock LocalDraftWrite.Rejected("VERSION_CONFLICT")
                }
                if (existing == null && expectedVersion != null) {
                    return@withLock LocalDraftWrite.Rejected("VERSION_CONFLICT")
                }
                if (existing != null && expectedVersion == null) {
                    return@withLock LocalDraftWrite.Rejected("VERSION_CONFLICT")
                }
                val stored = draft.copy(
                    draftId = existing?.draftId ?: draft.draftId,
                    version = (existing?.version ?: 0L) + 1L,
                    savedAtEpochMs = clock(),
                )
                val payload = json.encodeToString(StoredMetadataProposal.serializer(), stored)
                if (payload.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
                    return@withLock LocalDraftWrite.Rejected("DRAFT_TOO_LARGE")
                }
                val tmp = File(root, "${file.name}.${System.nanoTime()}.tmp")
                try {
                    FileChannel.open(
                        tmp.toPath(),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    ).use { channel ->
                        channel.write(java.nio.ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8)))
                        channel.force(true)
                    }
                    Files.move(
                        tmp.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    forceDirectory()
                } finally {
                    tmp.delete()
                }
                LocalDraftWrite.Saved(stored.draftId, stored.version)
            } catch (_: Exception) {
                LocalDraftWrite.Rejected("PRIVATE_WRITE_FAILED")
            }
        }
    }

    override suspend fun find(sourceId: String, trackId: String): StoredMetadataProposal? =
        withContext(Dispatchers.IO) {
            if (trackId.isBlank()) return@withContext null
            readFile(fileFor(sourceId, trackId))?.takeIf {
                it.sourceId == sourceId && it.trackId == trackId
            }
        }

    override suspend fun delete(draftId: String, expectedVersion: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val files = root.listFiles { file -> file.name.endsWith(".json") }.orEmpty()
                val match = files.asSequence().mapNotNull { file ->
                    readFile(file)?.takeIf { it.draftId == draftId }?.let { file to it }
                }.firstOrNull() ?: return@withContext false
                if (match.second.version != expectedVersion) return@withContext false
                if (!match.first.delete()) return@withContext false
                forceDirectory()
                true
            } catch (_: Exception) {
                false
            }
        }

    private fun ensureRoot() {
        if (!root.exists() && !root.mkdirs()) error("DRAFT_ROOT_UNAVAILABLE")
        if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory) {
            error("DRAFT_ROOT_UNSAFE")
        }
    }

    private fun identity(sourceId: String, trackId: String): String =
        sha256("$sourceId\u0000$trackId")

    private fun fileFor(sourceId: String, trackId: String): File =
        File(root, "draft-${identity(sourceId, trackId)}.json")

    private fun readFile(file: File): StoredMetadataProposal? = try {
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) null
        else json.decodeFromString<StoredMetadataProposal>(file.readText(Charsets.UTF_8))
            .takeIf { it.schemaVersion == 1 }
    } catch (_: Exception) {
        null
    }

    private fun forceDirectory() {
        try {
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Best effort: the file itself was forced and atomically moved.
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_OPERATIONS = 64
        const val MAX_VALUES = 32
        const val MAX_TOTAL_CHARS = 256 * 1024
        const val MAX_FILE_BYTES = 512 * 1024
    }
}
