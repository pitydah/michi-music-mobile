package org.michimusic.mobile.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.michimusic.mobile.playback.usbMixerControlAvailability
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.player.AudioRouteStatus
import org.michimusic.player.UsbDacManager

/** Configures only the Android default USB mixer preference, not DAC firmware. */
@Composable
fun UsbDacMixerControls(
    manager: UsbDacManager,
    routeStatus: AudioRouteStatus,
    modifier: Modifier = Modifier,
) {
    val mixer by manager.mixerState.collectAsStateWithLifecycle()
    val availability = usbMixerControlAvailability(Build.VERSION.SDK_INT, routeStatus, mixer)
    AeroSurface(role = AeroSurfaceRole.Content, finish = AeroFinish.Plain,
        modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DAC USB · mezclador Android", style = MaterialTheme.typography.titleMedium)
            Text(availability.explanation, style = MaterialTheme.typography.bodySmall)
            Text("Preferencia solicitada: " +
                (mixer.requestedRate?.let { sampleRateLabel(it) } ?: "Automático"),
                style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { manager.requestMixerRate(null) },
                enabled = availability.editable && mixer.requestedRate != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Usar selección automática de Android") }
            mixer.supportedRates.forEach { rate ->
                OutlinedButton(
                    onClick = { manager.requestMixerRate(rate) },
                    enabled = availability.editable && mixer.requestedRate != rate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Solicitar " + (sampleRateLabel(rate) ?: "$rate Hz") +
                        if (mixer.requestedRate == rate) " · preferido" else "")
                }
            }
            if (mixer.message.isNotBlank()) {
                Text(mixer.message, style = MaterialTheme.typography.bodySmall)
            }
            Text("La preferencia puede requerir recrear la salida. " +
                "AudioTrack y el DAC físico no están medidos por este control.",
                style = MaterialTheme.typography.bodySmall)
            Text("Volumen HID, ganancia analógica, filtros del chip, DSD/DoP, MQA " +
                "y modo exclusivo requieren soporte/driver verificado del hardware. " +
                "No se exponen como interruptores genéricos.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
