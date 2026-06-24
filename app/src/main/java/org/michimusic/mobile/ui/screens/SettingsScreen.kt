package org.michimusic.mobile.ui.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary

private const val PREFS_NAME = "michi_settings"
private const val KEY_SERVER_IP = "server_ip"
private const val KEY_SERVER_PORT = "server_port"
private const val KEY_AUTO_SYNC = "auto_sync"

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var serverIp by remember { mutableStateOf(prefs.getString(KEY_SERVER_IP, "") ?: "") }
    var serverPort by remember { mutableStateOf(prefs.getString(KEY_SERVER_PORT, "53318") ?: "53318") }
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

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Servidor de sincronización",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = serverIp,
            onValueChange = {
                serverIp = it
                prefs.edit().putString(KEY_SERVER_IP, it).apply()
            },
            label = { Text("Dirección IP") },
            placeholder = { Text("192.168.1.100") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = serverPort,
            onValueChange = {
                serverPort = it
                prefs.edit().putString(KEY_SERVER_PORT, it).apply()
            },
            label = { Text("Puerto") },
            placeholder = { Text("53318") },
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
                    prefs.edit().putBoolean(KEY_AUTO_SYNC, it).apply()
                },
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

        Spacer(Modifier.height(32.dp))
    }
}
