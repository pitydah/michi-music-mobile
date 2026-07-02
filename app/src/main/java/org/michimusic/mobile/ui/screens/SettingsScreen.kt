package org.michimusic.mobile.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.ReplayGainMode

const val SETTINGS_PREFS = "michi_settings"
const val KEY_AUTO_SYNC = "auto_sync"
const val KEY_RG_MODE = "replaygain_mode"
const val KEY_RG_PREAMP_WITH = "replaygain_preamp_with"
const val KEY_RG_PREAMP_WITHOUT = "replaygain_preamp_without"
const val KEY_SERVER_URL = "server_url"

@Composable
fun SettingsScreen(
    onNavigateToDiagnostics: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }
    var autoSync by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_SYNC, false)) }
    var serverUrl by remember { mutableStateOf(prefs.getString(KEY_SERVER_URL, "") ?: "") }
    var selectedMode by remember {
        mutableStateOf(
            runCatching {
                ReplayGainMode.valueOf(
                    prefs.getString(KEY_RG_MODE, ReplayGainMode.OFF.name) ?: ReplayGainMode.OFF.name,
                )
            }.getOrDefault(ReplayGainMode.OFF),
        )
    }
    val preAmpWith = remember { mutableFloatStateOf(prefs.getFloat(KEY_RG_PREAMP_WITH, 0f)) }
    val preAmpWithout = remember { mutableFloatStateOf(prefs.getFloat(KEY_RG_PREAMP_WITHOUT, 0f)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Ajustes",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
        )
        Text(
            text = "Conexión, sincronización y reproducción",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "Servidor", subtitle = "Michi Link") {
            Text(
                text = "La detección automática por UDP busca Michi Music Player en tu red local.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    prefs.edit { putString(KEY_SERVER_URL, it) }
                },
                label = { Text("URL manual") },
                placeholder = { Text("http://192.168.1.100:53318") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPink,
                    unfocusedBorderColor = SurfaceElevated,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentPink,
                ),
            )
        }

        SettingsSection(title = "Sincronización", subtitle = if (autoSync) "Automática activa" else "Manual") {
            SettingSwitchRow(
                title = "Sincronización automática",
                subtitle = "Sincronizar la biblioteca al conectar",
                checked = autoSync,
                onCheckedChange = {
                    autoSync = it
                    prefs.edit { putBoolean(KEY_AUTO_SYNC, it) }
                },
            )
        }

        SettingsSection(title = "ReplayGain", subtitle = replayGainLabel(selectedMode)) {
            Text(
                text = "Modo",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Column(Modifier.selectableGroup()) {
                ReplayGainMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .selectable(
                                selected = selectedMode == mode,
                                onClick = {
                                    selectedMode = mode
                                    prefs.edit { putString(KEY_RG_MODE, mode.name) }
                                },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedMode == mode, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = replayGainLabel(mode),
                            color = if (selectedMode == mode) TextPrimary else TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            PreAmpSlider(
                label = "Con etiquetas RG",
                value = preAmpWith.floatValue,
                onValueChange = { preAmpWith.floatValue = it },
                onCommit = { prefs.edit { putFloat(KEY_RG_PREAMP_WITH, preAmpWith.floatValue) } },
            )
            PreAmpSlider(
                label = "Sin etiquetas RG",
                value = preAmpWithout.floatValue,
                onValueChange = { preAmpWithout.floatValue = it },
                onCommit = { prefs.edit { putFloat(KEY_RG_PREAMP_WITHOUT, preAmpWithout.floatValue) } },
            )
        }

        SettingsSection(title = "Información", subtitle = "0.1.0-alpha") {
            InfoRow("App", "Michi Music Mobile")
            InfoRow("Licencia", "GPL-3.0")
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onNavigateToDiagnostics,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentPink,
                    contentColor = SurfaceDark,
                ),
            ) {
                Text("Diagnóstico Michi Link")
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentPink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PreAmpSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.9f),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onCommit,
            valueRange = -12f..12f,
            steps = 48,
            modifier = Modifier.weight(1.4f),
        )
        Text(
            "%.1f".format(value),
            color = TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

private fun replayGainLabel(mode: ReplayGainMode): String = when (mode) {
    ReplayGainMode.OFF -> "Desactivado"
    ReplayGainMode.TRACK -> "Pista"
    ReplayGainMode.ALBUM -> "Álbum"
    ReplayGainMode.DYNAMIC -> "Dinámico"
}
