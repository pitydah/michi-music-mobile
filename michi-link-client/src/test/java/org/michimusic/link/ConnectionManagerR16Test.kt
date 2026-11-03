package org.michimusic.link

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.link.identity.MichiIdentity

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionManagerR16Test {
    @Test fun disconnectInvalidatesLateConnectionResponse() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val registry = mockk<PairedDeviceRegistry>()
        val identity = mockk<MichiIdentity>()
        val device = PairedDevice(deviceId = "dev", deviceToken = "token",
            lastUrl = "http://192.168.1.10", pairedClientDeviceId = "")
        every { registry.getDevice("dev") } returns device
        val client = mockk<LinkClient>(relaxed = true)
        every { client.baseUrl } returns "http://192.168.1.10"
        every { client.deviceToken } returns "token"
        every { client.pairedClientDeviceId } returns ""
        coEvery { client.getServerInfo() } coAnswers {
            delay(100)
            Result.failure(IllegalStateException("offline"))
        }
        val manager = object : ConnectionManager(registry, identity, dispatcher) {
            init { clients["dev"] = client }
        }
        manager.connect("dev")
        runCurrent()
        manager.disconnect("dev")
        testScheduler.advanceUntilIdle()
        assertEquals(SyncConnectionState.DISCONNECTED, manager.connectionStates.value["dev"])
    }
}
