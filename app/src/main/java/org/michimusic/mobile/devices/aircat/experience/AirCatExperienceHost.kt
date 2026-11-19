package org.michimusic.mobile.devices.aircat.experience

import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
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
    observedPeerIds: Set<String> = emptySet(),
    transferScope: CoroutineScope? = null,
    onSearchDevices: () -> Unit = {},
    onManageHome: () -> Unit = {},
    retainedController: AirCatExperienceController? = null,
    onSourcesUpdated: ((List<AirCatSourceUi>) -> Unit)? = null,
    recoveryProvider: suspend (Set<String>) -> List<AirCatRecoveryUi> = { emptyList() },
    onRepairSource: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sources by produceState(initialValue = emptyList<AirCatSourceUi>(), devices, observedPeerIds) {
        value = withContext(Dispatchers.IO) {
            val store = AirCatHomeEnrollmentStore(context)
            devices
                .map { device -> device.toSourceUi(store, device.id in observedPeerIds) }
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
            // The same real filesystem root used by AirCatOperationCoordinator.
            // Probe failures throw and keep Confirm disabled: UNKNOWN != zero bytes.
            StatFs(context.filesDir.absolutePath).availableBytes.also { require(it >= 0L) }
        }
    }

    // Keep the latest roster while retaining the controller across recomposition.
    val currentSources by rememberUpdatedState(sources)
    val controller = retainedController ?: remember(engine) {
        AirCatExperienceController(
            scope = scope,
            engine = engine,
            operationScope = transferScope ?: scope,
            sources = { currentSources },
            freeBytes = freeBytes,
        )
    }
    val state = controller.state.collectAsStateWithLifecycle()
    val latestOnSourcesUpdated by rememberUpdatedState(onSourcesUpdated)
    LaunchedEffect(sources) {
        if (latestOnSourcesUpdated != null) latestOnSourcesUpdated?.invoke(sources)
        else controller.dispatch(AirCatIntent.RefreshSources)
    }
    // Only when Devices/AirCat is visible; no periodic LAN or Room polling.
    val recoveries by produceState(initialValue = emptyList<AirCatRecoveryUi>(), devices) {
        value = withContext(Dispatchers.IO) {
            runCatching { recoveryProvider(devices.filter { it.isPaired && !it.isRevoked }
                .map { it.id }.filter { it.isNotBlank() }.toSet()) }
                .getOrDefault(emptyList())
        }
    }

    BackHandler(enabled = true) {
        when (state.value.phase) {
            AirCatPhase.HUB -> onClose()
            AirCatPhase.TRANSFER -> controller.dispatch(AirCatIntent.Back)
            AirCatPhase.RESULT -> controller.dispatch(AirCatIntent.DismissResult)
            else -> controller.dispatch(AirCatIntent.Back)
        }
    }

    AirCatExperienceContent(
        state = state.value,
        onIntent = controller::dispatch,
        modifier = modifier,
        onClose = onClose,
        onSearchDevices = onSearchDevices,
        onManageHome = onManageHome,
        recoveries = recoveries,
        onRepairSource = onRepairSource,
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
private fun UnifiedDevice.toSourceUi(
    store: AirCatHomeEnrollmentStore, observed: Boolean,
): AirCatSourceUi {
    val connected = connectionState == SyncConnectionState.CONNECTED
    // Home anchors are stored under the verified Michi identity, never under the
    // registry/discovery id. Keep `deviceId` for coordinator actions, and use the
    // canonical id only for the encrypted trust lookup.
    val canonical = canonicalMichiId?.takeIf { it.isNotBlank() }
    val enrolledResult = canonical?.let { runCatching { store.anchorForSource(it) } }
    val enrolled = enrolledResult?.getOrNull() != null
    val advertised = runCatching { AirCatCapabilityPolicy.hasFeature(features) }.getOrDefault(false)
    val trustFailure = enrolledResult?.isFailure == true
    return AirCatSourceUi(
        deviceId = id,
        name = name.ifBlank { "Equipo Michi" },
        kindLabel = deviceType.ifBlank { "Equipo Michi" },
        discovered = observed,
        known = isPaired,
        connected = connected,
        homeEnrolled = enrolled,
        airCatAdvertised = advertised,
        revoked = isRevoked,
        blockedReason = when {
            trustFailure -> "No pudimos leer la confianza Home cifrada de este equipo"
            isPaired && canonical == null -> "Este equipo no tiene una identidad Michi verificada"
            else -> null
        },
        canonicalMichiId = canonical,
    )
}
