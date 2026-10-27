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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.audioRouteEvidence
import org.michimusic.mobile.ui.components.sampleRateLabel
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
fun AudioRouteScreen() {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } ?: return
    val usbDacManager: org.michimusic.player.UsbDacManager? = runCatching {
        org.koin.compose.koinInject<org.michimusic.player.UsbDacManager>()
    }.getOrNull()
    val routeStatus by (usbDacManager?.routeStatus?.collectAsState() ?: remember {
        mutableStateOf(org.michimusic.player.AudioRouteStatus.NoDevice)
    })
    val dacInfo by (usbDacManager?.dacState?.collectAsState() ?: remember {
        mutableStateOf(org.michimusic.player.UsbDacInfo())
    })

    val outputDevices by (usbDacManager?.outputDevices?.collectAsState() ?: remember {
        mutableStateOf(emptyList())
    })

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
            Text(text = "SIGNAL TRUTH", style = MichiTypography.screenEyebrow)
            Text(
                text = "Ruta de audio",
                style = MichiTypography.screenTitle,
                color = TextPrimary,
            )
            Spacer(Modifier.height(16.dp))

            GlassCard(modifier = Modifier.fillMaxWidth()) {
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
                        Icon(routeIcon(route), contentDescription = null, tint = org.michimusic.mobile.ui.theme.PrimaryPinkContainer, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            evidence.headline,
                            style = MaterialTheme.typography.titleMedium, color = TextPrimary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = evidence.deviceLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = org.michimusic.mobile.ui.theme.OnSurfaceVariant,
                        )
                        Text(
                            text = evidence.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("Dispositivos disponibles (no necesariamente activos)", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            Spacer(Modifier.height(8.dp))

            systemDevices.forEach { device ->
                val deviceName = device.productName?.toString().takeIf { !it.isNullOrBlank() } ?: "Dispositivo ${device.id}"
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

                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(deviceName, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text(typeName, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text(sampleRatesStr, style = MaterialTheme.typography.labelSmall, color = TextSecondary.copy(alpha = 0.7f))
                    }
                }
                Spacer(Modifier.height(8.dp))
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
