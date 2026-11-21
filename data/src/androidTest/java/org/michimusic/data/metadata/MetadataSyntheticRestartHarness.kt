package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.michimusic.core.metadata.MetadataSyntheticProviderEvidence as Evidence

/**
 * ME-09, androidTest ONLY. Reconstructs a test provider session from PRIVATE,
 * checksummed checkpoint bytes in a second instrumentation invocation. A real
 * force-stop is orchestrated externally; normal tests never kill the process.
 * Does not inspect user-library IDs or grant production write capability.
 */
internal class MetadataSyntheticRestartHarness(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "me09-synthetic-restart-only")
    private val checkpoint = File(root,"checkpoint-v1.bin")
    private val doc = Regex("me08-fixture-[0-9a-f]{32}")
    private val hex = Regex("[a-f0-9]{64}")
    private val originalBytes = ByteArray(2048) { ((it * 11) % 251).toByte() }
    private val candidateBytes = ByteArray(337) { ((it * 13 + 9) % 251).toByte() }
    private val lab = MetadataSyntheticProviderTrial(context)

    data class Record(val id: String, val originalSha: String, val candidateSha: String)
    sealed interface Recovery {
        data object Missing : Recovery
        data object CorruptKeepEvidence : Recovery
        data object RestoredAndDiscarded : Recovery
        data object RestoreFailedKeepEvidence : Recovery
    }
    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun rootSafe(): Boolean {
        if (Files.isSymbolicLink(root.toPath())) return false
        Files.createDirectories(root.toPath())
        return Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(checkpoint.toPath())
    }

    private fun encode(r: Record): ByteArray {
        require(doc.matches(r.id) && hex.matches(r.originalSha) && hex.matches(r.candidateSha))
        val inner = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { o ->
                o.writeInt(0x4d453039);o.writeInt(1)
                o.writeUTF(r.id);o.writeUTF(r.originalSha);o.writeUTF(r.candidateSha)
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { o ->
                o.writeInt(inner.size);o.write(inner)
                o.write(MessageDigest.getInstance("SHA-256").digest(inner))
            }
        }.toByteArray()
    }

    private fun read(): Record? {
        if (!rootSafe() || !checkpoint.isFile || checkpoint.length() !in 37..600) return null
        return try {
            val bytes = Files.readAllBytes(checkpoint.toPath())
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val n=input.readInt()
                if (n !in 1..564 || bytes.size != n + 36) return null
                val body=ByteArray(n); input.readFully(body)
                val digest=ByteArray(32);input.readFully(digest)
                if (!MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(body))) return null
                DataInputStream(ByteArrayInputStream(body)).use { payload ->
                    if (payload.readInt()!=0x4d453039 || payload.readInt()!=1) return null
                    val r=Record(payload.readUTF(),payload.readUTF(),payload.readUTF())
                    r.takeIf { payload.available()==0 && doc.matches(it.id) &&
                        it.originalSha==sha(originalBytes) && it.candidateSha==sha(candidateBytes) }
                }
            }
        } catch (_: Exception) { null }
    }

    private fun persist(r: Record) {
        check(rootSafe() && !checkpoint.exists()) { "TEST_CHECKPOINT_ALREADY_PRESENT_OR_UNTRUSTED" }
        val bytes=encode(r)
        val tmp=File(root,"checkpoint-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(tmp.toPath(),StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { ch ->
                val b=ByteBuffer.wrap(bytes)
                while(b.hasRemaining())ch.write(b)
                ch.force(true)
            }
            Files.move(tmp.toPath(),checkpoint.toPath(),StandardCopyOption.ATOMIC_MOVE)
            try { FileChannel.open(root.toPath(),StandardOpenOption.READ).use { it.force(true) } }
            catch (_: Exception) { /* no assertion of durable filesystem commit */ }
        } finally { tmp.delete() }
    }

    /** Creates and damages ONLY a fresh synthetic document; leaves a durable checkpoint. */
    fun seedInterrupted(): Evidence.Outcome {
        check(rootSafe() && !checkpoint.exists()) { "PREVIOUS_TEST_RECOVERY_PENDING" }
        val session=lab.prepare(originalBytes,candidateBytes)
        persist(Record(session.id,session.original.sha256,session.candidate.sha256))
        val result=lab.execute(session,Evidence.Cut.AFTER_FIRST_BLOCK)
        check(result.outcome==Evidence.Outcome.PARTIAL_OR_FOREIGN_BYTES) {
            "UNEXPECTED_TEST_PROVIDER_BEHAVIOR_KEEP_CHECKPOINT"
        }
        return result.outcome
    }

    /** Restores only the exact random synthetic UUID; keep all evidence on uncertainty. */
    fun resumeAfterRestart(explicitConfirmation: Boolean): Recovery {
        if (!rootSafe()) return Recovery.CorruptKeepEvidence
        if (!checkpoint.exists()) return Recovery.Missing
        val record=read() ?: return Recovery.CorruptKeepEvidence
        if (!explicitConfirmation) return Recovery.RestoreFailedKeepEvidence
        val uri=DocumentsContract.buildDocumentUri(Evidence.FIXTURE_AUTHORITY,record.id)
        val backup=File(File(context.noBackupFilesDir,"me08-private-test-backups"),"${record.id}.backup")
        val s=MetadataSyntheticProviderTrial.Session(uri,record.id,
            Evidence.Fingerprint(originalBytes.size.toLong(),record.originalSha),
            Evidence.Fingerprint(candidateBytes.size.toLong(),record.candidateSha),backup,candidateBytes.copyOf())
        val restored=lab.restore(s,true)
        if (restored !in setOf(Evidence.Restore.STILL_ORIGINAL,
                Evidence.Restore.SYNTHETIC_ORIGINAL_RESTORED)) return Recovery.RestoreFailedKeepEvidence
        if (!lab.discardSyntheticSession(s,true)) return Recovery.RestoreFailedKeepEvidence
        return if (checkpoint.delete()) Recovery.RestoredAndDiscarded
        else Recovery.RestoreFailedKeepEvidence
    }
}
