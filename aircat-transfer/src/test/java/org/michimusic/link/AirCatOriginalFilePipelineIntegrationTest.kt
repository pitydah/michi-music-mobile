package org.michimusic.link

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A38 in-process integration: real HTTP policy, byte limiter, SHA, on-disk
 * .part promotion and crash journal. Server/TLS/Room are NOT replaced with
 * claims of Android E2E; their external gates remain mandatory.
 */
class AirCatOriginalFilePipelineIntegrationTest {
    private val fileBytes = "01234567".toByteArray(Charsets.US_ASCII)

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun withDir(run: (File) -> Unit) {
        val dir = Files.createTempDirectory("aircat-a38-pipeline-").toFile()
        try { run(dir) } finally { dir.deleteRecursively() }
    }

    private fun policy(offset: Long, code: Int, range: String?, length: Long?, etag: String = "\"snapshot-a\"",
                       expected: String? = null) = AirCatOriginalHttpPolicy.evaluate(
        requestedOffset = offset, statusCode = code, contentRange = range,
        contentLength = length, contentEncoding = "identity", etag = etag,
        expectedEtag = expected,
    )

    @Test fun fullManifestSizedFileCommitsToJournalAndCloses() = withDir { root ->
        val verdict = policy(0L, 200, null, fileBytes.size.toLong())
        assertTrue(verdict.allowed)
        val part = File(root, "track.flac.part")
        val final = File(root, "track.flac")
        FileOutputStream(part).use { raw ->
            AirCatBoundedOutputStream(raw, fileBytes.size.toLong()).use { guarded ->
                guarded.write(fileBytes, 0, 3)
                guarded.write(fileBytes, 3, 5)
            }
        }
        val digest = sha(fileBytes)
        assertEquals(digest, sha(part.readBytes()))
        val journal = AirCatRecoveryJournal(File(root, ".aircat-journal"))
        assertTrue(journal.promote(part, final, "a38-transfer", digest))
        assertTrue(journal.hasJournal(final))
        assertTrue(journal.finishCommitted(final, "a38-transfer", digest, true, true))
        assertFalse(journal.hasJournal(final))
        assertArrayEquals(fileBytes, final.readBytes())
    }

    @Test fun oversizeBodyCannotWriteUnboundedPartOrCreateJournal() = withDir { root ->
        assertTrue(policy(0, 200, null, null).allowed)
        val part = File(root, "track.flac.part")
        val final = File(root, "track.flac")
        var refused = false
        FileOutputStream(part).use { raw ->
            AirCatBoundedOutputStream(raw, 8).use { guarded ->
                guarded.write(fileBytes, 0, 3)
                try { guarded.write(ByteArray(32) { 42 }) } catch (_: IOException) { refused = true }
            }
        }
        assertTrue(refused)
        assertEquals(3L, part.length())
        assertFalse(final.exists())
        assertFalse(AirCatRecoveryJournal(File(root, ".aircat-journal")).hasJournal(final))
    }

    @Test fun resumedPartialRangeCommitsExactEightOriginalBytes() = withDir { root ->
        val part = File(root, "track.flac.part")
        val final = File(root, "track.flac")
        part.writeBytes(fileBytes.copyOfRange(0, 3))
        val verdict = policy(3, 206, "bytes 3-7/8", 5, expected = "\"snapshot-a\"")
        assertTrue(verdict.allowed)
        FileOutputStream(part, true).use { raw ->
            AirCatBoundedOutputStream(raw, 8L - 3L).use { guarded ->
                guarded.write(fileBytes, 3, 5)
            }
        }
        assertArrayEquals(fileBytes, part.readBytes())
        val digest = sha(fileBytes)
        val journal = AirCatRecoveryJournal(File(root, ".aircat-journal"))
        assertTrue(journal.promote(part, final, "a38-resume", digest))
        assertTrue(journal.finishCommitted(final, "a38-resume", digest, true, true))
        assertArrayEquals(fileBytes, final.readBytes())
    }

    @Test fun changedEtagRefusedWithoutWritingPartial() = withDir { root ->
        val part = File(root, "track.flac.part")
        part.writeBytes(fileBytes.copyOfRange(0, 3))
        val before = part.readBytes()
        val verdict = policy(3, 206, "bytes 3-7/8", 5, etag = "\"changed\"", expected = "\"snapshot-a\"")
        assertFalse(verdict.allowed)
        assertArrayEquals(before, part.readBytes())
    }

    @Test fun ignoredRangeRestartsWithoutAppendingFullBody() = withDir { root ->
        val part = File(root, "track.flac.part")
        part.writeBytes(fileBytes.copyOfRange(0, 3))
        val verdict = policy(3, 200, null, 8, expected = "\"snapshot-a\"")
        assertEquals(AirCatOriginalHttpPolicy.Action.RESTART_FROM_ZERO, verdict.action)
        FileOutputStream(part, false).use { raw ->
            AirCatBoundedOutputStream(raw, fileBytes.size.toLong()).use { it.write(fileBytes) }
        }
        assertArrayEquals(fileBytes, part.readBytes())
        assertEquals(8L, part.length())
    }

    @Test fun wrongShaLeavesPriorFileAndJournalForExplicitRepair() = withDir { root ->
        val old = "previous-valid-asset".toByteArray()
        val final = File(root, "track.flac").also { it.writeBytes(old) }
        val part = File(root, "track.flac.part").also { it.writeBytes(fileBytes) }
        val journal = AirCatRecoveryJournal(File(root, ".aircat-journal"))
        val wrongExpected = sha("different-content".toByteArray())
        assertTrue(journal.promote(part, final, "a38-wrong-sha", wrongExpected))
        assertFalse(journal.finishCommitted(final, "a38-wrong-sha", wrongExpected, true, true))
        assertTrue(journal.hasJournal(final))
        assertArrayEquals(old, File(root, "track.flac.bak").readBytes())
    }
}
