package org.michimusic.data.metadata

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataUncertifiedObservationLedgerTest {
    private val id = "11111111-2222-3333-4444-555555555555"
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private fun entry(n: Long = 1, outcome: MetadataUncertifiedObservationLedger.Outcome =
        MetadataUncertifiedObservationLedger.Outcome.CANDIDATE_UNCERTIFIED) =
        MetadataUncertifiedObservationLedger.Record(id, a, b, outcome, n)

    @Test fun persistsAcrossNewLedgerInstancesWithoutSavedClaims() {
        val dir = Files.createTempDirectory("me09-ledger-").toFile()
        try {
            val first = MetadataUncertifiedObservationLedger(dir)
            assertEquals(MetadataUncertifiedObservationLedger.Read.Absent, first.inspect())
            assertTrue(first.record(entry()))
            assertEquals(MetadataUncertifiedObservationLedger.Read.Valid(entry()),
                MetadataUncertifiedObservationLedger(dir).inspect())
            assertTrue(dir.listFiles()!!.none { it.name.contains("content") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun deniesSequenceRewindsAndCrossTransactionOverwrite() {
        val dir = Files.createTempDirectory("me09-ledger-").toFile()
        try {
            val ledger = MetadataUncertifiedObservationLedger(dir)
            assertFalse(ledger.record(entry(2)))
            assertTrue(ledger.record(entry()))
            assertFalse(ledger.record(entry()))
            assertFalse(ledger.record(entry(2).copy(transactionId=
                "22222222-3333-4444-5555-666666666666")))
            assertTrue(ledger.record(entry(2,MetadataUncertifiedObservationLedger.Outcome.UNKNOWN_BYTES)))
            assertEquals(MetadataUncertifiedObservationLedger.Read.Valid(
                entry(2,MetadataUncertifiedObservationLedger.Outcome.UNKNOWN_BYTES)),ledger.inspect())
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptLedgerBlocksRatherThanReplaces() {
        val dir = Files.createTempDirectory("me09-ledger-").toFile()
        try {
            val ledger=MetadataUncertifiedObservationLedger(dir)
            assertTrue(ledger.record(entry()))
            dir.resolve("uncertified-observation-v1.bin").appendText("tamper")
            assertEquals(MetadataUncertifiedObservationLedger.Read.UntrustedKeepArtifacts,ledger.inspect())
            assertFalse(ledger.record(entry(2)))
            assertFalse(ledger.clearIfKnown(entry(),true))
        } finally { dir.deleteRecursively() }
    }

    @Test fun privateFilesRequireExplicitCleanupAndExactRecord() {
        val dir=Files.createTempDirectory("me09-ledger-").toFile()
        try {
            val ledger=MetadataUncertifiedObservationLedger(dir)
            assertTrue(ledger.record(entry()))
            assertFalse(ledger.clearIfKnown(entry(),false))
            assertFalse(ledger.clearIfKnown(entry(2),true))
            assertTrue(ledger.clearIfKnown(entry(),true))
            assertEquals(MetadataUncertifiedObservationLedger.Read.Absent,ledger.inspect())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsMisleadingDigestsAndUnboundedSequence() {
        val dir=Files.createTempDirectory("me09-ledger-").toFile()
        try {
            val ledger=MetadataUncertifiedObservationLedger(dir)
            assertFalse(ledger.record(entry().copy(backupSha256="not a digest")))
            assertFalse(ledger.record(entry().copy(candidateSha256=a)))
            assertFalse(ledger.record(entry().copy(sequence=Long.MAX_VALUE)))
            assertEquals(MetadataUncertifiedObservationLedger.Read.Absent,ledger.inspect())
        } finally { dir.deleteRecursively() }
    }

    @Test fun symlinkRootMustNotBeAccepted() {
        val source=Files.createTempDirectory("me09-origin-")
        val link=source.parent.resolve("me09-link-${java.util.UUID.randomUUID()}")
        try {
            Files.createSymbolicLink(link,source)
            val ledger=MetadataUncertifiedObservationLedger(link.toFile())
            assertEquals(MetadataUncertifiedObservationLedger.Read.UntrustedKeepArtifacts,ledger.inspect())
            assertFalse(ledger.record(entry()))
        } finally { Files.deleteIfExists(link);source.toFile().deleteRecursively() }
    }
}
