package org.michimusic.mobile.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.components.audioRouteEvidence
import org.michimusic.mobile.ui.components.isObservedOutputInUse
import org.michimusic.mobile.ui.components.usbDacRouteLabel
import org.michimusic.mobile.ui.components.STREAM_PROVISIONING_GUIDANCE
import org.michimusic.mobile.ui.components.sampleRateLabel
import org.michimusic.mobile.ui.motion.AeroObservedText
import org.michimusic.mobile.ui.components.UsbDacMixerControls
import org.michimusic.mobile.ui.motion.AeroObservedRouteIcon
import org.michimusic.mobile.ui.motion.AeroRouteCertaintyLabel
import org.michimusic.player.AudioRouteStatus
import org.michimusic.mobile.ui.components.PremiumScreen
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised

private sealed class AudioRoute {
    data object InternalSpeaker : AudioRoute()
    data object UsbDac : AudioRoute()
    data object UsbHeadset : AudioRoute()
    data class Bluetooth(val codec: String = "") : AudioRoute()
    data object Unknown : AudioRoute()
}

@Composable
fun AudioRouteScreen(
    onBack: (() -> Unit)? = null,
    onNavigateToDevices: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } ?: return
    val usbDacManager: org.michimusic.player.UsbDacManager? = runCatching {
        org.koin.compose.koinInject<org.michimusic.player.UsbDacManager>()
    }.getOrNull()
    val routeStatus by (usbDacManager?.routeStatus?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(org.michimusic.player.AudioRouteStatus.NoDevice)
    })
    val dacInfo by (usbDacManager?.dacState?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(org.michimusic.player.UsbDacInfo())
    })

    val outputDevices by (usbDacManager?.outputDevices?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(emptyList())
    })
    val selectedUsbId by (usbDacManager?.selectedDeviceId?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf<Int?>(null)
    })

    var pendingUsbConfirmation by remember { mutableStateOf<UsbPhysicalDescriptor?>(null) }
    var systemDevices by remember {
        mutableStateOf(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList())
    }

    DisposableEffect(audioManager) {
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                systemDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                systemDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose {
            audioManager.unregisterAudioDeviceCallback(callback)
        }
    }

    val evidence = remember(routeStatus) { audioRouteEvidence(routeStatus) }
    // A device being present is not proof of an effective route. Only tracked
    // AudioTrack evidence is shown as the effective route in the top card.
    val activeDeviceType = when (val active = routeStatus) {
        is AudioRouteStatus.Confirmed -> active.device.androidType
        is AudioRouteStatus.RoutedElsewhere -> active.actual.androidType
        else -> null
    }
    val route = routeFromConfirmedType(activeDeviceType)

    PremiumScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(MichiSpacing.screenHorizontal)
                .verticalScroll(rememberScrollState()),
        ) {
            if (onBack != null) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("audio_route_back"),
                ) { Text("Volver") }
            }
            Text(text = "SIGNAL TRUTH", style = MichiTypography.screenEyebrowTechnical)
            Text(
                text = "Ruta de audio",
                style = MichiTypography.screenTitle,
                color = TextPrimary,
            )
            Spacer(Modifier.height(16.dp))
            val preferredPresence = preferredPhysicalUsbPresence(
                selectedUsbId,
                systemDevices.associate { it.id to it.type },
                outputDevices.filter { it.isUsbDac || it.isUsbHeadset }.map { it.id }.toSet(),
            ) { type -> type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET) }
            preferredUsbGuidance(preferredPresence)?.let { guidance ->
                Text(guidance, style = MaterialTheme.typography.bodySmall,
                    color = if (preferredPresence == PreferredUsbPresence.DISCONNECTED)
                        org.michimusic.mobile.ui.theme.PrimaryPinkContainer else TextSecondary,
                    modifier = Modifier.testTag("audio_route_preferred_usb_status"))
            }
            if (selectedUsbId != null && usbDacManager != null) {
                TextButton(
                    onClick = { usbDacManager.clearDeviceSelection() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("audio_route_automatic"),
                ) { Text("Volver a ruta automática") }
                Text("Esto cambia una preferencia; la ruta efectiva debe verificarse otra vez.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            if (systemDevices.isEmpty()) {
                Text("Android no comunica salidas en este momento.", color = TextSecondary)
            }

            // Observed route receives material emphasis; it is not a bit-perfect claim.
            AeroSurface(role = AeroSurfaceRole.Raised, finish = AeroFinish.Anodized,
                modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AeroGraphiteRaised),
                        contentAlignment = Alignment.Center,
                    ) {
                        AeroObservedRouteIcon(
                            observationKey = evidence.headline + evidence.deviceLabel,
                            icon = routeIcon(route),
                            tint = org.michimusic.mobile.ui.theme.PrimaryPinkContainer,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        AeroObservedText(
                            value = evidence.headline,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                        )
                        Spacer(Modifier.height(4.dp))
                        AeroObservedText(
                            value = evidence.deviceLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = org.michimusic.mobile.ui.theme.OnSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                        )
                        AeroObservedText(
                            value = evidence.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                            maxLines = 4,
                        )
                        AeroRouteCertaintyLabel(
                            status = routeStatus,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Real Android 14+ mixer controls are confined to a confirmed USB
            // AudioTrack route; the UI cannot certify physical DAC delivery.
            usbDacManager?.let { manager ->
                UsbDacMixerControls(manager = manager, routeStatus = routeStatus)
                Spacer(Modifier.height(16.dp))
            }

            Text("Dispositivos disponibles (no necesariamente activos)", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            Spacer(Modifier.height(8.dp))

            systemDevices.forEach { device ->
                val usbType = device.type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET)
                val canSelect = canRequestPhysicalUsbOutput(
                    deviceId = device.id,
                    isUsbType = usbType,
                    managerPresent = usbDacManager != null,
                    physicalOutputIds = systemDevices.map { it.id }.toSet(),
                    managedUsbOutputIds = outputDevices.filter { it.isUsbDac || it.isUsbHeadset }
                        .map { it.id }.toSet(),
                )
                val observedHere = isObservedOutputInUse(routeStatus, device.id)
                val requestedHere = (routeStatus as? AudioRouteStatus.Requested)
                    ?.device?.androidDeviceId == device.id
                val deviceName = device.productName?.toString().takeIf { !it.isNullOrBlank() } ?: "Salida disponible"
                val typeName = when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Altavoz integrado"
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Auricular telefónico integrado"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Auriculares con cable (con micro)"
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Auriculares con cable (estéreo)"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                    AudioDeviceInfo.TYPE_USB_DEVICE -> "Dispositivo USB (DAC / Interfaz)"
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "Auriculares USB"
                    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "Accesorio USB Audio"
                    AudioDeviceInfo.TYPE_DOCK -> "Base / Dock de audio"
                    else -> "Tipo ${device.type}"
                }
                val sampleRatesStr = if (device.sampleRates.isNotEmpty()) {
                    "Frecuencias anunciadas: " + device.sampleRates.asSequence().distinct().sorted()
                        .mapNotNull { sampleRateLabel(it) }.take(12).joinToString(", ").ifBlank { "No disponibles" }
                } else {
                    "Frecuencias: Según mixer de Android"
                }

                // Only a real USB output can request a preferred route. Bluetooth and
                // speakers remain informational. A request is never labelled confirmed.
                AeroSurface(
                    role = AeroSurfaceRole.Content,
                    finish = if (observedHere) AeroFinish.Satin else AeroFinish.Plain,
                    modifier = Modifier.fillMaxWidth().testTag("audio_route_output_${device.id}"),
                    onClick = if (canSelect) ({
                        // Recheck BOTH device inventories at tap time: hot-plug events can
                        // invalidate the composition's cached list before the next frame.
                        val liveDevices = runCatching {
                            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
                        }.getOrDefault(emptyList())
                        val liveTypes = liveDevices.associate { it.id to it.type }
                        val displayed = UsbPhysicalDescriptor(
                            device.id, device.type, device.address.orEmpty(),
                            device.productName?.toString().orEmpty(),
                        )
                        val live = liveDevices.firstOrNull { it.id == device.id }?.let {
                            UsbPhysicalDescriptor(it.id, it.type, it.address.orEmpty(),
                                it.productName?.toString().orEmpty())
                        }
                        val managed = usbDacManager?.outputDevices?.value.orEmpty()
                            .filter { it.isUsbDac || it.isUsbHeadset }.map { it.id }.toSet()
                        // Android may recycle a numeric ID after hot unplug.
                        // Never select a different physical type from the displayed row.
                        if (usbDacManager != null && canRequestSamePhysicalUsbOutput(
                                device.id, device.type, liveTypes, managed,
                                isUsbAudioType = { type -> type in setOf(
                                    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
                                    AudioDeviceInfo.TYPE_USB_HEADSET) },
                            )) {
                            if (matchingUsbDescriptor(displayed, live)) {
                                if (usbNeedsConfirmation(displayed)) pendingUsbConfirmation = displayed
                                else usbDacManager.selectDevice(device.id)
                            }
                        }
                    }) else null,
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(deviceName, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text(typeName, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        if (usbType) Text(usbDacRouteLabel(routeStatus, device.id),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (observedHere) org.michimusic.mobile.ui.theme.PrimaryPinkContainer else TextSecondary)
                        Text(audioRouteSelectionHint(canSelect, observedHere, requestedHere,
                            preferredHere = selectedUsbId == device.id),
                            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text(sampleRatesStr, style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary.copy(alpha = 0.7f))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))
            AeroSurface(role = AeroSurfaceRole.Raised, finish = AeroFinish.Satin,
                modifier = Modifier.fillMaxWidth().testTag("audio_route_stream_guidance")) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Michi Music Stream", style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary)
                    Text(STREAM_PROVISIONING_GUIDANCE,
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Una salida de red se gestiona en Dispositivos, no como DAC USB del teléfono.",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    if (onNavigateToDevices != null) {
                        TextButton(
                            onClick = onNavigateToDevices,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("audio_route_open_devices"),
                        ) { Text("Abrir Dispositivos") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = TextSecondary.copy(alpha = 0.18f))
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Observación de señal y fidelidad",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Las tasas anunciadas por un dispositivo no son medidas de la señal física. " +
                    "La aplicación no certifica salida bit-perfect ni la frecuencia que recibe un DAC.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }

        pendingUsbConfirmation?.let { candidate ->
            AlertDialog(
                onDismissRequest = { pendingUsbConfirmation = null },
                title = { Text("Confirmar salida USB") },
                text = { Text("Android no entrega una dirección estable para esta salida. " +
                    "Verifica el DAC conectado: ${candidate.product.ifBlank { "USB Audio" }}. " +
                    "Esto solicita la preferencia, no confirma la ruta de AudioTrack.") },
                confirmButton = {
                    TextButton(onClick = {
                        val live = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                            .firstOrNull { it.id == candidate.id } }.getOrNull()
                        val liveDesc = live?.let { UsbPhysicalDescriptor(it.id, it.type,
                            it.address.orEmpty(), it.productName?.toString().orEmpty()) }
                        val managed = usbDacManager?.outputDevices?.value.orEmpty()
                            .any { it.id == candidate.id && (it.isUsbDac || it.isUsbHeadset) }
                        if (managed && matchingUsbDescriptor(candidate, liveDesc)) {
                            usbDacManager?.selectDevice(candidate.id)
                        }
                        pendingUsbConfirmation = null
                    }, modifier = Modifier.testTag("audio_route_confirm_usb")) { Text("Solicitar salida") }
                },
                dismissButton = { TextButton(onClick = { pendingUsbConfirmation = null }) {
                    Text("Cancelar")
                } },
            )
        }
}

private fun routeName(route: AudioRoute): String = when (route) {
    is AudioRoute.InternalSpeaker -> "Altavoz / Salida estándar"
    is AudioRoute.UsbDac -> "Dispositivo USB (DAC / Interfaz)"
    is AudioRoute.UsbHeadset -> "Auriculares USB"
    is AudioRoute.Bluetooth -> "Bluetooth (${route.codec})"
    is AudioRoute.Unknown -> "Salida no verificada"
}

private fun routeIcon(route: AudioRoute) = when (route) {
    is AudioRoute.InternalSpeaker -> Icons.Rounded.Speaker
    is AudioRoute.UsbDac -> Icons.Rounded.Usb
    is AudioRoute.UsbHeadset -> Icons.Rounded.Headphones
    is AudioRoute.Bluetooth -> Icons.Rounded.BluetoothAudio
    is AudioRoute.Unknown -> Icons.Rounded.Headphones
}

/** Never infer the active audio route from available system devices. */
private fun routeFromConfirmedType(type: Int?): AudioRoute = when (type) {
    AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.UsbHeadset
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_DOCK -> AudioRoute.UsbDac
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRoute.Bluetooth("A2DP")
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioRoute.InternalSpeaker
    else -> AudioRoute.Unknown
}
