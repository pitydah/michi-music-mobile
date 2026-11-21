package org.michimusic.data.metadata

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.metadata.MetadataSyntheticProviderEvidence as Evidence

/**
 * ME-08. Runs ONLY in Android instrumentation test APK. Each test owns a random
 * synthetic document, never selects a song from a user library.
 */
@RunWith(AndroidJUnit4::class)
class MetadataSyntheticProviderTrialInstrumentedTest {
    private lateinit var lab: MetadataSyntheticProviderTrial
    private lateinit var s: MetadataSyntheticProviderTrial.Session
    private val original = ByteArray(4096) { (it % 251).toByte() }
    private val candidate = ByteArray(997) { ((it * 7) % 251).toByte() }

    @Before fun setup() {
        lab = MetadataSyntheticProviderTrial(
            InstrumentationRegistry.getInstrumentation().context)
        s = lab.prepare(original, candidate)
    }

    @After fun cleanup() {
        // The original synthetic test file is restored and checked before deletion.
        val restored = lab.restore(s,explicitConfirmation=true)
        assertTrue(restored == Evidence.Restore.STILL_ORIGINAL ||
            restored == Evidence.Restore.SYNTHETIC_ORIGINAL_RESTORED)
        assertTrue(lab.discardSyntheticSession(s, explicitConfirmation=true))
    }

    @Test fun testApkProviderIsExactAndNonProduction() {
        assertEquals(Evidence.FIXTURE_AUTHORITY,s.uri.authority)
        assertTrue(DocumentsContract.getDocumentId(s.uri).startsWith("me08-fixture-"))
        assertFalse(Evidence.mayEnableRealSourceWrites(Evidence.Outcome.CANDIDATE_IN_SYNTHETIC_PROVIDER))
    }

    @Test fun unmodifiedBeforeOpeningDoesNotMutateBytes() {
        val result=lab.execute(s,Evidence.Cut.BEFORE_OPEN)
        assertEquals(Evidence.Outcome.UNCHANGED,result.outcome)
    }

    @Test fun readWriteTruncateSavesCandidateBytesOnlyInSynthetic() {
        val result=lab.execute(s,Evidence.Cut.NONE)
        assertEquals(Evidence.Outcome.CANDIDATE_IN_SYNTHETIC_PROVIDER,result.outcome)
        assertTrue(result.writeClosed)
        assertEquals(candidate.size.toLong(),result.readback?.size)
    }

    @Test fun afterTruncateIsZeroAndRecoverable() {
        val result=lab.execute(s,Evidence.Cut.AFTER_TRUNCATE)
        assertEquals(Evidence.Outcome.TRUNCATED_TO_ZERO,result.outcome)
        assertEquals(0L,result.readback?.size)
        assertEquals(Evidence.Restore.BLOCKED,lab.restore(s,false))
        assertEquals(Evidence.Restore.SYNTHETIC_ORIGINAL_RESTORED,lab.restore(s,true))
    }

    @Test fun afterFirstBlockProducesPartialBytes() {
        val result=lab.execute(s,Evidence.Cut.AFTER_FIRST_BLOCK)
        assertEquals(Evidence.Outcome.PARTIAL_OR_FOREIGN_BYTES,result.outcome)
        assertEquals(17L,result.readback?.size)
    }

    @Test fun interruptionAfterFsyncIsNotReceipt() {
        val result=lab.execute(s,Evidence.Cut.AFTER_SYNC)
        assertEquals(Evidence.Outcome.CANDIDATE_IN_SYNTHETIC_PROVIDER,result.outcome)
        assertFalse(result.writeClosed)
        assertFalse(Evidence.mayEnableRealSourceWrites(result.outcome))
    }

    @Test fun writeModeMayPreserveUnwrittenTail() {
        val result=lab.execute(s,Evidence.Cut.NONE,mode="w")
        assertEquals(Evidence.Outcome.PARTIAL_OR_FOREIGN_BYTES,result.outcome)
        assertEquals(original.size.toLong(),result.readback?.size)
    }

    @Test fun randomFixtureDocumentIdsAreIsolated() {
        val other=lab.prepare(ByteArray(40){1},ByteArray(10){2})
        try {
            assertNotEquals(s.uri,other.uri)
            assertEquals(Evidence.Outcome.UNCHANGED,lab.execute(s,Evidence.Cut.BEFORE_OPEN).outcome)
            assertEquals(Evidence.Outcome.CANDIDATE_IN_SYNTHETIC_PROVIDER,
                lab.execute(other,Evidence.Cut.NONE).outcome)
        } finally {
            assertEquals(Evidence.Restore.SYNTHETIC_ORIGINAL_RESTORED,lab.restore(other,true))
            assertTrue(lab.discardSyntheticSession(other,true))
        }
    }

    @Test fun forgedUriCannotBePassedToTrial() {
        val forged=s.copy(uri=android.net.Uri.parse("content://media/external/audio/media/1"))
        assertEquals(Evidence.Outcome.INVALID_FIXTURE,
            lab.execute(forged,Evidence.Cut.NONE).outcome)
        assertEquals(Evidence.Restore.BLOCKED,lab.restore(forged,true))
    }

    @Test fun syntheticCleanupRefusesToDeleteWhileCandidatePresent() {
        lab.execute(s,Evidence.Cut.NONE)
        assertFalse(lab.discardSyntheticSession(s,true))
    }

    @Test fun noExplicitCleanupConsentIsAllowed() {
        assertFalse(lab.discardSyntheticSession(s,false))
    }

    @Test fun fiveCutMatrixRequiresVerifiedRestoreEveryTime() {
        val steps=mutableListOf<org.michimusic.core.metadata.MetadataSyntheticProviderMatrix.Step>()
        for (cut in Evidence.Cut.entries) {
            val observed=lab.execute(s,cut)
            val restored=lab.restore(s,true)
            steps += org.michimusic.core.metadata.MetadataSyntheticProviderMatrix.Step(
                s.id,cut,observed.outcome,restored)
        }
        assertEquals(org.michimusic.core.metadata.MetadataSyntheticProviderMatrix.Verdict.COMPLETE_SYNTHETIC_ONLY,
            org.michimusic.core.metadata.MetadataSyntheticProviderMatrix.evaluate(steps))
    }

    @Test fun providerQueryableSizeReflectsSyntheticWrite() {
        lab.execute(s,Evidence.Cut.NONE)
        val ctx=InstrumentationRegistry.getInstrumentation().context
        val col=DocumentsContract.Document.COLUMN_SIZE
        val value=ctx.contentResolver.query(s.uri,arrayOf(col),null,null,null)?.use { cur ->
            if (cur.moveToFirst()) cur.getLong(0) else -1L
        }
        assertEquals(candidate.size.toLong(),value)
    }
}
