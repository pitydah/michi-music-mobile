package org.michimusic.mobile.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.michimusic.data.repository.ExistingGenreVerificationReport
import org.michimusic.mobile.ui.theme.TextSecondary

/** Pure presentation: no IO, media writes, network access or automatic workers. */
@Composable
internal fun SettingsGenreVerificationDetails(
    enabled: Boolean,
    busy: Boolean,
    canCancel: Boolean,
    report: ExistingGenreVerificationReport?,
    onVerify: () -> Unit,
    onCancel: () -> Unit,
) {
    TextButton(
        enabled = enabled,
        onClick = onVerify,
        modifier = Modifier.testTag("settings_library_verify_existing_genres"),
    ) {
        Text(if (report?.nextOffset != null)
            "Verificar siguientes 128 canciones" else "Verificar géneros existentes (128 por lote)")
    }
    if (busy && canCancel) {
        TextButton(onClick = onCancel, modifier = Modifier.testTag("settings_library_cancel_genre_operation")) {
            Text("Detener lectura de etiquetas")
        }
    }
    report?.let { page ->
        Text(
            "Lote: ${page.examined} de ${page.candidates} pistas con género. " +
                "Coinciden: ${page.matching}; diferencias: ${page.differing}; " +
                "sin etiqueta: ${page.withoutEmbeddedGenre}; " +
                "no verificables: ${page.unverifiable}; errores: ${page.unreadable}" +
                (if (page.permissionDenied > 0)
                    "; permisos denegados: ${page.permissionDenied}." else "."),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.testTag("settings_library_genre_verification_summary"),
        )
        var showDifferences by remember(page) { mutableStateOf(false) }
        if (page.differences.isNotEmpty()) {
            TextButton(onClick = { showDifferences = !showDifferences }) {
                Text(if (showDifferences) "Ocultar diferencias" else "Ver diferencias del lote")
            }
        }
        if (showDifferences) page.differences.forEach { difference ->
            Text(
                "${difference.title}: Biblioteca «${difference.indexedGenre}» / " +
                    "Etiqueta «${difference.embeddedGenre}»",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}
