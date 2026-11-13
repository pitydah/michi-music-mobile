package org.michimusic.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.MediaResult

/**
 * P12 manual genre recovery for Settings → Library.
 *
 * The repository re-reads embedded tags only after an explicit user request, so
 * this action owns the recovery state machine (busy, progress, message) while
 * the Library panel keeps rendering the shared status. It never schedules work,
 * timers or background scans.
 */
@Composable
internal fun rememberSettingsGenreRecovery(
    scope: CoroutineScope,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
    onProgressChange: (Int) -> Unit,
    onMessageChange: (String?) -> Unit,
): () -> Unit {
    val repository: LocalMediaRepository = koinInject()
    val busyState = rememberUpdatedState(busy)
    val busyWriter = rememberUpdatedState(onBusyChange)
    val progressWriter = rememberUpdatedState(onProgressChange)
    val messageWriter = rememberUpdatedState(onMessageChange)
    return remember(repository, scope) {
        {
            if (!busyState.value) scope.launch {
                busyWriter.value(true)
                progressWriter.value(0)
                messageWriter.value(null)
                try {
                    val result = repository.recoverAllMissingGenres(
                        onProgress = { done, total ->
                            withContext(Dispatchers.Main) {
                                progressWriter.value(done)
                                messageWriter.value("Recuperando etiquetas: $done de $total")
                            }
                        },
                    )
                    messageWriter.value(when (result) {
                        is MediaResult.Success ->
                            "Recuperación terminada. Regresa a Biblioteca para ver los géneros actualizados."
                        MediaResult.PermissionDenied ->
                            "Permiso de lectura denegado. Revisa el origen de la biblioteca."
                        is MediaResult.QueryFailed ->
                            "No se completó la recuperación: la biblioteca cambió o el proveedor falló."
                    })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    messageWriter.value("No fue posible recuperar las etiquetas.")
                } finally {
                    busyWriter.value(false)
                }
            }
        }
    }
}
