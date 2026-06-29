package org.michimusic.mobile.ui.screens

import android.content.Context
import androidx.core.content.edit
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.theme.SurfaceDark
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

        Spacer(Modifier.height(16.dp))

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Servidor",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))

        Text(
            text = "Descubrimiento automático vía UDP Multicast activo. La app detectará a Michi Music Player automáticamente en la red local.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(12.dp))

        var serverUrl by remember { mutableStateOf(prefs.getString(KEY_SERVER_URL, "") ?: "") }
        OutlinedTextField(
            value = serverUrl,
            onValueChange = {
                serverUrl = it
                prefs.edit { putString(KEY_SERVER_URL, it) }
            },
            label = { Text("URL manual del servidor") },
            placeholder = { Text("http://192.168.1.100:53318") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Sincronización",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sincronización automática", color = TextPrimary)
                Text(
                    "Sincronizar la biblioteca al conectar",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Switch(
                checked = autoSync,
                onCheckedChange = {
                    autoSync = it
                    prefs.edit { putBoolean(KEY_AUTO_SYNC, it) }
                },
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = "ReplayGain",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))

        Text(
            text = "Modo",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(4.dp))

        val rgMode = ReplayGainMode.valueOf(
            prefs.getString(KEY_RG_MODE, ReplayGainMode.OFF.name) ?: ReplayGainMode.OFF.name
        )
        var selectedMode by remember { mutableStateOf(rgMode) }

        Column(Modifier.selectableGroup()) {
            ReplayGainMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .selectable(
                            selected = selectedMode == mode,
                            onClick = {
                                selectedMode = mode
                                prefs.edit { putString(KEY_RG_MODE, mode.name) }
                            },
                            role = Role.RadioButton,
                        )
                        .padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedMode == mode,
                        onClick = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (mode) {
                            ReplayGainMode.OFF -> "Desactivado"
                            ReplayGainMode.TRACK -> "Pista"
                            ReplayGainMode.ALBUM -> "Álbum"
                            ReplayGainMode.DYNAMIC -> "Dinámico"
                        },
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Pre-amp (dB)",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        val preAmpWith = remember {
            mutableFloatStateOf(prefs.getFloat(KEY_RG_PREAMP_WITH, 0f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Con etiquetas RG", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Slider(
                value = preAmpWith.floatValue,
                onValueChange = { preAmpWith.floatValue = it },
                onValueChangeFinished = {
                    prefs.edit().putFloat(KEY_RG_PREAMP_WITH, preAmpWith.floatValue).apply()
                },
                valueRange = -12f..12f,
                steps = 48,
                modifier = Modifier.weight(1f),
            )
            Text(
                "%.1f".format(preAmpWith.floatValue),
                color = TextPrimary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(40.dp),
            )
        }

        val preAmpWithout = remember {
            mutableFloatStateOf(prefs.getFloat(KEY_RG_PREAMP_WITHOUT, 0f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sin etiquetas RG", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Slider(
                value = preAmpWithout.floatValue,
                onValueChange = { preAmpWithout.floatValue = it },
                onValueChangeFinished = {
                    prefs.edit().putFloat(KEY_RG_PREAMP_WITHOUT, preAmpWithout.floatValue).apply()
                },
                valueRange = -12f..12f,
                steps = 48,
                modifier = Modifier.weight(1f),
            )
            Text(
                "%.1f".format(preAmpWithout.floatValue),
                color = TextPrimary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(40.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Información",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text("Michi Music Mobile", color = TextSecondary)
        Text("Versión 0.1.0-alpha", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text("GPL-3.0", style = MaterialTheme.typography.bodySmall, color = TextSecondary)

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onNavigateToDiagnostics,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Diagnóstico Michi Link")
        }

        Spacer(Modifier.height(32.dp))
    }
}
