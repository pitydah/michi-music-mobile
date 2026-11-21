package org.michimusic.data.metadata

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataRecoveryPhase

/**
 * ME-11R P0: the exclusion contract of [MetadataRecoveryJournalV1] must survive
 * concurrency between coroutines, concurrency between instances, exclusion between
 * processes, cancellation, exceptions, nesting, interruption, concurrent generation
 * changes, invalidated evidence and a real process crash.
 *
 * Every case uses a synthetic private directory. Nothing here touches MediaStore, a
 * provider document or an audio byte.
 */
class MetadataRecoveryJournalConcurrencyTest {
    private fun fresh(): File = Files.createTempDirectory("michi-me11r-journal-").toFile()

    private val id = "a999a999-1234-4abc-8abc-999a999a9999"
    private val otherId = "b888b888-1234-4abc-8abc-888b888b8888"

    private fun entry(id: String = this.id, bytes: Long = 4096L) =
        MetadataRecoveryJournalV1.Entry(
            id,
            MetadataRecoveryPhase.SNAPSHOTTING,
            "",
            "track-1",
            "content://media/external/audio/media/7",
            bytes,
            98765L,
        )

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }

    private fun backedEntry(from: MetadataRecoveryJournalV1.Entry, digest: Char) =
        from.copy(phase = MetadataRecoveryPhase.BACKUP_DURABLE, originalSha256 = digest.toString().repeat(64))

    // ---------------------------------------------------------------- child JVMs

    private fun spawn(mainClass: String, argument: String): Process? {
        val java = File(System.getProperty("java.home"), "bin/java")
        if (!java.canExecute()) return null
        val classpath = System.getProperty("java.class.path")
        if (classpath.isNullOrBlank()) return null
        return try {
            ProcessBuilder(java.absolutePath, "-cp", classpath, mainClass, argument)
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            null
        }
    }

    private fun readLineWithTimeout(process: Process, millis: Long): String? {
        val holder = arrayOfNulls<String>(1)
        val reader = Thread {
            holder[0] = try {
                BufferedReader(InputStreamReader(process.inputStream)).readLine()
            } catch (_: Exception) {
                null
            }
        }
        reader.isDaemon = true
        reader.start()
        reader.join(millis)
        return holder[0]
    }

    private fun release(process: Process) {
        try {
            process.outputStream.write('\n'.code)
            process.outputStream.flush()
        } catch (_: Exception) {
            // The child may have exited already; the caller asserts the outcome.
        }
    }

    // ------------------------------------------------- coroutine + instance cases

    /**
     * Regression for the P0 defect: two instances over one directory used to keep two
     * independent mutexes, so the second `FileChannel.lock()` threw
     * `OverlappingFileLockException` inside the same JVM. Now a single process-wide
     * lock keyed by canonical path serialises them.
     */
    @Test
    fun twoInstancesOverOneDirectorySerialiseInProcess() = runBlocking {
        val dir = fresh()
        try {
            val first = MetadataRecoveryJournalV1(dir)
            val second = MetadataRecoveryJournalV1(dir)
            first.exclusive { first.start(entry()) }
            val inside = AtomicInteger()
            val peak = AtomicInteger()
            coroutineScope {
                (0 until 32).map { index ->
                    async(Dispatchers.IO) {
                        val journal = if (index % 2 == 0) first else second
                        repeat(5) {
                            journal.exclusive {
                                val now = inside.incrementAndGet()
                                peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                                try {
                                    Thread.sleep(1)
                                } finally {
                                    inside.decrementAndGet()
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            assertEquals("two instances must never be inside the journal at once", 1, peak.get())
            assertEquals(
                MetadataRecoveryJournalV1.Inspection.Pending(entry()),
                second.inspect(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Only one publisher may create the record; every other observation is stale. */
    @Test
    fun concurrentStartAcrossInstancesPublishesExactlyOneRecord() = runBlocking {
        val dir = fresh()
        try {
            val first = MetadataRecoveryJournalV1(dir)
            val second = MetadataRecoveryJournalV1(dir)
            val clear = first.withExclusiveSnapshot { it }.generation
            assertEquals(MetadataRecoveryJournalV1.Generation.Clear, clear)
            val published = AtomicInteger()
            val stale = AtomicInteger()
            coroutineScope {
                (0 until 16).map { index ->
                    async(Dispatchers.IO) {
                        val instance = if (index % 2 == 0) first else second
                        val outcome = instance.publishIfUnchanged(clear) {
                            instance.start(entry())
                            "created"
                        }
                        when (outcome) {
                            is MetadataRecoveryJournalV1.Publication.Published -> published.incrementAndGet()
                            is MetadataRecoveryJournalV1.Publication.Stale -> stale.incrementAndGet()
                        }
                    }
                }.awaitAll()
            }
            assertEquals(1, published.get())
            assertEquals(15, stale.get())
            assertTrue(first.inspect() is MetadataRecoveryJournalV1.Inspection.Pending)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A generation is content addressed: restart, tamper and equality are observable. */
    @Test
    fun generationIsContentAddressedAndSurvivesRestart() = runBlocking {
        val dir = fresh()
        try {
            val journal = MetadataRecoveryJournalV1(dir)
            journal.exclusive { journal.start(entry()) }
            val reopened = MetadataRecoveryJournalV1(dir)
            val snapshot = reopened.withExclusiveSnapshot { it }
            assertEquals(
                MetadataRecoveryJournalV1.Inspection.Pending(entry()),
                snapshot.inspection,
            )
            assertEquals(sha(File(dir, "journal-v1.bin").readBytes()), snapshot.generation.digest)
            assertEquals(
                "the same durable record must produce the same generation in both instances",
                snapshot.generation,
                journal.withExclusiveSnapshot { it }.generation,
            )
            val record = File(dir, "journal-v1.bin")
            record.writeBytes(record.readBytes().also { it[6] = (it[6].toInt() xor 3).toByte() })
            val tampered = MetadataRecoveryJournalV1(dir).withExclusiveSnapshot { it }
            assertTrue(tampered.inspection is MetadataRecoveryJournalV1.Inspection.Blocked)
            assertFalse(
                "a tampered record must not keep the previous identity",
                tampered.generation == snapshot.generation,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // -------------------------------------------------------- exclusion cases

    /**
     * Cross-process exclusion. A child JVM holds the OS lock on `journal-v1.lock`; the
     * journal must stay outside until that lock is released. `Mutex` alone cannot
     * provide this, which is why the file lock was kept.
     */
    @Test
    fun anotherProcessHoldingTheLockKeepsTheJournalOutside() = runBlocking {
        val dir = fresh()
        val process = spawn(JournalLockHolder::class.java.name, dir.absolutePath)
        assumeTrue("a child JVM is required for cross-process evidence", process != null)
        try {
            val locked = readLineWithTimeout(process!!, 30_000)
            assumeTrue("the child JVM could not take the OS lock", locked == "LOCKED")
            val journal = MetadataRecoveryJournalV1(dir)
            val attempt = runCatching { withTimeoutOrNull(750) { journal.exclusive { "ACQUIRED" } } }
            assertTrue(
                "the journal must not enter while another process holds journal-v1.lock",
                attempt.isFailure || attempt.getOrNull() == null,
            )
            release(process)
            assertTrue("the child must exit after releasing the lock", process.waitFor(30, TimeUnit.SECONDS))
            assertEquals("ACQUIRED", withTimeout(20_000) { journal.exclusive { "ACQUIRED" } })
            assertEquals(MetadataRecoveryJournalV1.Inspection.Clear, journal.inspect())
        } finally {
            process?.destroy()
            process?.waitFor(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }

    /**
     * Cancelling a caller parked on the OS lock must not leak the process mutex: a
     * later acquisition on the same instance still has to succeed.
     */
    @Test
    fun cancellingWhileParkedOnTheLockDoesNotLeakTheMutex() = runBlocking {
        val dir = fresh()
        val process = spawn(JournalLockHolder::class.java.name, dir.absolutePath)
        assumeTrue("a child JVM is required for cross-process evidence", process != null)
        try {
            assumeTrue("the child JVM could not take the OS lock", readLineWithTimeout(process!!, 30_000) == "LOCKED")
            val journal = MetadataRecoveryJournalV1(dir)
            val attempt = runCatching { withTimeoutOrNull(600) { journal.exclusive { "ACQUIRED" } } }
            assertTrue(attempt.isFailure || attempt.getOrNull() == null)
            release(process)
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(
                "a cancelled acquisition must release the process mutex",
                "ACQUIRED",
                withTimeout(20_000) { journal.exclusive { "ACQUIRED" } },
            )
        } finally {
            process?.destroy()
            process?.waitFor(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }

    // --------------------------------------------------- cancellation cases

    /**
     * Cancellation between the snapshot and the publication stands in for a provider
     * read that never returns. The durable record must be exactly as before.
     */
    @Test
    fun cancellingBetweenSnapshotAndPublicationNeverPublishes() = runBlocking {
        val dir = fresh()
        try {
            val journal = MetadataRecoveryJournalV1(dir)
            val started = entry()
            journal.exclusive { journal.start(started) }
            val before = journal.withExclusiveSnapshot { it }.generation
            val published = AtomicBoolean(false)
            val job = launch(Dispatchers.Default) {
                val snapshot = journal.withExclusiveSnapshot { it }
                delay(30_000)
                journal.publishIfUnchanged(snapshot.generation, started) {
                    published.set(true)
                    "published"
                }
            }
            delay(200)
            job.cancelAndJoin()
            assertFalse("cancelled work must not publish", published.get())
            assertEquals(before, journal.withExclusiveSnapshot { it }.generation)
            assertEquals(MetadataRecoveryJournalV1.Inspection.Pending(started), journal.inspect())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A publication that already returned must survive a later cancellation. */
    @Test
    fun aCompletedPublicationSurvivesLaterCancellation() = runBlocking {
        val dir = fresh()
        try {
            val journal = MetadataRecoveryJournalV1(dir)
            val started = entry()
            journal.exclusive { journal.start(started) }
            val snapshot = journal.withExclusiveSnapshot { it }
            val backed = backedEntry(started, 'c')
            val done = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                val outcome = journal.publishIfUnchanged(snapshot.generation, started) {
                    journal.advance(started, backed)
                    "advanced"
                }
                assertTrue(outcome is MetadataRecoveryJournalV1.Publication.Published)
                done.complete(Unit)
                delay(30_000)
            }
            withTimeout(10_000) { done.await() }
            job.cancelAndJoin()
            val reopened = MetadataRecoveryJournalV1(dir)
            reopened.exclusive {
                assertEquals(MetadataRecoveryJournalV1.Inspection.Pending(backed), reopened.inspect())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------- exceptions, nesting, resources

    @Test
    fun anExceptionInsideTheCriticalSectionReleasesTheLockAndLeavesNoTempFile() = runBlocking {
        val dir = fresh()
        try {
            val journal = MetadataRecoveryJournalV1(dir)
            var thrown = false
            try {
                journal.exclusive { throw IllegalStateException("BOOM") }
            } catch (_: IllegalStateException) {
                thrown = true
            }
            assertTrue(thrown)
            assertEquals("ok", withTimeout(5_000) { journal.exclusive { "ok" } })
            assertEquals(MetadataRecoveryJournalV1.Inspection.Clear, journal.inspect())
            assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A failing publish block must not report success. When it throws before persisting,
     * the record and its generation are untouched; when it throws after persisting, the
     * record is the complete new one, never a partial envelope.
     */
    @Test
    fun aFailingPublishBlockNeverLeavesAPartialRecord() = runBlocking {
        val dir = fresh()
        try {
            val journal = MetadataRecoveryJournalV1(dir)
            val started = entry()
            journal.exclusive { journal.start(started) }
            val snapshot = journal.withExclusiveSnapshot { it }
            var failed = false
            try {
                journal.publishIfUnchanged(snapshot.generation, started) { error("BOOM_BEFORE_PERSIST") }
            } catch (_: IllegalStateException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(snapshot.generation, journal.withExclusiveSnapshot { it }.generation)
            assertEquals(MetadataRecoveryJournalV1.Inspection.Pending(started), journal.inspect())

            val backed = backedEntry(started, 'd')
            failed = false
            try {
                journal.publishIfUnchanged(snapshot.generation, started) {
                    journal.advance(started, backed)
                    error("BOOM_AFTER_PERSIST")
                }
            } catch (_: IllegalStateException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(
                "a persisted transition stays durable even when its block then throws",
                MetadataRecoveryJournalV1.Inspection.Pending(backed),
                MetadataRecoveryJournalV1(dir).inspect(),
            )
            assertEquals("ok", withTimeout(5_000) { journal.exclusive { "ok" } })
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A nested acquisition can only be written deliberately (the block is not
     * suspending). It must be rejected instead of deadlocking on the non-reentrant mutex.
     */
    @Test
    fun nestedAcquisitionIsRejectedWithoutDeadlock() = runBlocking {
        val dir = fresh()
        try {
            val journal = MetadataRecoveryJournalV1(dir)
            var message: String? = null
            journal.exclusive {
                try {
                    runBlocking { journal.exclusive { } }
                } catch (error: IllegalStateException) {
                    message = error.message
                }
            }
            assertEquals("RECOVERY_JOURNAL_REENTRANT_LOCK", message)
            assertEquals("ok", withTimeout(5_000) { journal.exclusive { "ok" } })
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------ stale observations and evidence

    @Test
    fun aStaleObservationOrMismatchedEntryNeverReportsSuccess() = runBlocking {
        val dir = fresh()
        try {
            val first = MetadataRecoveryJournalV1(dir)
            val second = MetadataRecoveryJournalV1(dir)
            val started = entry()
            first.exclusive { first.start(started) }
            val observation = second.withExclusiveSnapshot { it }
            val backed = backedEntry(started, 'e')
            assertTrue(
                second.publishIfUnchanged(observation.generation, started) {
                    second.advance(started, backed)
                    "advanced"
                } is MetadataRecoveryJournalV1.Publication.Published,
            )
            var ran = false
            val stale = first.publishIfUnchanged(observation.generation, started) {
                ran = true
                "published"
            }
            assertTrue(stale is MetadataRecoveryJournalV1.Publication.Stale)
            assertEquals(observation.generation, (stale as MetadataRecoveryJournalV1.Publication.Stale).expected)
            assertFalse("a stale observation must not execute the publish block", ran)
            assertFalse(first.isUnchanged(observation.generation, started))

            val current = first.withExclusiveSnapshot { it }.generation
            val mismatched = first.publishIfUnchanged(current, started) {
                ran = true
                "published"
            }
            assertTrue(
                "a matching generation with a different entry must be refused",
                mismatched is MetadataRecoveryJournalV1.Publication.Stale,
            )
            assertFalse(ran)
            assertEquals(MetadataRecoveryJournalV1.Inspection.Pending(backed), first.inspect())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Private evidence invalidated during the unlocked window must be detectable inside
     * the publish block, so no caller can turn it into a success.
     */
    @Test
    fun invalidatedPrivateEvidenceCannotBePublishedAsSuccess() = runBlocking {
        val dir = fresh()
        try {
            val journal = MetadataRecoveryJournalV1(dir)
            val original = ByteArray(64) { (it * 7).toByte() }
            val staged = ByteArray(96) { (it * 5).toByte() }
            val started = entry(bytes = original.size.toLong())
            val backed = started.copy(
                phase = MetadataRecoveryPhase.BACKUP_DURABLE,
                originalSha256 = sha(original),
            )
            val ready = backed.copy(
                phase = MetadataRecoveryPhase.STAGE_READY,
                stageSha256 = sha(staged),
                stageBytes = staged.size.toLong(),
                format = "FLAC",
            )
            journal.exclusive {
                journal.start(started)
                journal.backup(started).writeBytes(original)
                journal.advance(started, backed)
                journal.stage(backed).writeBytes(staged)
                journal.advance(backed, ready)
                assertTrue(journal.privateArtifactsIntact(ready))
            }
            val observation = journal.withExclusiveSnapshot { it }
            assertTrue(journal.stage(ready).delete())
            var intact = true
            val publication = journal.publishIfUnchanged(observation.generation, ready) {
                intact = journal.privateArtifactsIntact(ready)
                if (intact) "READY" else "EVIDENCE_INVALID"
            }
            assertFalse("a tampered stage must be detected inside the publish block", intact)
            assertEquals(
                "EVIDENCE_INVALID",
                (publication as MetadataRecoveryJournalV1.Publication.Published).value,
            )
            assertEquals(MetadataRecoveryJournalV1.Inspection.Pending(ready), journal.inspect())
        } finally {
            dir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------- restart

    /** Publication followed by an immediate JVM halt must still be recoverable. */
    @Test
    fun aCrashImmediatelyAfterPublicationIsRecoveredFromDisk() {
        val dir = fresh()
        val process = spawn(JournalCrashWriter::class.java.name, dir.absolutePath)
        assumeTrue("a child JVM is required for the restart evidence", process != null)
        try {
            assertTrue("the crash writer must finish", process!!.waitFor(60, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            runBlocking {
                val reopened = MetadataRecoveryJournalV1(dir)
                val snapshot = reopened.withExclusiveSnapshot { it }
                val pending = snapshot.inspection as? MetadataRecoveryJournalV1.Inspection.Pending
                assertNotNull("the durable record must decode after a hard halt", pending)
                assertEquals(JournalCrashWriter.ID, pending!!.entry.id)
                assertEquals(JournalCrashWriter.TRACK_ID, pending.entry.trackId)
                assertEquals(JournalCrashWriter.BYTES, pending.entry.expectedBytes)
                assertEquals(
                    sha(File(dir, "journal-v1.bin").readBytes()),
                    snapshot.generation.digest,
                )
            }
        } finally {
            process?.destroy()
            process?.waitFor(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }

    /**
     * Generation identity is content-addressed. Discarding a PRE-reservation record and
     * recreating the exact same envelope reproduces the same digest; that ABA is
     * harmless because only SNAPSHOTTING/BACKUP_DURABLE/STAGE_READY are discardable and
     * a record that may have touched the source can never be discarded. The second half
     * pins the refusal: an old observation can never publish a DIFFERENT record.
     */
    @Test
    fun discardRecreateIsContentAddressedAndCannotPublishADifferentRecord() = runBlocking {
        val dir = fresh()
        val journal = MetadataRecoveryJournalV1(dir)
        try {
            val first = entry()
            val initial = journal.publishIfUnchanged(MetadataRecoveryJournalV1.Generation.Clear) {
                journal.start(first); true
            }
            check(initial is MetadataRecoveryJournalV1.Publication.Published)
            val firstGeneration = initial.generation
            assertTrue(journal.exclusive { journal.discardPrepared(first.id) })
            assertEquals(MetadataRecoveryJournalV1.Inspection.Clear, journal.inspect())

            // Recreating the same pre-reservation envelope is content-identical.
            val recreated = journal.publishIfUnchanged(MetadataRecoveryJournalV1.Generation.Clear) {
                journal.start(first); true
            }
            check(recreated is MetadataRecoveryJournalV1.Publication.Published)
            assertEquals(firstGeneration, recreated.generation)

            // Discard again, then create a DIFFERENT record with a fresh identity.
            assertTrue(journal.exclusive { journal.discardPrepared(first.id) })
            val different = journal.publishIfUnchanged(MetadataRecoveryJournalV1.Generation.Clear) {
                journal.start(entry(otherId)); true
            }
            assertTrue(different is MetadataRecoveryJournalV1.Publication.Published)

            var blockRan = false
            val stale = journal.publishIfUnchanged(firstGeneration, first) {
                blockRan = true; true
            }
            assertTrue(stale is MetadataRecoveryJournalV1.Publication.Stale)
            assertFalse("a stale observation must not run the publish block", blockRan)
            val pending = journal.inspect() as? MetadataRecoveryJournalV1.Inspection.Pending
            assertNotNull(pending)
            assertEquals(otherId, pending!!.entry.id)
        } finally {
            dir.deleteRecursively()
        }
    }
}
