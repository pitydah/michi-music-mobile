package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
fun DiagnosticsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Diagnóstico",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(24.dp))

        val info = listOf(
            "Permiso READ_MEDIA_AUDIO" to "Concedido",
            "Servicio Media3" to "Activo",
            "Conexión Sync" to "Pendiente",
            "Conexión Remote" to "Pendiente",
        )

        info.forEach { (label, value) ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = if (value == "Concedido" || value == "Activo") AccentPink else TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Michi Music Mobile v0.1.0-alpha",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}
