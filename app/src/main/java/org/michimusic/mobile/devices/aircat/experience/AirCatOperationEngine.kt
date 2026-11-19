package org.michimusic.mobile.devices.aircat.experience

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.michimusic.link.LinkClient
import org.michimusic.mobile.devices.aircat.AirCatOperationCoordinator
import org.michimusic.mobile.devices.aircat.AirCatProgressEvent
import org.michimusic.mobile.devices.aircat.AirCatTransferClient
import org.michimusic.mobile.devices.aircat.CataloguePreview
import org.michimusic.mobile.devices.aircat.SelectionEntry

/**
 * The only thing standing between the AirCat experience and the real engine.
 *
 * This adapter exists so the screen never touches a socket, a keystore read or a
 * grant: [AirCatExperienceEngine] is the whole surface the UI may use, and every
 * method here forwards to the same `AirCatOperationCoordinator` /
 * `AirCatTransferClient` that already passed the security certification. No
 * second download engine, no second identity, no local grant.
 */
internal class AirCatOperationEngine(
    private val coordinator: AirCatOperationCoordinator,
    private val client: AirCatTransferClient?,
    private val currentClient: () -> LinkClient? = { null },
) : AirCatExperienceEngine {

    /** A client that is not wired (feature disabled) reports no progress at all. */
    private val noProgress = MutableStateFlow<AirCatProgressEvent?>(null)

    override val progress: StateFlow<AirCatProgressEvent?> =
        client?.progressEvents ?: noProgress

    override suspend fun explore(
        targetDeviceId: String,
        currentClient: LinkClient?,
    ): Result<CataloguePreview> =
        coordinator.exploreCatalog(targetDeviceId, currentClient ?: this.currentClient())

    override fun start(
        targetDeviceId: String,
        scope: CoroutineScope,
        currentClient: LinkClient?,
        onResult: (Boolean, String) -> Unit,
        selection: List<SelectionEntry>?,
        onTransferIdentified: (String) -> Unit,
    ) {
        coordinator.startTransfer(
            targetDeviceId = targetDeviceId,
            scope = scope,
            currentClient = currentClient ?: this.currentClient(),
            onResult = onResult,
            selection = selection,
            onTransferIdentified = onTransferIdentified,
        )
    }

    override fun pause(transferId: String): Boolean {
        client?.pauseTransfer(transferId)
        return client?.isTransferPaused(transferId) == true
    }

    override fun resume(transferId: String) {
        client?.resumeTransfer(transferId)
    }

    override fun cancel(transferId: String) {
        client?.cancelTransfer(transferId)
    }

    override fun isPaused(transferId: String): Boolean =
        client?.isTransferPaused(transferId) == true
}
