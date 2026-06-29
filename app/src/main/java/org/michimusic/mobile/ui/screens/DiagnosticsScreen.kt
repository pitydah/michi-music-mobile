package org.michimusic.mobile.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkDiagnostics
import org.michimusic.link.TokenStore
import org.michimusic.link.dto.DiagnosticReport
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    val tokenStore = remember { TokenStore(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Diagnóstico Michi Link", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Spacer(Modifier.height(8.dp))

        val savedUrl = tokenStore.getServerUrl()
        if (!savedUrl.isNullOrEmpty() && serverUrl.isEmpty()) {
            serverUrl = savedUrl
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Servidor", color = TextSecondary)
                if (savedUrl != null) {
                    Text(savedUrl, color = TextPrimary)
                } else {
                    Text("Ninguno (conecta desde Sync primero)", color = TextMuted)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                running = true
                report = null
                scope.launch {
                    val client = LinkClient(
                        baseUrl = serverUrl.ifEmpty { savedUrl ?: "" },
                        deviceToken = tokenStore.getDeviceToken() ?: "",
                        clientDeviceId = tokenStore.getClientDeviceId() ?: "",
                    )
                    val diagnostics = LinkDiagnostics(context)
                    report = diagnostics.runAll(client)
                    running = false
                }
            },
            enabled = !running && (serverUrl.isNotEmpty() || !savedUrl.isNullOrEmpty()),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (running) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(if (running) "Ejecutando..." else "Ejecutar diagnóstico")
        }

        Spacer(Modifier.height(16.dp))

        report?.let { rep ->
            // Summary
            val passed = rep.tests.count { it.passed }
            val total = rep.tests.size
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = AccentPink, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("$passed/$total pruebas pasaron", color = TextPrimary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Dispositivo: ${rep.device}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Text("Servidor: ${rep.server}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Text("Estrategia auth: ${rep.authStrategy}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Tests
            rep.tests.forEach { test ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (test.passed) Color(0xFF1B5E20) else Color(0xFF4A0000)
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (test.passed) Icons.Default.CheckCircle else Icons.Default.Error,
                            null,
                            tint = if (test.passed) Color(0xFF4CAF50) else Color(0xFFEF5350),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(test.name, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                            if (test.message.isNotEmpty()) {
                                Text(test.message, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("${test.durationMs}ms", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Errors
            if (rep.errors.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Errores", color = Color(0xFFEF5350), style = MaterialTheme.typography.titleMedium)
                rep.errors.forEach { err ->
                    Text("• $err", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Recommendations
            if (rep.recommendations.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Recomendaciones", color = AccentPink, style = MaterialTheme.typography.titleMedium)
                rep.recommendations.forEach { rec ->
                    Text("• $rec", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Export
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val json = LinkDiagnostics(context).exportJson(rep)
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", json))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Folder, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copiar reporte JSON")
            }
        }

        if (!running && report == null && (serverUrl.isEmpty() && savedUrl.isNullOrEmpty())) {
            Spacer(Modifier.height(32.dp))
            Text(
                "Conéctate a un servidor desde la pantalla Sync primero",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
