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
import org.michimusic.mobile.ui.components.PremiumScreen
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary

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

    val route = remember(systemDevices, routeStatus) {
        resolveHonestRoute(systemDevices, routeStatus)
    }

    PremiumScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Ruta de audio",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(16.dp))

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AccentCoral.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(routeIcon(route), contentDescription = null, tint = AccentCoral, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Salida actual", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = routeName(route),
                            style = MaterialTheme.typography.bodyLarge,
                            color = AccentCoral,
                        )
                        val statusLabel = when (routeStatus) {
                            is org.michimusic.player.AudioRouteStatus.Confirmed -> "Ruta confirmada en pipeline"
                            is org.michimusic.player.AudioRouteStatus.Requested -> "Salida solicitada (esperando uso en AudioTrack)"
                            is org.michimusic.player.AudioRouteStatus.Detected -> "Dispositivo detectado (sin confirmación de reproducción)"
                            is org.michimusic.player.AudioRouteStatus.Failed -> "Error en enrutamiento"
                            is org.michimusic.player.AudioRouteStatus.Unknown -> "Estado no verificable por API"
                            org.michimusic.player.AudioRouteStatus.NoDevice -> "Sin dispositivo externo"
                        }
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("Dispositivos de audio observados", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
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
                    "Frecuencias declaradas: " + device.sampleRates.joinToString(", ") { "${it / 1000.0} kHz" }
                } else {
                    "Frecuencias: Según mixer de Android"
                }

                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(4.dp)) {
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
                text = when (route) {
                    is AudioRoute.UsbDac -> "Dispositivo USB detectado. La ruta efectiva y la tasa de muestreo final dependen de AudioTrack y del mezclador de Android; la aplicación no declara bit-perfect salvo confirmación explícita."
                    is AudioRoute.UsbHeadset -> "Auriculares USB conectados vía interfaz digital estándar."
                    is AudioRoute.Bluetooth -> "Conexión Bluetooth activa (${route.codec}). Sujeto a compresión con pérdidas según el códec negociado."
                    is AudioRoute.InternalSpeaker -> "Reproduciendo por altavoces o salida cableada del dispositivo."
                    is AudioRoute.Unknown -> "No se pudo determinar el enrutamiento físico activo."
                },
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

private fun resolveHonestRoute(
    devices: List<AudioDeviceInfo>,
    routeStatus: org.michimusic.player.AudioRouteStatus,
): AudioRoute {
    if (routeStatus is org.michimusic.player.AudioRouteStatus.Confirmed) {
        val dev = routeStatus.device
        return when (dev.androidType) {
            AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.UsbHeadset
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_DOCK -> AudioRoute.UsbDac
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRoute.Bluetooth(codec = dev.name)
            else -> AudioRoute.InternalSpeaker
        }
    }

    return when {
        devices.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY || it.type == AudioDeviceInfo.TYPE_DOCK } ->
            AudioRoute.UsbDac
        devices.any { it.type == AudioDeviceInfo.TYPE_USB_HEADSET } ->
            AudioRoute.UsbHeadset
        devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> {
            val bt = devices.first { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            AudioRoute.Bluetooth(codec = bt.productName?.toString() ?: "A2DP")
        }
        devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES } ->
            AudioRoute.InternalSpeaker
        else -> AudioRoute.InternalSpeaker
    }
}
