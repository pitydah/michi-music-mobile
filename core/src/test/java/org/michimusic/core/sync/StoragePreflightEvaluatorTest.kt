package org.michimusic.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.IssueSeverity
import org.michimusic.core.models.SyncItemAction
import org.michimusic.core.models.SyncItemDecision
import org.michimusic.core.models.SyncPreflightStatus

class StoragePreflightEvaluatorTest {

    private val oneMb = 1024L * 1024L
    private val safety100Mb = StoragePreflightEvaluator.SAFETY_BUFFER_BYTES // 100 MB

    @Test
    fun sumsDownloadAndResumeBytes_skipsExistingFiles() {
        val items = listOf(
            SyncItemDecision(
                trackId = "t1", title = "T1", artist = "A1",
                action = SyncItemAction.DOWNLOAD,
                sourceSizeBytes = 10 * oneMb,
                estimatedDownloadBytes = 10 * oneMb
            ),
            SyncItemDecision(
                trackId = "t2", title = "T2", artist = "A2",
                action = SyncItemAction.RESUME_PARTIAL,
                sourceSizeBytes = 10 * oneMb,
                estimatedDownloadBytes = 4 * oneMb // 6 MB already downloaded in .part
            ),
            SyncItemDecision(
                trackId = "t3", title = "T3", artist = "A3",
                action = SyncItemAction.SKIP_ALREADY_EXISTS,
                sourceSizeBytes = 20 * oneMb,
                estimatedDownloadBytes = 0L
            )
        )

        // Available storage is plenty (10 GB)
        val result = StoragePreflightEvaluator.evaluate(10L * 1024 * oneMb, items)

        assertEquals(14 * oneMb, result.totalDownloadBytes)
        assertEquals(SyncPreflightStatus.READY, result.status)
        assertTrue(result.issues.none { it.severity == IssueSeverity.BLOCKING })
    }

    @Test
    fun strictlyEnforces100MbSafetyReserve_blocksWhenExceeded() {
        // Need 50 MB download
        val items = listOf(
            SyncItemDecision(
                trackId = "t1", title = "T1", artist = "A1",
                action = SyncItemAction.DOWNLOAD,
                sourceSizeBytes = 50 * oneMb,
                estimatedDownloadBytes = 50 * oneMb
            )
        )

        // Device has 150 MB free.
        // Download (50 MB) + Safety Reserve (100 MB) + Atomic Buffer (10 MB) = 160 MB needed.
        // Since 150 MB < 160 MB, this MUST be blocked!
        val result = StoragePreflightEvaluator.evaluate(150 * oneMb, items)

        assertEquals(SyncPreflightStatus.BLOCKED_NO_STORAGE, result.status)
        val blockingIssue = result.issues.firstOrNull { it.severity == IssueSeverity.BLOCKING }
        assertTrue(blockingIssue != null)
        assertEquals("STORAGE_EXCEEDED", blockingIssue!!.code)
    }

    @Test
    fun passesWhenStorageAccommodatesDownloadAndSafetyReserve() {
        val items = listOf(
            SyncItemDecision(
                trackId = "t1", title = "T1", artist = "A1",
                action = SyncItemAction.DOWNLOAD,
                sourceSizeBytes = 50 * oneMb,
                estimatedDownloadBytes = 50 * oneMb
            )
        )

        // 300 MB free > 50 MB + 100 MB + 10 MB = 160 MB
        val result = StoragePreflightEvaluator.evaluate(300 * oneMb, items)

        assertEquals(SyncPreflightStatus.READY, result.status)
        assertTrue(result.issues.none { it.severity == IssueSeverity.BLOCKING })
    }

    @Test
    fun emitsWarningWhenPostSyncSpaceIsBelowDoubleSafetyReserve() {
        val items = listOf(
            SyncItemDecision(
                trackId = "t1", title = "T1", artist = "A1",
                action = SyncItemAction.DOWNLOAD,
                sourceSizeBytes = 50 * oneMb,
                estimatedDownloadBytes = 50 * oneMb
            )
        )

        // 220 MB free:
        // Peak needed: 50 + 100 + 10 = 160 MB (< 220, so ready)
        // Remaining after sync: 220 - 50 = 170 MB (< 200 MB warning threshold)
        val result = StoragePreflightEvaluator.evaluate(220 * oneMb, items)

        assertEquals(SyncPreflightStatus.READY, result.status)
        val warning = result.issues.firstOrNull { it.code == "LOW_STORAGE_WARNING" }
        assertTrue(warning != null)
        assertEquals(IssueSeverity.WARNING, warning!!.severity)
    }
}
