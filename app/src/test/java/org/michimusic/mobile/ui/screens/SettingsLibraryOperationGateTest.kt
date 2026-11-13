package org.michimusic.mobile.ui.screens

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLibraryOperationGateTest {
    @Test fun onlyOneOperationCanRunAndGateReleasesAfterCompletion(): Unit = runBlocking {
        val gate = SettingsLibraryOperationGate()
        val hold = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val start = CompletableDeferred<Unit>()
        assertTrue(gate.launch(scope, SettingsLibraryOperation.RECOVER) {
            start.complete(Unit)
            hold.await()
        })
        start.await()
        assertFalse(gate.launch(scope, SettingsLibraryOperation.VERIFY) {})
        assertFalse(gate.launch(scope, SettingsLibraryOperation.RESCAN) {})
        hold.complete(Unit)
        while (gate.current != null) yield()
        assertTrue(gate.launch(scope, SettingsLibraryOperation.VERIFY) {})
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    @Test fun cancellationIsAvailableOnlyForReadOperations(): Unit = runBlocking {
        val gate = SettingsLibraryOperationGate()
        val hold = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        assertTrue(gate.launch(scope, SettingsLibraryOperation.RESCAN) { hold.await() })
        assertFalse(gate.canCancel)
        assertFalse(gate.cancelRead())
        hold.complete(Unit)
        while (gate.current != null) yield()
        assertTrue(gate.launch(scope, SettingsLibraryOperation.RECOVER) {
            CompletableDeferred<Unit>().await()
        })
        assertTrue(gate.canCancel)
        assertTrue(gate.cancelRead())
        while (gate.current != null) yield()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    @Test fun cancelledScopeNeverKeepsGateLocked() = runBlocking {
        val gate = SettingsLibraryOperationGate()
        val job = Job().also { it.cancel() }
        val scope = CoroutineScope(job + Dispatchers.Default)
        assertTrue(gate.launch(scope, SettingsLibraryOperation.VERIFY) {})
        while (gate.current != null) yield()
        assertEquals(null, gate.current)
    }
}
