package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/** Only the canonical visual preferences Flow owns accessibility settings. */
@Composable
internal fun SettingsAccessibilityPanel() {
    val visualPreferencesRepo: org.michimusic.mobile.ui.theme.VisualPreferencesRepository = koinInject()
    val visualPrefs by visualPreferencesRepo.preferences.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Cambios inmediatos · sin reiniciar ni crear perfiles extra",
            color = TextSecondary, modifier = Modifier)
        SettingsSectionCard(
            icon = Icons.Default.AccessibilityNew,
            title = "Lectura y movimiento",
            subtitle = "Contraste y animaciones en toda la aplicación",
            accent = TertiaryCyan,
        ) {
            SettingSwitchRow(
                title = "Alto contraste",
                subtitle = "Resaltar bordes y texto para mayor legibilidad",
                checked = visualPrefs.highContrast,
                onCheckedChange = { visualPreferencesRepo.setHighContrast(it) },
                testTag = "settings_high_contrast",
            )

            Spacer(Modifier.height(12.dp))

            SettingSwitchRow(
                title = "Reducir animaciones",
                subtitle = "Disminuir transiciones y movimiento en pantallas",
                checked = visualPrefs.reducedMotion,
                onCheckedChange = { visualPreferencesRepo.setReducedMotion(it) },
                testTag = "settings_reduced_motion",
            )

        }
        SettingsSectionCard(
            icon = Icons.Default.AccessibilityNew,
            title = "Respuesta táctil",
            subtitle = "Solo la vibración de las interacciones compatibles",
            accent = TertiaryCyan,
        ) {
            SettingSwitchRow(
                title = "Vibración táctil sutil (Aero)",
                subtitle = "Respuesta háptica precisa al pulsar botones y controles de reproducción",
                checked = visualPrefs.hapticsEnabled,
                onCheckedChange = { visualPreferencesRepo.setHapticsEnabled(it) },
                testTag = "settings_haptics",
            )
        }
    }
}
