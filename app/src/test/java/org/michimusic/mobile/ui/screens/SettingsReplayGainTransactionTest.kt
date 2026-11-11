package org.michimusic.mobile.ui.screens

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.playback.SettingsReplayGainEdit
import org.michimusic.mobile.playback.SettingsReplayGainSnapshot
import org.michimusic.mobile.playback.SettingsReplayGainTransactionPort
import org.michimusic.mobile.playback.SettingsReplayGainTransaction
import org.michimusic.mobile.playback.SettingsReplayGainSaveStatus
import org.michimusic.mobile.playback.applyTo
import org.michimusic.mobile.playback.settingsReplayGainTruth
import org.michimusic.player.ReplayGainMode

class SettingsReplayGainTransactionTest {
    private val original = SettingsReplayGainSnapshot(ReplayGainMode.TRACK, 0f, 0f)

    private class Fake(initial: SettingsReplayGainSnapshot) : SettingsReplayGainTransactionPort {
        var saved = initial
        var commitSucceeds = true
        var changeMemoryEvenOnFailure = false
        var configured: Boolean? = true
        var restorePreviousProcessor = true
        var foreignEdit: (() -> Unit)? = null
        var configuredCount = 0
        var persistCount = 0
        var persistDelayMs = 0L
        var concurrentPersists = 0
        var maxConcurrentPersists = 0
        override suspend fun read() = saved
        override suspend fun persist(edit: SettingsReplayGainEdit): Boolean {
            persistCount++
            concurrentPersists++
            maxConcurrentPersists = maxOf(maxConcurrentPersists, concurrentPersists)
            if (persistDelayMs > 0) delay(persistDelayMs)
            concurrentPersists--
            if (commitSucceeds || changeMemoryEvenOnFailure) saved = edit.applyTo(saved)
            foreignEdit?.invoke(); foreignEdit = null
            return commitSucceeds
        }
        override suspend fun configure(value: SettingsReplayGainSnapshot): Boolean? {
            configuredCount++
            return if (configured == false && restorePreviousProcessor && configuredCount > 1) true
                else configured
        }
    }

    @Test fun validModeIsPersistedBeforeProcessorIsConfigured() = runBlocking {
        val fake = Fake(original)
        val result = SettingsReplayGainTransaction(fake).save(SettingsReplayGainEdit.Mode(ReplayGainMode.ALBUM))
        assertEquals(SettingsReplayGainSaveStatus.CONFIGURED, result.status)
        assertEquals(ReplayGainMode.ALBUM, fake.saved.mode)
        assertEquals(1, fake.configuredCount)
    }

    @Test fun writeFailureLeavesProcessorUntouchedAndReportsRollback() = runBlocking {
        val fake = Fake(original).apply { commitSucceeds = false }
        val result = SettingsReplayGainTransaction(fake).save(SettingsReplayGainEdit.Mode(ReplayGainMode.DYNAMIC))
        assertEquals(SettingsReplayGainSaveStatus.REJECTED, result.status)
        assertEquals(original, result.snapshot)
        assertEquals(0, fake.configuredCount)
        assertFalse(result.success)
    }

    @Test fun failedDiskCommitWithMemoryChangeIsNeverCalledDurable() = runBlocking {
        val fake = Fake(original).apply { commitSucceeds = false; changeMemoryEvenOnFailure = true }
        val result = SettingsReplayGainTransaction(fake).save(SettingsReplayGainEdit.WithTags(3f))
        assertEquals(SettingsReplayGainSaveStatus.STATE_UNCERTAIN, result.status)
        // A second non-durable commit may restore only the in-memory value.
        // The status must remain uncertain even when the read-back looks old.
        assertEquals(0f, result.snapshot!!.withTagsDb)
        assertEquals(2, fake.persistCount)
        assertEquals(0, fake.configuredCount)
    }

    @Test fun rejectedProcessorRestoresOnlyEditedField() = runBlocking {
        val fake = Fake(original).apply { configured = false }
        val result = SettingsReplayGainTransaction(fake).save(SettingsReplayGainEdit.WithoutTags(4f))
        assertEquals(SettingsReplayGainSaveStatus.ROLLED_BACK, result.status)
        assertEquals(0f, result.snapshot!!.withoutTagsDb)
        assertEquals(2, fake.persistCount)
        assertFalse(result.success)
    }

    @Test fun failedProcessorRollbackMustNeverClaimRestoredState() = runBlocking {
        val fake = Fake(original).apply { configured = false; restorePreviousProcessor = false }
        val result = SettingsReplayGainTransaction(fake).save(SettingsReplayGainEdit.WithoutTags(4f))
        assertEquals(SettingsReplayGainSaveStatus.STATE_UNCERTAIN, result.status)
        assertEquals(original.withoutTagsDb, result.snapshot!!.withoutTagsDb)
    }

    @Test fun remoteOutputSavesWithoutClaimingProcessorConfigured() = runBlocking {
        val fake = Fake(original).apply { configured = null }
        val result = SettingsReplayGainTransaction(fake).save(SettingsReplayGainEdit.Mode(ReplayGainMode.ALBUM))
        assertEquals(SettingsReplayGainSaveStatus.SAVED_FOR_LOCAL, result.status)
        assertEquals(ReplayGainMode.ALBUM, result.snapshot!!.mode)
    }

    @Test fun invalidDbDoesNotReachPersistentStorage() = runBlocking {
        val fake = Fake(original)
        val result = SettingsReplayGainTransaction(fake).save(SettingsReplayGainEdit.WithTags(Float.NaN))
        assertEquals(SettingsReplayGainSaveStatus.REJECTED, result.status)
        assertEquals(0, fake.persistCount)
        assertEquals(0, fake.configuredCount)
    }

    @Test fun outOfRangeValueIsRefused() = runBlocking {
        val fake = Fake(original)
        val result = SettingsReplayGainTransaction(fake).save(SettingsReplayGainEdit.WithoutTags(20f))
        assertEquals(SettingsReplayGainSaveStatus.REJECTED, result.status)
        assertEquals(0, fake.persistCount)
    }

    @Test fun noOpDoesNotWriteOrPretendNewProcessorConfiguration() = runBlocking {
        val fake = Fake(original)
        val result = SettingsReplayGainTransaction(fake).save(SettingsReplayGainEdit.Mode(ReplayGainMode.TRACK))
        assertEquals(SettingsReplayGainSaveStatus.SAVED_FOR_LOCAL, result.status)
        assertEquals(0, fake.configuredCount)
        assertEquals(0, fake.persistCount)
    }

    @Test fun externalModeChangeDuringCommitDoesNotGetOverriddenBySettings() = runBlocking {
        val fake = Fake(original).apply { foreignEdit = { saved = saved.copy(mode=ReplayGainMode.OFF) } }
        val result = SettingsReplayGainTransaction(fake).save(SettingsReplayGainEdit.Mode(ReplayGainMode.DYNAMIC))
        assertEquals(SettingsReplayGainSaveStatus.EXTERNAL_CHANGE, result.status)
        assertEquals(ReplayGainMode.OFF, fake.saved.mode)
        assertEquals(0, fake.configuredCount)
    }

    @Test fun writingOneFieldDoesNotOverwriteOtherScreensConcurrentPreamp() = runBlocking {
        val fake = Fake(original).apply { foreignEdit = { saved = saved.copy(withoutTagsDb=6f) } }
        val result = SettingsReplayGainTransaction(fake).save(SettingsReplayGainEdit.WithTags(3f))
        assertEquals(SettingsReplayGainSaveStatus.CONFIGURED, result.status)
        assertEquals(6f, fake.saved.withoutTagsDb)
        assertEquals(3f, fake.saved.withTagsDb)
    }

    @Test fun sharedTransactionSerializesConcurrentChangesToIndependentFields() = runBlocking {
        val fake = Fake(original).apply { persistDelayMs = 15L }
        val transaction = SettingsReplayGainTransaction(fake)
        val results = listOf(
            async { transaction.save(SettingsReplayGainEdit.WithTags(2f)) },
            async { transaction.save(SettingsReplayGainEdit.WithoutTags(3f)) },
        ).awaitAll()
        assertEquals(listOf(true, true), results.map { it.success })
        assertEquals(2f, fake.saved.withTagsDb)
        assertEquals(3f, fake.saved.withoutTagsDb)
        assertEquals(1, fake.maxConcurrentPersists)
    }

    @Test fun outputRemoteCannotBeReportedAsEffectApplied() {
        assertEquals("Guardado para el teléfono; no modifica la salida remota.",
            settingsReplayGainTruth(ReplayGainMode.TRACK, false, true, false))
    }

    @Test fun transparentModeIsNotClassifiedAsAudioApplied() {
        assertTrue(settingsReplayGainTruth(ReplayGainMode.TRACK, true, true, true)
            .contains("efecto omitido"))
    }

    @Test fun disabledModeIsAlwaysHonest() {
        assertTrue(settingsReplayGainTruth(ReplayGainMode.OFF, false, false, true)
            .contains("desactivado"))
    }
}
