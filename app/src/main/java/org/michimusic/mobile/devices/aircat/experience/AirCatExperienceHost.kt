package org.michimusic.mobile.devices.aircat.experience

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.devices.AirCatCapabilityPolicy
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.UnifiedDevice
import org.michimusic.mobile.devices.aircat.AirCatHomeEnrollmentStore

/**
 * Wires the AirCat experience to the real device list and the real Home store.
 *
 * The only non-Compose work this file does is read *facts* — is this peer
 * enrolled with Home, does it advertise AirCat, is it connected — on IO, because
 * the enrollment store is Keystore-backed. It never mints a grant, never opens a
 * socket and never decides that a peer is authorised: [AirCatSourceUi] keeps the
 * six facts apart, and the engine re-checks Home on its own before any byte
 * moves.
 */
@Composable
internal fun AirCatExperienceHost(
    devices: List<UnifiedDevice>,
    engine: AirCatExperienceEngine,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sources by produceState(initialValue = emptyList<AirCatSourceUi>(), devices) {
        value = withContext(Dispatchers.IO) {
            val store = AirCatHomeEnrollmentStore(context)
            devices
                .map { device -> device.toSourceUi(store) }
                .filter { it.known || it.discovered }
                .sortedWith(
                    compareByDescending<AirCatSourceUi> { it.canExplore }
                        .thenByDescending { it.connected }
                        .thenBy { it.name.lowercase() },
                )
        }
    }

    val freeBytes: suspend () -> Long = {
        withContext(Dispatchers.IO) {
            runCatching { File(context.filesDir, "synced_music").usableSpace }
                .getOrDefault(0L)
                .coerceAtLeast(0L)
        }
    }

    val controller = remember(engine) {
        AirCatExperienceController(
            scope = scope,
            engine = engine,
            sources = { sources },
            freeBytes = freeBytes,
        )
    }
    val state = controller.state.collectAsStateWithLifecycle()

    BackHandler(enabled = true) {
        if (state.value.phase == AirCatPhase.HUB) onClose() else controller.dispatch(AirCatIntent.Back)
    }

    AirCatExperienceContent(
        state = state.value,
        onIntent = controller::dispatch,
        modifier = modifier,
    )
}

/**
 * One row of the source list, built from facts that are independently true.
 *
 * `discovered` is presence without trust, `known` is a local pairing record,
 * `connected` is a live transport, `homeEnrolled` is a stored Home authority for
 * *this* source, `airCatAdvertised` is a published capability. None of them
 * implies another, which is exactly why they stay five fields instead of one
 * `ready` boolean.
 */
private fun UnifiedDevice.toSourceUi(store: AirCatHomeEnrollmentStore): AirCatSourceUi {
    val connected = connectionState == SyncConnectionState.CONNECTED
    val enrolled = runCatching { store.anchorForSource(id) != null }.getOrDefault(false)
    val advertised = runCatching { AirCatCapabilityPolicy.hasFeature(features) }.getOrDefault(false)
    return AirCatSourceUi(
        deviceId = id,
        name = name.ifBlank { "Equipo Michi" },
        kindLabel = deviceType.ifBlank { "Equipo Michi" },
        discovered = !isPaired,
        known = isPaired,
        connected = connected,
        homeEnrolled = enrolled,
        airCatAdvertised = advertised,
        revoked = isRevoked,
    )
}
