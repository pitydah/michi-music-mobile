package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.mobile.artist.artwork.ArtistArtworkAutoScheduler
import org.michimusic.mobile.ui.theme.TertiaryCyan

/** Privacy-first library settings. Reads preferences off main; does not scan or fetch. */
@Composable
private fun ArtistArtworkAutoSettingsBody() {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val scheduler = remember(app) { ArtistArtworkAutoScheduler(app) }
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var paused by remember { mutableStateOf(false) }
    var wifiOnly by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var backfillStatus by remember { mutableStateOf<String?>(null) }
    var backfillExhausted by remember { mutableStateOf(false) }

    // Reading back persisted state after every attempted scheduler operation is
    // essential: WorkManager cancellation/dispatch may fail AFTER prefs.commit().
    // A stale switch would misrepresent consent, pause or the network policy.
    suspend fun syncPreferenceState() {
        val values = withContext(Dispatchers.IO) {
            Triple(ArtistArtworkAutoScheduler.isEnabled(app),
                ArtistArtworkAutoScheduler.isPaused(app), ArtistArtworkAutoScheduler.unmeteredOnly(app))
        }
        enabled = values.first
        paused = values.second
        wifiOnly = values.third
    }

    LaunchedEffect(app) { syncPreferenceState() }

    // Opted-out installations must not create a long-lived WorkManager observer.
    val activeWork by produceState(0, app, enabled) {
        if (enabled == true) {
            WorkManager.getInstance(app)
                .getWorkInfosByTagFlow(ArtistArtworkAutoScheduler.TAG)
                .collect { work ->
                    value = work.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                }
        } else value = 0
    }

    // The section card already states the title; repeating it here read as two headings.
    Column(modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Con tu permiso, Michi consulta fuentes abiertas para artistas ya identificados. " +
                "Envía identificadores del artista, nunca canciones, archivos, rutas o historial.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Buscar fotografías automáticamente", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            Switch(checked = enabled == true, enabled = enabled != null && !busy,
                modifier = Modifier.testTag("artist_artwork_auto_enabled"),
                onCheckedChange = { target ->
                    scope.launch {
                        busy = true
                        try { scheduler.setEnabled(target); enabled = target; error = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "No se pudo completar el cambio; se muestra el permiso guardado." }
                        finally { try { syncPreferenceState() } finally { busy = false } }
                    }
                })
        }
        if (enabled == true) {
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Solo Wi-Fi o red sin costo", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                Switch(checked = wifiOnly, enabled = !busy,
                    modifier = Modifier.testTag("artist_artwork_wifi_only"),
                    onCheckedChange = { target ->
                        scope.launch {
                            busy = true
                            try { scheduler.setUnmeteredOnly(target); wifiOnly = target; error = null }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "No se pudo completar el cambio; se muestra la red guardada." }
                            finally { try { syncPreferenceState() } finally { busy = false } }
                        }
                    })
            }
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Pausar búsquedas automáticas", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                Switch(checked = paused, enabled = !busy,
                    modifier = Modifier.testTag("artist_artwork_paused"),
                    onCheckedChange = { target ->
                        scope.launch {
                            busy = true
                            try { scheduler.setPaused(target); paused = target; error = null }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "No se pudo completar el cambio; se muestra la pausa guardada." }
                            finally { try { syncPreferenceState() } finally { busy = false } }
                        }
                    })
            }
            Text(if (paused) "Búsquedas pausadas" else "Trabajos en espera o ejecución: $activeWork",
                style = MaterialTheme.typography.bodySmall)
            Text("La activación no revisa masivamente la biblioteca: solo atiende " +
                "nuevos cambios confirmados y trabajos ya pendientes. Para revisar " +
                "artistas existentes, inicia manualmente un lote con el botón inferior.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy && !paused,
                modifier = Modifier.testTag("artist_artwork_backfill_batch"),
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val result = org.michimusic.mobile.artist.artwork.ArtistArtworkAutoBackfill(app)
                                .reviewBoundedSession()
                            backfillExhausted = result.exhausted
                            backfillStatus = "Revisados ${result.inspected} créditos en ${result.batches} lote(s); " +
                                "eventos preparados: ${result.queued}." +
                                (if (result.exhausted) " Fin del catálogo verificado." else " Puedes continuar con otra revisión acotada.")
                            error = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "No se completó la búsqueda inicial." }
                        finally { busy = false }
                    }
                }) { Text("Buscar fotos faltantes · revisión acotada") }
            backfillStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (backfillExhausted) {
                OutlinedButton(enabled = !busy && !paused,
                    modifier = Modifier.testTag("artist_artwork_backfill_restart"),
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                org.michimusic.mobile.artist.artwork.ArtistArtworkAutoBackfill(app).restartReview()
                                backfillExhausted = false
                                backfillStatus = "Revisión reiniciada. Pulsa buscar para revisar otro lote."
                                error = null
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "No se pudo reiniciar la revisión." }
                            finally { busy = false }
                        }
                    }) { Text("Revisar desde el inicio") }
            }
            OutlinedButton(enabled = !busy && !paused,
                modifier = Modifier.testTag("artist_artwork_retry_pending"),
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val count = scheduler.retryPendingExplicitly()
                            backfillStatus = "Reintentos solicitados: $count asociación(es) verificadas."
                            error = null
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "No se pudieron reprogramar los trabajos pendientes." }
                        finally { busy = false }
                    }
                }) { Text("Reintentar trabajos pendientes") }
        } else {
            Text("Desactivado: no se consultan fuentes externas automáticamente.",
                style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * The automatic ladder is a library setting, so it wears the same section card as the
 * rest of Settings instead of a bare Column: a bare Column inherits no content colour,
 * and on obsidian that renders black text.
 */
@Composable
internal fun ArtistArtworkAutoSettings() {
    SettingsSectionCard(
        icon = Icons.Default.Face,
        title = "Fotografías de artistas",
        subtitle = "Búsqueda automática y revisión acotada",
        accent = TertiaryCyan,
    ) {
        ArtistArtworkAutoSettingsBody()
    }
}
