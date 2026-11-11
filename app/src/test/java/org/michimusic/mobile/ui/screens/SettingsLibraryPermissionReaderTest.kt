package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException

class SettingsLibraryPermissionReaderTest {
    @Test fun grantIsNeverInferredFromAnUnknownPermissionQuery() {
        val truth = settingsLibraryPermissionTruth(null, true)
        assertTrue(truth.readFailed)
        assertEquals(true, truth.checked)
        assertEquals(false, truth.canWrite)
        assertEquals(false, truth.permissionRevoked)
    }

    @Test fun aFailedWriteQueryCannotAuthorizeAnOperation() {
        val truth = settingsLibraryPermissionTruth(false, null)
        assertEquals(SettingsLibraryPermissionTruth(readFailed = true, checked = true), truth)
    }

    @Test fun revocationAlwaysWinsOverPreviouslyWritableState() {
        val truth = settingsLibraryPermissionTruth(true, true)
        assertTrue(truth.permissionRevoked)
        assertEquals(false, truth.canWrite)
        assertEquals(false, truth.readFailed)
    }

    @Test fun knownReadonlyTreeIsNotIncorrectlyCalledRevoked() {
        val truth = settingsLibraryPermissionTruth(false, false)
        assertEquals(false, truth.permissionRevoked)
        assertEquals(false, truth.canWrite)
        assertEquals(false, truth.readFailed)
    }

    @Test fun providerExceptionFailsClosedWithoutCrashingTheScreen() = runBlocking {
        val status = SettingsLibraryPermissionReader { throw SecurityException("SAF revoked") }.read()
        assertEquals(true, status.checked)
        assertEquals(true, status.readFailed)
        assertEquals(false, status.canWrite)
    }

    @Test fun cancellationPropagatesRatherThanBecomingPermissionFailure() = runBlocking {
        val reader = SettingsLibraryPermissionReader { throw CancellationException("screen disposed") }
        var cancelled = false
        try { reader.read() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun initialPermissionStateIsPendingNotAnErrorOrGrant() {
        val initial = SettingsLibraryPermissionTruth()
        assertEquals(false, initial.checked)
        assertEquals(false, initial.readFailed)
        assertEquals(false, initial.canWrite)
    }

    @Test fun confirmedWriteGrantIsOnlyStateThatEnablesCreation() {
        val truth = settingsLibraryPermissionTruth(false, true)
        assertEquals(true, truth.canWrite)
        assertEquals(false, truth.readFailed)
    }
}
