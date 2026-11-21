package org.michimusic.data.metadata

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataSafRenameRecoveryPolicy

class MetadataSafRenameJournalTest {
    private fun root(): File = Files.createTempDirectory("me-rename-journal-").toFile()
    private val tx = "123e4567-e89b-42d3-a456-426614174000"
    private val oldUri = "content://local/document/a"
    private val newUri = "content://local/document/b"
    private val parentUri = "content://local/tree/parent"
    private val hash = "a".repeat(64)
    private val intent = MetadataSafRenameRecoveryPolicy.Intent(
        tx,"","track",oldUri,"a",parentUri,"Old.flac","New.flac",hash,100)
    private fun observation(vararg children: MetadataSafRenameRecoveryPolicy.Child) =
        MetadataSafRenameRecoveryPolicy.Observation("","track",parentUri,true,true,true,true,
            children.toList())
    private val newChild = MetadataSafRenameRecoveryPolicy.Child("b",newUri,"New.flac",100,hash,true)
    private val oldChild = MetadataSafRenameRecoveryPolicy.Child("a",oldUri,"Old.flac",100,hash,true)

    @Test fun preparationSurvivesFreshJournalInstance() {
        val dir=root()
        val first=MetadataSafRenameJournal(dir)
        val prepared=first.prepare(intent,true)
        assertEquals(MetadataSafRenameJournal.Phase.PREPARED,prepared.phase)
        val next=MetadataSafRenameJournal(dir).inspect() as MetadataSafRenameJournal.State.Pending
        assertEquals(prepared,next.entry)
    }
    @Test fun pendingMetadataRecoveryBlocksPreparation() {
        val dir=root()
        try { MetadataSafRenameJournal(dir).prepare(intent,false); throw AssertionError("should refuse") }
        catch(e:IllegalArgumentException) { assertTrue(e.message!!.contains("OTHER_METADATA")) }
    }
    @Test fun invokingAndProviderResultArePersistedBeforeReadback() {
        val dir=root()
        val journal=MetadataSafRenameJournal(dir)
        val prepared=journal.prepare(intent,true)
        val invoking=journal.markInvoking(prepared)
        assertEquals(MetadataSafRenameJournal.Phase.INVOKING,invoking.phase)
        val returned=MetadataSafRenameJournal(dir).markProviderReturned(invoking,newUri)
        assertEquals(MetadataSafRenameJournal.Phase.PROVIDER_RETURNED,returned.phase)
        assertEquals(newUri,returned.returnedDocumentUri)
        assertEquals(returned,(MetadataSafRenameJournal(dir).inspect() as
            MetadataSafRenameJournal.State.Pending).entry)
    }
    @Test fun fullReadbackCanClassifyRenamedWithoutDeletingJournal() {
        val dir=root()
        val journal=MetadataSafRenameJournal(dir)
        val invoking=journal.markInvoking(journal.prepare(intent,true))
        journal.markProviderReturned(invoking,newUri)
        val outcome=MetadataSafRenameJournal(dir).reconcile(observation(newChild))
        assertEquals(MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified(newUri,"b"), outcome)
        assertEquals(MetadataSafRenameJournal.Phase.RENAMED_VERIFIED,
            (journal.inspect() as MetadataSafRenameJournal.State.Pending).entry.phase)
    }
    @Test fun crashBeforeProviderReturnRequiresManualReviewForChangedIds() {
        val dir=root()
        val journal=MetadataSafRenameJournal(dir)
        journal.markInvoking(journal.prepare(intent,true))
        val outcome=MetadataSafRenameJournal(dir).reconcile(observation(newChild))
        assertEquals(MetadataSafRenameRecoveryPolicy.Verdict.Uncertain(
            MetadataSafRenameRecoveryPolicy.Reason.PROVIDER_RESULT_MISSING),outcome)
        assertEquals(MetadataSafRenameJournal.Phase.UNKNOWN,
            (journal.inspect() as MetadataSafRenameJournal.State.Pending).entry.phase)
    }
    @Test fun invalidProviderResultStaysUnknownAndRetainsJournal() {
        val journal=MetadataSafRenameJournal(root())
        val invoking=journal.markInvoking(journal.prepare(intent,true))
        val returned=journal.markProviderReturned(invoking,"https://wrong/doc")
        assertEquals(MetadataSafRenameJournal.Phase.UNKNOWN,returned.phase)
        assertEquals(MetadataSafRenameJournal.Phase.UNKNOWN,
            (journal.inspect() as MetadataSafRenameJournal.State.Pending).entry.phase)
    }
    @Test fun illegalTransitionCannotSkipDurableInvokingPhase() {
        val journal=MetadataSafRenameJournal(root())
        val prepared=journal.prepare(intent,true)
        try { journal.markProviderReturned(prepared,newUri);throw AssertionError("should refuse") }
        catch(_:IllegalStateException) { /* correct */ }
        assertEquals(prepared,(journal.inspect() as MetadataSafRenameJournal.State.Pending).entry)
    }
    @Test fun unchangedOriginalAfterNullProviderResultIsNotRenamed() {
        val dir=root()
        val journal=MetadataSafRenameJournal(dir)
        val invoking=journal.markInvoking(journal.prepare(intent,true))
        journal.markProviderReturned(invoking,null)
        assertEquals(MetadataSafRenameRecoveryPolicy.Verdict.OriginalVerified(oldUri,"a"),
            MetadataSafRenameJournal(dir).reconcile(observation(oldChild)))
    }
    @Test fun corruptRecordPreservedAndBlocksNewIntent() {
        val dir=root()
        val journal=MetadataSafRenameJournal(dir)
        journal.prepare(intent,true)
        val record=File(dir,"metadata-saf-rename-v1.bin")
        val data=record.readBytes(); data[5]=(data[5].toInt() xor 0x7f).toByte(); record.writeBytes(data)
        assertTrue(journal.inspect() is MetadataSafRenameJournal.State.Blocked)
        try { journal.prepare(intent,true);throw AssertionError("should refuse") }
        catch(_:IllegalStateException) { /* existing corrupt journal blocks */ }
        assertTrue(record.exists())
    }
    @Test fun uncertainCannotBeClearedByPreparedDiscard() {
        val dir=root()
        val journal=MetadataSafRenameJournal(dir)
        val invoking=journal.markInvoking(journal.prepare(intent,true))
        journal.markUncertain(invoking)
        assertFalse(journal.discardNeverInvoked(tx,true))
        assertTrue(journal.inspect() is MetadataSafRenameJournal.State.Pending)
    }
    @Test fun uninvokedCanOnlyBeDiscardedWithConfirmation() {
        val journal=MetadataSafRenameJournal(root())
        journal.prepare(intent,true)
        assertFalse(journal.discardNeverInvoked(tx,false))
        assertFalse(journal.discardNeverInvoked("123e4567-e89b-42d3-a456-426614174001",true))
        assertTrue(journal.discardNeverInvoked(tx,true))
        assertEquals(MetadataSafRenameJournal.State.Clear,journal.inspect())
    }
    @Test fun secondPreparationNeverOverwritesFirstReceipt() {
        val journal=MetadataSafRenameJournal(root())
        val prepared=journal.prepare(intent,true)
        try { journal.prepare(intent.copy(transactionId="123e4567-e89b-42d3-a456-426614174001"),true)
            throw AssertionError("should refuse") }
        catch(_:IllegalStateException) { /* checked */ }
        assertEquals(prepared,(journal.inspect() as MetadataSafRenameJournal.State.Pending).entry)
    }

    @Test fun terminalRenamedReceiptCannotBeDowngradedByLaterUntrustedObservation() {
        val journal=MetadataSafRenameJournal(root())
        val invoking=journal.markInvoking(journal.prepare(intent,true))
        journal.markProviderReturned(invoking,newUri)
        journal.reconcile(observation(newChild))
        try {
            journal.reconcile(observation(oldChild))
            throw AssertionError("terminal receipt was reclassified")
        } catch (_:IllegalStateException) { /* terminal receipts remain immutable */ }
        assertEquals(MetadataSafRenameJournal.Phase.RENAMED_VERIFIED,
            (journal.inspect() as MetadataSafRenameJournal.State.Pending).entry.phase)
    }
    @Test fun terminalOriginalReceiptCannotBecomeRenamedAfterLaterProviderChanges() {
        val journal=MetadataSafRenameJournal(root())
        val invoking=journal.markInvoking(journal.prepare(intent,true))
        journal.markProviderReturned(invoking,null)
        journal.reconcile(observation(oldChild))
        try {
            journal.reconcile(observation(newChild))
            throw AssertionError("original receipt was reclassified")
        } catch (_:IllegalStateException) { /* terminal receipt preserved */ }
        assertEquals(MetadataSafRenameJournal.Phase.ORIGINAL_VERIFIED,
            (journal.inspect() as MetadataSafRenameJournal.State.Pending).entry.phase)
    }
}
