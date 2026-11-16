package org.michimusic.mobile.devices.aircat

import android.content.Context
import org.robolectric.RuntimeEnvironment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.michimusic.link.ConnectionManager
import org.michimusic.link.LinkClient
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.identity.IdentityState
import org.michimusic.link.identity.MichiIdentity
import org.michimusic.link.identity.MichiIdentityAuthority

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AirCatOperationCoordinatorTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = mockk<PairedDeviceRegistry>()
    private val connections = mockk<ConnectionManager>()
    private val identity = mockk<MichiIdentity>()
    private val authority = MichiIdentityAuthority(
        context, factory = { identity }, initialState = IdentityState.Ready(identity),
    )

    private fun coordinator(
        service: AirCatTransferClient?,
        factory: (PairedDevice, MichiIdentity) -> LinkClient = { _, _ -> error("No debe crear HTTP") },
    ) = AirCatOperationCoordinator(
        context, registry, connections, authority, service, AirCatLeaseCoordinator(context), factory,
    )

    @Test fun unavailableServiceFailsSynchronouslyOnce() {
        val messages = mutableListOf<Pair<Boolean, String>>()
        coordinator(null).startTransfer("device", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), null) { a, b ->
            messages += a to b
        }
        assertEquals(1, messages.size)
        assertFalse(messages.single().first)
        assertTrue(messages.single().second.contains("no disponible"))
    }

    @Test fun revokedDeviceCannotAcquireTransferLease() {
        every { registry.getDevice("revoked") } returns PairedDevice(
            deviceId = "revoked", deviceToken = "token", lastUrl = "http://192.168.1.2", isRevoked = true,
        )
        val service = mockk<AirCatTransferClient>(relaxed = true)
        val messages = mutableListOf<Pair<Boolean, String>>()
        coordinator(service).recover("revoked", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), null) { a, b ->
            messages += a to b
        }
        assertEquals(1, messages.size)
        assertFalse(messages.single().first)
        assertTrue(messages.single().second.contains("revocado"))
    }

    @Test fun transientClientClosesAfterRemoteVerificationFailureAndReleasesLease() = runBlocking {
        every { identity.michiId } returns "local"
        every { registry.getDevice("micro") } returns PairedDevice(
            deviceId = "micro", deviceToken = "token", lastUrl = "http://192.168.1.2",
        )
        every { connections.getClient("micro") } returns null
        val transient = mockk<LinkClient>(relaxed = true)
        every { transient.baseUrl } returns "http://192.168.1.2"
        every { transient.deviceToken } returns "token"
        every { transient.pairedClientDeviceId } returns ""
        coEvery { transient.getServerInfo() } returns Result.failure(IllegalStateException("offline"))
        val service = mockk<AirCatTransferClient>(relaxed = true)
        val results = mutableListOf<Pair<Boolean, String>>()
        val first = CompletableDeferred<Unit>()
        coordinator(service) { _, _ -> transient }.startTransfer("micro", this, null) { ok, message ->
            results += ok to message
            first.complete(Unit)
        }
        withTimeout(5000) { first.await() }
        // Join all child operations before verifying the finally path.
        coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.join() }
        assertEquals(1, results.size)
        assertFalse(results.single().first)
        verify(exactly = 1) { transient.close() }
        val lease = AirCatLeaseCoordinator(context)
        assertTrue(lease.tryEnter())
        lease.leave()
    }
}
