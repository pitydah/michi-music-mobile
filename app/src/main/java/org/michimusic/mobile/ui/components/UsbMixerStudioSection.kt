package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.player.UsbDacManager

/** Android 14+ USB mixer preferences, never a physical sample-rate certification. */
@Composable
fun UsbMixerStudioSection() {
    val manager: UsbDacManager = koinInject()
    val mixer by manager.mixerState.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().testTag("usb_mixer_studio"),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("MEZCLADOR USB", style = MichiTypography.screenEyebrow, color = PrimaryPinkContainer)
        Text(
            "Configuración preferida de Android; no demuestra el reloj físico del DAC.",
            style = MichiTypography.microLabel,
            color = OnSurfaceVariant,
        )
        if (mixer.supportedRates.isEmpty()) {
            Text(
                mixer.message.ifEmpty { "Android no anunció modos configurables para esta salida" },
                fontSize = 11.sp,
            )
        } else {
            mixer.supportedRates.chunked(3).forEach { values ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    values.forEach { hz ->
                        // requestedRate is what Android accepted, so the surface must show it
                        // instead of leaving the user guessing which preference is active.
                        AeroSegmentedChoice(
                            label = audioRateLabel(hz),
                            selected = mixer.requestedRate == hz,
                            onClick = { manager.requestMixerRate(hz) },
                            treatment = AeroSegmentTreatment.Filter,
                            testTag = "usb_mixer_rate_$hz",
                        )
                    }
                }
            }
            AeroSegmentedChoice(
                label = "Mezclador automático",
                selected = mixer.requestedRate == null,
                onClick = { manager.requestMixerRate(null) },
                treatment = AeroSegmentTreatment.Filter,
                testTag = "usb_mixer_auto",
            )
            Text(mixer.message.ifEmpty { "Aún no hay una preferencia aceptada" }, fontSize = 11.sp)
        }
    }
}
