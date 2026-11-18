package org.michimusic.mobile.devices.aircat

import android.content.Context
import org.robolectric.RuntimeEnvironment
import io.mockk.coEvery
import io.mockk.coVerify
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
    private val PINNED = "https://source.example"

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
        val service = mockk<AirCatTransferClient>(relaxed = true)
        // A68-13: the operation cannot reach any HTTP call until Home enrollment
        // answers with a pin for this exact source and endpoint.
        val enrolledPin = "sha256/" + java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        every { service.enrolledSourceTlsPin("micro", PINNED) } returns enrolledPin
        every { registry.getDevice("micro") } returns PairedDevice(
            deviceId = "micro", deviceToken = "token", michiId = "micro", lastUrl = PINNED,
        )
        every { connections.getClient("micro") } returns null
        val transient = mockk<LinkClient>(relaxed = true)
        every { transient.baseUrl } returns PINNED
        every { transient.deviceToken } returns "token"
        every { transient.pairedClientDeviceId } returns ""
        // A27: the transfer runs on a fresh pinned transport created FROM the
        // transient, and the transient must be closed whether or not that succeeds.
        val secure = mockk<LinkClient>(relaxed = true)
        every { transient.pinnedAirCatSession(enrolledPin) } returns secure
        coEvery { secure.getServerInfo() } returns Result.failure(IllegalStateException("offline"))
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
        assertTrue(
            "the refusal has to name the unverified session, but was ${results.single().second}",
            results.single().second.contains("No se pudo verificar la sesión remota"),
        )
        verify(exactly = 1) { transient.close() }
        verify(exactly = 1) { transient.pinnedAirCatSession(enrolledPin) }
        val lease = AirCatLeaseCoordinator(context)
        assertTrue(lease.tryEnter())
        lease.leave()
    }

    /**
     * A68-13: a transient client resolved to a different origin must be closed and
     * the operation refused, even though the same SPKI pin would validate there too.
     * A pinned Home URL is a saved binding, never a hint a scan can improve on.
     */
    @Test fun aResolvedEndpointThatMovedIsClosedAndRefused() = runBlocking {
        every { identity.michiId } returns "local"
        val service = mockk<AirCatTransferClient>(relaxed = true)
        val enrolledPin = "sha256/" + java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        every { service.enrolledSourceTlsPin("micro", PINNED) } returns enrolledPin
        every { registry.getDevice("micro") } returns PairedDevice(
            deviceId = "micro", deviceToken = "token", michiId = "micro", lastUrl = PINNED,
        )
        every { connections.getClient("micro") } returns null
        val moved = mockk<LinkClient>(relaxed = true)
        every { moved.baseUrl } returns "https://moved.example"
        every { moved.deviceToken } returns "token"
        every { moved.pairedClientDeviceId } returns ""
        val results = mutableListOf<Pair<Boolean, String>>()
        val first = CompletableDeferred<Unit>()
        coordinator(service) { _, _ -> moved }.startTransfer("micro", this, null) { ok, message ->
            results += ok to message
            first.complete(Unit)
        }
        withTimeout(5000) { first.await() }
        coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.join() }
        assertEquals(1, results.size)
        assertFalse(results.single().first)
        assertTrue(
            "the refusal has to say the location changed, but was ${results.single().second}",
            results.single().second.contains("cambió"),
        )
        verify(exactly = 1) { moved.close() }
        // Nothing may be negotiated against an endpoint Home did not enrol.
        verify(exactly = 0) { moved.pinnedAirCatSession(any()) }
        coVerify(exactly = 0) { moved.getServerInfo() }
        val lease = AirCatLeaseCoordinator(context)
        assertTrue(lease.tryEnter())
        lease.leave()
    }
}
