package org.michimusic.mobile.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary

private sealed class AudioRoute {
    data object InternalSpeaker : AudioRoute()
    data object UsbDac : AudioRoute()
    data class Bluetooth(val codec: String = "") : AudioRoute()
    data object Unknown : AudioRoute()
}

@Composable
fun AudioRouteScreen() {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } ?: return
    var route by remember { mutableStateOf(detectRoute(audioManager)) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                route = detectRoute(audioManager)
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction("android.media.action.HDMI_AUDIO_PLUG")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
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
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Salida actual", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (route) {
                        is AudioRoute.InternalSpeaker -> "Altavoz interno"
                        is AudioRoute.UsbDac -> "USB DAC"
                        is AudioRoute.Bluetooth -> "Bluetooth (${(route as AudioRoute.Bluetooth).codec})"
                        is AudioRoute.Unknown -> "Desconocido"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = AccentPink,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("Detalles técnicos", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
        Spacer(Modifier.height(8.dp))

        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        devices.forEach { device ->
            val deviceName = device.productName?.toString() ?: "Desconocido"
            val typeName = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Altavoz interno"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Auriculares con cable"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Auriculares con cable"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB DAC"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_DOCK -> "Dock"
                else -> "Tipo ${device.type}"
            }
            Text(
                text = "$deviceName ($typeName)",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Consejos",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (route) {
                is AudioRoute.UsbDac -> "USB DAC detectado. La reproducción es bit-perfect si tu DAC lo soporta."
                is AudioRoute.Bluetooth -> "Bluetooth activo. Para mejor calidad, usa un codec LDAC o aptX HD."
                is AudioRoute.InternalSpeaker -> "Usando altavoz interno. Conecta auriculares o un DAC USB para mejor experiencia."
                is AudioRoute.Unknown -> "No se pudo detectar la salida de audio."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}

private fun detectRoute(audioManager: AudioManager): AudioRoute {
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    return when {
        devices.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_DOCK } ->
            AudioRoute.UsbDac
        devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> {
            val btDevice = devices.first { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            AudioRoute.Bluetooth(codec = btDevice.productName?.toString() ?: "A2DP")
        }
        devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES } ->
            AudioRoute.InternalSpeaker
        else -> AudioRoute.InternalSpeaker
    }
}
