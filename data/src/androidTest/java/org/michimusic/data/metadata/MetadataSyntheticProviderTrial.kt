package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.michimusic.core.metadata.MetadataSyntheticProviderEvidence as Evidence

/**
 * ME-08 INSTRUMENTATION-ONLY trial. All write descriptors target the test APK's
 * non-exported DocumentsProvider and random synthetic documents. Never compiled
 * into product data/app. Deliberate error injection is NOT a process-death test.
 */
internal class MetadataSyntheticProviderTrial(private val context: Context) {
    private val resolver = context.contentResolver
    private val backupRoot = File(context.noBackupFilesDir, "me08-private-test-backups")
    private val idPattern = Regex("me08-fixture-[a-f0-9]{32}")
    private val MAX_BYTES = 512 * 1024

    data class Session internal constructor(
        val uri: Uri,
        val id: String,
        val original: Evidence.Fingerprint,
        val candidate: Evidence.Fingerprint,
        val privateBackup: File,
        val candidateBytes: ByteArray,
    )
    data class Result(
        val outcome: Evidence.Outcome,
        val cut: Evidence.Cut,
        val writeClosed: Boolean,
        val readback: Evidence.Fingerprint?,
        val failure: String? = null,
    )
    private class InjectedCut : RuntimeException()

    private fun allowed(uri: Uri): Boolean = uri.scheme == "content" &&
        uri.authority == Evidence.FIXTURE_AUTHORITY &&
        try { idPattern.matches(DocumentsContract.getDocumentId(uri)) }
        catch (_: Exception) { false }

    private fun fingerprint(bytes: ByteArray): Evidence.Fingerprint = Evidence.Fingerprint(
        bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) })

    private fun read(uri: Uri): ByteArray? {
        return try {
        if (!allowed(uri)) null else resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0 || output.size() + n > MAX_BYTES) return null
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        } catch (_: Exception) { null }
    }

    private fun write(uri: Uri, bytes: ByteArray, mode: String = "rwt",
        cut: Evidence.Cut = Evidence.Cut.NONE) {
        val descriptor = resolver.openFileDescriptor(uri, mode)
            ?: error("SYNTHETIC_DESCRIPTOR_UNAVAILABLE")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { stream ->
            if (cut == Evidence.Cut.AFTER_TRUNCATE) throw InjectedCut()
            val first = minOf(bytes.size, 17)
            stream.write(bytes, 0, first)
            if (cut == Evidence.Cut.AFTER_FIRST_BLOCK) throw InjectedCut()
            stream.write(bytes, first, bytes.size - first)
            stream.flush()
            stream.fd.sync()
            if (cut == Evidence.Cut.AFTER_SYNC) throw InjectedCut()
        }
    }

    fun prepare(originalBytes: ByteArray, candidateBytes: ByteArray): Session {
        require(originalBytes.isNotEmpty() && candidateBytes.isNotEmpty() &&
            originalBytes.size <= MAX_BYTES && candidateBytes.size <= MAX_BYTES &&
            !originalBytes.contentEquals(candidateBytes)) { "INVALID_SYNTHETIC_FIXTURE" }
        val id = "me08-fixture-" + UUID.randomUUID().toString().replace("-", "")
        val uri = DocumentsContract.buildDocumentUri(Evidence.FIXTURE_AUTHORITY, id)
        check(allowed(uri))
        write(uri, originalBytes)
        check(read(uri)?.contentEquals(originalBytes) == true) { "SEED_NOT_VERIFIED" }
        check(!Files.isSymbolicLink(backupRoot.toPath())) { "BACKUP_ROOT_SYMLINK" }
        Files.createDirectories(backupRoot.toPath())
        val backup = File(backupRoot, "$id.backup")
        try {
            FileChannel.open(backup.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val bb = ByteBuffer.wrap(originalBytes)
                while (bb.hasRemaining()) channel.write(bb)
                channel.force(true)
            }
            check(backup.isFile && !Files.isSymbolicLink(backup.toPath()) &&
                backup.length() == originalBytes.size.toLong() &&
                fingerprint(Files.readAllBytes(backup.toPath())) == fingerprint(originalBytes)) {
                "PRIVATE_BACKUP_NOT_DURABLE"
            }
        } catch (error: Exception) {
            // Retain fixture on failure; no user audio is referenced.
            throw error
        }
        return Session(uri, id, fingerprint(originalBytes), fingerprint(candidateBytes),
            backup, candidateBytes.copyOf())
    }

    private fun backupBytes(session: Session): ByteArray? = try {
        val file = session.privateBackup
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) ||
            file.length() != session.original.size || file.length() > MAX_BYTES) null
        else Files.readAllBytes(file.toPath()).takeIf { fingerprint(it) == session.original }
    } catch (_: Exception) { null }

    private fun outcome(session: Session, cut: Evidence.Cut, closed: Boolean,
        failure: String? = null): Result {
        val observed = read(session.uri)?.let(::fingerprint)
        val trial = Evidence.Trial(session.uri.authority.orEmpty(), session.id,
            session.original, session.candidate, observed, cut, closed)
        return Result(Evidence.classify(trial), cut, closed, observed, failure)
    }

    /** No destructive operation is possible without a freshly verified private backup. */
    fun execute(session: Session, cut: Evidence.Cut,
        mode: String = "rwt"): Result {
        if (!allowed(session.uri) || mode !in setOf("rwt", "w") ||
            backupBytes(session) == null || fingerprint(session.candidateBytes) != session.candidate ||
            read(session.uri)?.let(::fingerprint) != session.original)
            return Result(Evidence.Outcome.INVALID_FIXTURE, cut, false, null, "PREFLIGHT_DENIED")
        if (cut == Evidence.Cut.BEFORE_OPEN) return outcome(session,cut,false)
        return try {
            write(session.uri, session.candidateBytes, mode, cut)
            outcome(session,cut,true)
        } catch (_: InjectedCut) {
            outcome(session,cut,false, "INJECTED_CUT_NOT_PROCESS_DEATH")
        } catch (_: Exception) {
            outcome(session,cut,false,"PROVIDER_WRITE_OR_CLOSE_FAILED")
        }
    }

    /** Explicit, test-only recovery of this UUID-owned document from a private backup. */
    fun restore(session: Session, explicitConfirmation: Boolean): Evidence.Restore {
        if (!explicitConfirmation || !allowed(session.uri)) return Evidence.Restore.BLOCKED
        val original = backupBytes(session) ?: return Evidence.Restore.BLOCKED
        val before = read(session.uri)?.let(::fingerprint) ?: return Evidence.Restore.BLOCKED
        val report = Evidence.Trial(session.uri.authority.orEmpty(),session.id,
            session.original,session.candidate,before,Evidence.Cut.NONE,true)
        if (Evidence.classify(report) == Evidence.Outcome.INVALID_FIXTURE)
            return Evidence.Restore.BLOCKED
        if (before == session.original) return Evidence.Restore.STILL_ORIGINAL
        try { write(session.uri, original) }
        catch (_: Exception) { return Evidence.Restore.NOT_RESTORED }
        return Evidence.restoration(report,read(session.uri)?.let(::fingerprint),true)
    }

    /** Cleanup only after verifying the fixture is back to the original bytes. */
    fun discardSyntheticSession(session: Session, explicitConfirmation: Boolean): Boolean {
        if (!explicitConfirmation || !allowed(session.uri) ||
            backupBytes(session) == null || read(session.uri)?.let(::fingerprint) != session.original)
            return false
        return try {
            if (!DocumentsContract.deleteDocument(resolver,session.uri)) false
            else session.privateBackup.delete()
        } catch (_: Exception) { false }
    }
}
