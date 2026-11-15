package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * K47-NORM-05: one pending MusicBrainz request and its own consent.
 *
 * Each variant maps 1:1 to a single HTTP request. Selecting a recording or a
 * suggestion is never consent for the next request: the editor must show a new
 * confirmation for the relaxed search and again for the recording genres.
 */
internal sealed interface GenreNetworkConsent {
    /** Strict search: title, artist and (when present) album. */
    data class StrictSearch(
        val fields: Map<String, String>,
    ) : GenreNetworkConsent

    /** Independent second search without the album, only after a zero-match result. */
    data class RelaxedSearch(
        val fields: Map<String, String>,
    ) : GenreNetworkConsent

    /** Second request that sends only the selected recording MBID. */
    data class RecordingGenres(
        val candidate: MichiGenreNormalizationPolicy.RecordingCandidate,
        val plausible: Boolean,
    ) : GenreNetworkConsent
}

internal val GenreNetworkConsent.confirmTestTag: String
    get() = when (this) {
        is GenreNetworkConsent.StrictSearch -> "mi23_send_once"
        is GenreNetworkConsent.RelaxedSearch -> "mi26_send_relaxed"
        is GenreNetworkConsent.RecordingGenres -> "mi27_confirm_recording_lookup"
    }

internal val GenreNetworkConsent.confirmLabel: String
    get() = when (this) {
        is GenreNetworkConsent.StrictSearch -> "Enviar esta vez"
        is GenreNetworkConsent.RelaxedSearch -> "Enviar sin álbum"
        is GenreNetworkConsent.RecordingGenres -> "Consultar esta grabación"
    }

/**
 * Inline consent stage of the single editor sheet.
 *
 * The panel replaces the editor body instead of opening a second window, so the
 * disclosure, the exact outgoing fields and the confirm/cancel pair are all in
 * the same modal surface. Nothing here performs IO by itself: the callbacks are
 * the only way to dispatch the request, and cancelling leaves no queued work.
 */
@Composable
internal fun GenreNetworkConsentPanel(
    consent: GenreNetworkConsent,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("mi26_network_consent"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "¿Consultar MusicBrainz?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = PureWhite,
            modifier = Modifier.testTag("mi26_network_consent_title"),
        )
        when (consent) {
            is GenreNetworkConsent.StrictSearch -> {
                Text("Se enviarán únicamente estos campos públicos:")
                consent.fields.forEach { (key, value) -> Text("$key: $value") }
                Text(
                    "MusicBrainz puede registrar la IP y los parámetros de búsqueda. " +
                        "No se enviarán otros datos.",
                )
            }
            is GenreNetworkConsent.RelaxedSearch -> {
                Text(
                    "Segunda consulta independiente. Se enviarán solo estos campos públicos:",
                )
                consent.fields.forEach { (key, value) -> Text("$key: $value") }
                Text(
                    "No se enviará el álbum ni ningún otro dato. MusicBrainz puede " +
                        "registrar la IP y los parámetros. No se modifica ningún archivo.",
                )
            }
            is GenreNetworkConsent.RecordingGenres -> {
                Text("Se enviará a MusicBrainz este identificador exacto:")
                Text(consent.candidate.mbid)
                Text(
                    "Grabación: ${consent.candidate.title} · ${consent.candidate.artist}",
                )
                Text(
                    "Es una segunda solicitud de red, independiente de la búsqueda anterior. " +
                        "No se enviarán archivos, rutas, historial ni preferencias locales.",
                )
                if (!consent.plausible) {
                    Text("La coincidencia es incierta: revisa la grabación manualmente.")
                }
            }
        }
        Text(
            "Cada consulta requiere su propia confirmación. Cancelar no envía nada.",
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.testTag("mi26_network_consent_cancel"),
            ) { Text("Cancelar", color = TextSecondary) }
            TextButton(
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.testTag(consent.confirmTestTag),
            ) { Text(consent.confirmLabel) }
        }
    }
}
