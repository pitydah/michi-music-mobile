package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.michimusic.core.models.AudioProcessingMode
import org.michimusic.player.AudioModeManager
import org.michimusic.player.AudioRouteStatus
import org.michimusic.player.HeadphoneProfileStore
import org.michimusic.player.PeqBand
import org.michimusic.player.UsbDacManager
import org.michimusic.player.peqHeadroomDb
import org.michimusic.player.headphonePeqEligible
import org.michimusic.player.headphoneSwitchEnabled
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import kotlin.math.log10
import kotlin.math.pow

/** Real parameter controls; changes are persisted and consumed by the Media3 PEQ stage. */
@Composable
fun HeadphoneStudioSection() {
    val store: HeadphoneProfileStore = koinInject()
    val manager: UsbDacManager = koinInject()
    val mode: AudioModeManager = koinInject()
    val state by store.state.collectAsState()
    val savedProfiles by store.savedProfiles.collectAsState()
    val route by manager.routeStatus.collectAsState()
    val audioMode by mode.currentMode.collectAsState()
    val confirmed = route as? AudioRouteStatus.Confirmed
    val device = confirmed?.device
    val isHeadphones = device?.androidType in listOf(
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
    )
    var selectedBand by remember { mutableIntStateOf(0) }
    val canApply = headphonePeqEligible(state, route, audioMode)
    val switchEnabled = headphoneSwitchEnabled(
        config = state,
        mode = audioMode,
        legacyBandsTruncated = state.legacyBandsTruncated,
        hasHeadphone = isHeadphones,
    )

    Column(Modifier.fillMaxWidth().testTag("headphone_studio"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("MICHI HEADPHONE STUDIO", style = MichiTypography.screenEyebrow, color = PrimaryPinkContainer)
        Text(
            "Personalización paramétrica del teléfono (no es una corrección acústica medida).",
            style = MichiTypography.microLabel,
            color = OnSurfaceVariant,
        )
        if (state.legacyBandsTruncated) {
            Text("Perfil anterior incompleto: no se aplica. Selecciona Neutral, Warm o Voice para repararlo de forma explícita.",
                fontSize = 12.sp, color = OnSurfaceVariant, modifier = Modifier.testTag("headphone_legacy_warning"))
        }
        OutlinedTextField(
            value = state.headphoneName,
            onValueChange = store::rename,
            label = { Text("Nombre de tus auriculares") },
            modifier = Modifier.fillMaxWidth().testTag("headphone_name"),
            singleLine = true,
        )
        Text(
            when {
                audioMode == AudioProcessingMode.TRANSPARENT -> "Sin efectos: el perfil está guardado, pero no se aplica"
                audioMode == AudioProcessingMode.AUTO -> "Modo Automático: bypass en USB. Selecciona Personalizado para aplicar el PEQ"
                device == null || !isHeadphones -> "Selecciona una salida USB o auriculares cableados y confirma su ruta"
                canApply -> "Elegible para ${device.name}; aplicación real depende de PCM local y estado del DSP"
                else -> "Perfil guardado, no aplicado. Actívalo para la salida observada"
            }, fontSize = 12.sp, color = OnSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Habilitar en esta salida", fontSize = 12.sp, color = PureWhite)
            // The switch shows the saved preference, not the current eligibility:
            // an unplugged headset must still let the user turn the profile off.
            Switch(
                checked = state.enabled,
                enabled = switchEnabled,
                onCheckedChange = { enabled -> if (enabled) store.selectRoute(device?.androidDeviceId, confirmed?.observedAtMs) else store.selectRoute(null) },
                modifier = Modifier.testTag("headphone_enable"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Neutral", "Warm", "Voice").forEach { preset ->
                // The rest of the app expresses a single choice with AeroSegmentedChoice.
                // A Material FilterChip here picked up the theme's secondary container and
                // broke the selection language inside the same studio panel.
                AeroSegmentedChoice(
                    label = preset,
                    selected = state.preset == preset,
                    onClick = { store.selectPreset(preset) },
                    treatment = AeroSegmentTreatment.Filter,
                    testTag = "headphone_preset_$preset",
                )
            }
        }
        Button(
            onClick = store::saveNamedProfile,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
            modifier = Modifier.testTag("save_headphone_profile"),
        ) {
            Text("Guardar perfil por nombre")
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            savedProfiles.forEach { saved ->
                AeroSegmentedChoice(
                    label = saved,
                    selected = state.headphoneName == saved,
                    onClick = { store.loadNamedProfile(saved) },
                    treatment = AeroSegmentTreatment.Filter,
                    testTag = "saved_profile_$saved",
                )
            }
        }
        Text("Reserva estimada de headroom: ${studioDbLabel(peqHeadroomDb(state.bands))}", fontSize = 12.sp, color = OnSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        PeqResponseCurve(bands = state.bands, referenceSampleRate = 48_000)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            state.bands.forEachIndexed { n, band ->
                // A chip that never shows its own selection left the editor without a
                // visible target, and the segmented choice also reports `selected` to TalkBack.
                AeroSegmentedChoice(
                    label = "${n + 1}: ${band.frequencyHz.toInt()}Hz",
                    selected = selectedBand == n,
                    onClick = { selectedBand = n },
                    treatment = AeroSegmentTreatment.Filter,
                    testTag = "peq_band_$n",
                )
            }
        }
        state.bands.getOrNull(selectedBand)?.let { band ->
            val index = selectedBand
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Filtro ${index + 1}: ${band.frequencyHz.toInt()} Hz · " +
                        "${studioDbLabel(band.gainDb, signed = true)} · Q ${studioRatioLabel(band.q)}",
                    fontSize = 12.sp, color = PureWhite,
                )
                Text("Frecuencia", fontSize = 12.sp, color = OnSurfaceVariant)
                MichiFineSlider(
                    value = (log10((band.frequencyHz / 20f).coerceAtLeast(1f)) / 3f).coerceIn(0f, 1f),
                    onValueChange = { t -> store.editBand(index, band.copy(frequencyHz = 20f * 1000f.pow(t))) },
                    onValueChangeFinished = store::persistCurrent,
                    modifier = Modifier.testTag("peq_frequency_$index"),
                )
                Text("Ganancia", fontSize = 12.sp, color = OnSurfaceVariant)
                MichiFineSlider(value = band.gainDb, valueRange = -12f..12f,
                    onValueChange = { store.editBand(index, band.copy(gainDb = it)) },
                    onValueChangeFinished = store::persistCurrent,
                    modifier = Modifier.testTag("peq_gain_$index"))
                Text("Q / anchura", fontSize = 12.sp, color = OnSurfaceVariant)
                MichiFineSlider(value = band.q, valueRange = 0.3f..8f,
                    onValueChange = { store.editBand(index, band.copy(q = it)) },
                    onValueChangeFinished = store::persistCurrent,
                    modifier = Modifier.testTag("peq_q_$index"))
            }
        }
    }
}
