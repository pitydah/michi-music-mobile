package org.michimusic.mobile.devices.aircat

import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkTransferManager
import org.michimusic.link.PairedDeviceRegistry

class AirCatOwnedClientCustodianTest {
    private fun service() = DefaultAirCatTransferClient(
        transferManager = mockk<LinkTransferManager>(relaxed = true),
        pairedDeviceRegistry = mockk<PairedDeviceRegistry>(relaxed = true),
        libraryRepository = null,
    )

    @Test fun unregisteredSessionClosesOwnedClient() {
        val service = service()
        val owned = mockk<LinkClient>(relaxed = true)
        service.adoptOwnedClient("unaccepted", owned)
        service.releaseIfNotRetained("unaccepted")
        service.releaseIfNotRetained("unaccepted")
        verify(exactly = 1) { owned.close() }
    }

    @Test fun cancelClosesAndRemovesOwnedSessionClient() {
        val service = service()
        val owned = mockk<LinkClient>(relaxed = true)
        service.adoptOwnedClient("cancelled", owned)
        service.cancelTransfer("cancelled")
        service.releaseIfNotRetained("cancelled")
        verify(exactly = 1) { owned.close() }
    }
}
