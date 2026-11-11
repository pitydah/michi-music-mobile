package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.TertiaryCyan


import org.michimusic.mobile.ui.theme.SecondaryPurple




/** Only navigates to existing diagnostic/device/onboarding authorities. */
@Composable
internal fun SettingsSystemPanel(
    onNavigateToDiagnostics: () -> Unit,
    onReopenOnboarding: () -> Unit,
    onNavigateToAudioRoute: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onboardingSnapshot: org.michimusic.mobile.onboarding.OnboardingSnapshot?,
) {
    val reminders = settingsWizardReminders(
        usbReminder = onboardingSnapshot?.audio ==
            org.michimusic.mobile.onboarding.AudioChoice.USB_REMINDER,
        discoverLater = onboardingSnapshot?.discoverLater == true,
        linkLater = onboardingSnapshot?.linkLater == true,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsCategoryNavCard(
            icon = Icons.Default.BugReport,
            title = "Diagnóstico del sistema",
            subtitle = "Abrir herramientas existentes de diagnóstico y estado técnico",
            accent = TertiaryCyan,
            onClick = onNavigateToDiagnostics,
            testTag = "settings_open_diagnostics",
        )
        SettingsCategoryNavCard(
            icon = Icons.Default.Info,
            iconRes = org.michimusic.mobile.R.drawable.wizard_fluent_history,
            title = "Repetir configuración inicial",
            subtitle = "Revisar el asistente sin eliminar música, archivos ni emparejamientos",
            accent = PrimaryPink,
            onClick = onReopenOnboarding,
            testTag = "settings_reopen_wizard",
        )
        if (reminders.showUsb) {
            SettingsCategoryNavCard(
                icon = Icons.Default.Headphones,
                iconRes = org.michimusic.mobile.R.drawable.wizard_audio_waveform,
                title = "Revisar salida USB",
                subtitle = "Retomar la preferencia de DAC desde el asistente inicial",
                accent = TertiaryCyan,
                onClick = onNavigateToAudioRoute,
                testTag = "settings_usb_reminder",
            )
        }
        if (reminders.showDevices) {
            SettingsCategoryNavCard(
                icon = Icons.Default.Info,
                iconRes = org.michimusic.mobile.R.drawable.wizard_fluent_phone_laptop,
                title = "Completar configuración del ecosistema",
                subtitle = "Continuar en Dispositivos cuando lo decidas",
                accent = SecondaryPurple,
                onClick = onNavigateToDevices,
            )
        }
    }
}
