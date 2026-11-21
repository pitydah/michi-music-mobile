package org.michimusic.data.metadata

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.metadata.MetadataSyntheticProviderEvidence as Evidence

/**
 * ME-09: an automatic same-process smoke, plus TWO explicitly opted-in phases.
 * Run the latter in SEPARATE instrumentation commands. The first kills the
 * instrumentation process and is EXPECTED to report an interrupted runner;
 * the second reads the private checkpoint and verifies restoration.
 */
@RunWith(AndroidJUnit4::class)
class MetadataSyntheticRestartInstrumentedTest {
    private fun harness()=MetadataSyntheticRestartHarness(
        InstrumentationRegistry.getInstrumentation().context)

    @Test fun coldReconstructionRoundTripDoesNotCertifyProductWrites() {
        val h=harness()
        assertEquals(Evidence.Outcome.PARTIAL_OR_FOREIGN_BYTES,h.seedInterrupted())
        assertEquals(MetadataSyntheticRestartHarness.Recovery.RestoredAndDiscarded,
            MetadataSyntheticRestartHarness(InstrumentationRegistry.getInstrumentation().context)
                .resumeAfterRestart(explicitConfirmation=true))
        assertEquals(MetadataSyntheticRestartHarness.Recovery.Missing,h.resumeAfterRestart(true))
    }

    @Test fun phaseOneSeedThenKillRunnerOptInOnly() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("ME09_KILL_SYNTHETIC_RUNNER")=="I_ACCEPT_RUNNER_TERMINATION")
        assertEquals(Evidence.Outcome.PARTIAL_OR_FOREIGN_BYTES,harness().seedInterrupted())
        // Only after a durable checkpoint and partial write of a disposable UUID.
        // This DOES NOT kill the media player process or any user's music file.
        Process.killProcess(Process.myPid())
        error("PROCESS_KILL_DID_NOT_OCCUR")
    }

    @Test fun phaseTwoRestartAndRestoreOptInOnly() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("ME09_RESTORE_SYNTHETIC_RUNNER")=="I_ACCEPT_SYNTHETIC_RESTORE")
        assertEquals(MetadataSyntheticRestartHarness.Recovery.RestoredAndDiscarded,
            harness().resumeAfterRestart(explicitConfirmation=true))
    }
}
