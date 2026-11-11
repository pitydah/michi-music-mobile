package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.michimusic.core.models.AudioProcessingMode
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.AudioModeManager
import org.michimusic.player.UsbDacManager

/** The existing singleton is the only DSP-mode authority: no duplicate preference. */
@Composable
internal fun SettingsProcessingPanel(onOpenEqualizer: () -> Unit) {
    val audioModes: AudioModeManager = koinInject()
    val selected by audioModes.currentMode.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().testTag("settings_processing_panel"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AeroSurface(role = AeroSurfaceRole.Raised, finish = AeroFinish.Anodized) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Modo de procesamiento", style = MichiTypography.cardTitle, color = PureWhite)
                Text("Elige cómo se solicitan los efectos de audio. La ruta activa puede limitar su aplicación.",
                    style = MichiTypography.metadata, color = TextSecondary)
                Column(Modifier.selectableGroup()) {
                    AudioProcessingMode.entries.forEach { mode ->
                        val title = when (mode) {
                            AudioProcessingMode.AUTO -> "Automático"
                            AudioProcessingMode.TRANSPARENT -> "Sin efectos"
                            AudioProcessingMode.DSP -> "DSP"
                        }
                        val detail = when (mode) {
                            AudioProcessingMode.AUTO -> "Adapta el bypass según la ruta observada."
                            AudioProcessingMode.TRANSPARENT -> "Omite efectos; no significa bit-perfect."
                            AudioProcessingMode.DSP -> "Solicita los efectos configurados si son compatibles."
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
                                .testTag("settings_audio_mode_${mode.name.lowercase()}")
                                .selectable(selected = selected == mode, role = Role.RadioButton,
                                    onClick = { audioModes.setMode(mode) }),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = selected == mode, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MichiTypography.cardTitle, color = PureWhite)
                                Text(detail, style = MichiTypography.metadata, color = TextSecondary)
                            }
                        }
                    }
                }
                val selectedLabel = when (selected) {
                    AudioProcessingMode.AUTO -> "Automático"
                    AudioProcessingMode.TRANSPARENT -> "Sin efectos"
                    AudioProcessingMode.DSP -> "DSP"
                }
                Text("Configurado: $selectedLabel. No demuestra procesamiento confirmado en el DAC.",
                    style = MichiTypography.metadata, color = TextSecondary)
            }
        }
        SettingsCategoryNavCard(
            icon = Icons.Default.GraphicEq,
            iconRes = org.michimusic.mobile.R.drawable.wizard_audio_waveform,
            title = "Ecualizador y perfiles de auriculares",
            subtitle = "EQ Android, presets y PEQ de seis filtros: controles reales del motor",
            accent = PrimaryPinkContainer,
            onClick = onOpenEqualizer,
            testTag = "settings_open_equalizer",
        )
        Text("Las capacidades y la aplicación de los efectos dependen del motor y del dispositivo activo.",
            style = MichiTypography.metadata, color = TextSecondary)
    }
}

/** Route selection stays within the existing AudioRouteDialog and USB mixer owner. */
@Composable
internal fun SettingsOutputPanel(onOpenRoute: () -> Unit) {
    val manager: UsbDacManager = koinInject()
    val route by manager.routeStatus.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().testTag("settings_audio_output_panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(settingsAudioRouteSummary(route),
            style = MichiTypography.metadata, color = TextSecondary,
            modifier = Modifier.fillMaxWidth().testTag("settings_audio_route_status"))
        SettingsCategoryNavCard(
            icon = Icons.Default.Headphones,
            iconRes = org.michimusic.mobile.R.drawable.wizard_fluent_headphones,
            title = "Elegir salida de audio",
            subtitle = "Abrir salidas disponibles, DAC USB y preferencias de mezclador Android",
            accent = PrimaryPinkContainer,
            onClick = onOpenRoute,
            testTag = "settings_open_audio_route",
        )
        Text("Una frecuencia preferida del mezclador USB no certifica el reloj físico " +
            "del dispositivo. Consulta el estado observado en el selector de salida.",
            style = MichiTypography.metadata, color = TextSecondary)
    }
}
