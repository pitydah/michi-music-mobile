package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.core.metadata.MetadataEditorDraftSession
import org.michimusic.core.metadata.MetadataField
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.TextSecondary

/** Inline sheet stage, never a nested modal. HTTP starts only after visible consent. */
@Composable
internal fun MichiMetadataMusicBrainzPanel(
    draft: MetadataEditorDraftSession,
    onApply: (Map<MetadataField, List<String>>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    service: MetadataMusicBrainzSuggestions = remember { MetadataMusicBrainzSuggestions() },
) {
    val snapshot = draft.original
    val search = MichiGenreNormalizationPolicy.prepareSearch(
        draft.values(MetadataField.TITLE).firstOrNull().orEmpty(),
        draft.values(MetadataField.ARTIST).firstOrNull().orEmpty(),
        draft.values(MetadataField.ALBUM).firstOrNull().orEmpty(),
    )
    var request by remember(snapshot.target.evidenceToken) {
        mutableStateOf<MichiGenreNormalizationPolicy.SearchIntent?>(null)
    }
    var requestRevision by remember(snapshot.target.evidenceToken) { mutableStateOf(0) }
    var pendingConsent by remember(snapshot.target.evidenceToken) { mutableStateOf(false) }
    var choices by remember(snapshot.target.evidenceToken) {
        mutableStateOf<List<MetadataMusicBrainzSuggestions.Choice>>(emptyList())
    }
    var error by remember(snapshot.target.evidenceToken) { mutableStateOf<String?>(null) }
    var searching by remember(snapshot.target.evidenceToken) { mutableStateOf(false) }
    var selected by remember(snapshot.target.evidenceToken) {
        mutableStateOf<MetadataMusicBrainzSuggestions.Choice?>(null)
    }
    var checked by remember(snapshot.target.evidenceToken) { mutableStateOf<Set<MetadataField>>(emptySet()) }
    // A changed local search cancels the previous request and invalidates previous results.
    LaunchedEffect(search) {
        if (request != null && search != request) {
            request = null; choices = emptyList(); selected = null; checked = emptySet()
        }
    }
    LaunchedEffect(request, requestRevision) {
        val intent = request ?: return@LaunchedEffect
        searching = true; error = null; choices = emptyList(); selected = null
        try {
            choices = service.search(intent)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            error = "MusicBrainz no respondió. Puedes reintentar de forma manual."
        } finally {
            searching = false
        }
    }
    val allowed = selected?.let { MetadataMusicBrainzSuggestions.reviewableFields(snapshot, it) }.orEmpty()
    LazyColumn(modifier.testTag("metadata_mb_panel"),
        verticalArrangement = Arrangement.spacedBy(MichiSpacing.md)) {
        item(key = "mb_title") {
            Column(Modifier.padding(MichiSpacing.xl), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sugerencias MusicBrainz", style = MaterialTheme.typography.titleLarge)
                Text("Consulta voluntaria. Los resultados son propuestas externas, no etiquetas verificadas.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                TextButton(onClick = onBack, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Volver al editor") }
                if (search == null) Text("Introduce título y artista para preparar la búsqueda.")
                else {
                    Text("Se compartirán con MusicBrainz: título «${search.title}», " +
                        "artista «${search.artist}»" +
                        (if (search.album.isNotBlank()) ", álbum «${search.album}»." else "."),
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { pendingConsent = true }, enabled = !searching,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                            .testTag("metadata_mb_search")) { Text("Consultar MusicBrainz") }
                }
                if (pendingConsent && search != null) {
                    Text("¿Autorizar esta búsqueda de grabaciones? No se enviarán rutas, IDs locales ni historial.")
                    Row {
                        TextButton(onClick = { pendingConsent = false }) { Text("Cancelar") }
                        TextButton(onClick = { pendingConsent = false; request = search; requestRevision++ },
                            modifier = Modifier.testTag("metadata_mb_confirm_consent")) {
                            Text("Confirmar consulta")
                        }
                    }
                }
                if (searching) CircularProgressIndicator(modifier = Modifier.testTag("metadata_mb_loading"))
                if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                if (!searching && request != null && choices.isEmpty() && error == null)
                    Text("Sin coincidencias. Revisa los nombres o la edición del álbum.")
            }
        }
        if (!searching && error == null) {
            items(choices, key = { it.identity }) { choice ->
                Column(Modifier.fillMaxWidth().padding(horizontal = MichiSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(choice.title, style = MaterialTheme.typography.titleMedium)
                    Text(choice.artistCredit + " · " + (choice.releaseTitle ?: "Sin edición asignada"),
                        color = TextSecondary)
                    Text("MBID ${choice.recordingMbid}" +
                        (choice.score?.let { " · coincidencia $it/100" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    TextButton(onClick = { selected = choice; checked = emptySet() },
                        modifier = Modifier.testTag("metadata_mb_choose_${choice.identity}")) {
                        Text("Comparar esta edición")
                    }
                    HorizontalDivider()
                }
            }
        }
        if (selected != null) {
            item(key = "mb_fields") {
                Column(Modifier.padding(MichiSpacing.xl), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selecciona qué datos proponer", style = MaterialTheme.typography.titleMedium)
                    Text("Solo se permiten campos con lectura local verificada. Ningún dato se copia automáticamente.",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    for ((field, values) in selected!!.fields) {
                        val enabled = field in allowed
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(checked = field in checked, enabled = enabled,
                                onCheckedChange = { yes -> checked = if (yes) checked + field else checked - field })
                            Column(Modifier.weight(1f)) {
                                Text(metadataFieldLabel(field), style = MaterialTheme.typography.labelLarge)
                                Text(values.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary)
                                if (!enabled) Text("Lectura local insuficiente para proponer este campo",
                                    style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                    }
                    Button(onClick = {
                        onApply(allowed.filterKeys { it in checked })
                        selected = null; checked = emptySet()
                    }, enabled = checked.isNotEmpty(), modifier = Modifier.fillMaxWidth()
                        .sizeIn(minHeight = 48.dp).testTag("metadata_mb_apply")) {
                        Text("Añadir ${checked.size} propuesta(s) al borrador")
                    }
                }
            }
        }
    }
}
