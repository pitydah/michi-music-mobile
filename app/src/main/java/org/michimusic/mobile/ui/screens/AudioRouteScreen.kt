package org.michimusic.mobile.ui.screens

import android.content.Context
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
import androidx.compose.runtime.remember
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
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val route = remember {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        when {
            devices.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_DOCK } ->
                AudioRoute.UsbDac
            devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> {
                val btDevice = devices.first { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                val productName = btDevice.productName?.toString() ?: ""
                AudioRoute.Bluetooth(codec = productName)
            }
            devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ->
                AudioRoute.InternalSpeaker
            else -> AudioRoute.Unknown
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Ruta de Audio",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
        )

        Spacer(Modifier.height(24.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Salida Activa",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )

                Spacer(Modifier.height(12.dp))

                when (route) {
                    is AudioRoute.UsbDac -> {
                        Text(
                            text = "USB DAC",
                            style = MaterialTheme.typography.headlineSmall,
                            color = AccentPink,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Ruta Bit-Perfect — El audio se envía sin resampling ni post-procesamiento. Calidad máxima de reproducción.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                    is AudioRoute.Bluetooth -> {
                        Text(
                            text = "Bluetooth A2DP",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Recomendación: Activa LDAC a 990kbps en Opciones de Desarrollador de Android para mejor calidad inalámbrica.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        if (route.codec.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Dispositivo: ${route.codec}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextDim,
                            )
                        }
                    }
                    is AudioRoute.InternalSpeaker -> {
                        Text(
                            text = "Altavoz Interno",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Reproduciendo por los altavoces del dispositivo. Conecta un DAC USB o auriculares Bluetooth para mejor calidad.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                    is AudioRoute.Unknown -> {
                        Text(
                            text = "Desconocido",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextDim,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No se pudo determinar la salida de audio actual.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Información Técnica",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))

        val allDevices = remember {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }
        allDevices.forEach { device ->
            val typeName = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Altavoz Interno"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_DOCK -> "USB DAC"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Auriculares Cableados"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Auriculares Cableados"
                AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
                AudioDeviceInfo.TYPE_DOCK -> "Dock"
                else -> "Tipo ${device.type}"
            }
            Text(
                text = "$typeName — ${device.productName}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}
