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
import org.michimusic.data.repository.ExistingGenreVerificationReport
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.MediaResult

/** User-triggered, 128-item, READ-ONLY genre comparison; route cancellation is cooperative. */
@Composable
internal fun rememberSettingsGenreVerification(
    scope: CoroutineScope,
    busy: Boolean,
    operationGate: SettingsLibraryOperationGate,
    startOffset: Int,
    expectedSnapshotToken: String?,
    onBusyChange: (Boolean) -> Unit,
    onProgressChange: (Int) -> Unit,
    onMessageChange: (String?) -> Unit,
    onReport: (ExistingGenreVerificationReport?) -> Unit,
): () -> Unit {
    val repository: LocalMediaRepository = koinInject()
    val busyState = rememberUpdatedState(busy)
    val startState = rememberUpdatedState(startOffset)
    val tokenState = rememberUpdatedState(expectedSnapshotToken)
    val busyWriter = rememberUpdatedState(onBusyChange)
    val progressWriter = rememberUpdatedState(onProgressChange)
    val messageWriter = rememberUpdatedState(onMessageChange)
    val reportWriter = rememberUpdatedState(onReport)
    return remember(repository, scope, operationGate) {
        {
            if (!busyState.value) {
                operationGate.launch(scope, SettingsLibraryOperation.VERIFY) {
                    busyWriter.value(true)
                    progressWriter.value(0)
                    messageWriter.value(null)
                    try {
                        val result = repository.verifyExistingGenres(
                            startOffset = startState.value,
                            expectedSnapshotToken = tokenState.value,
                        ) { done, total ->
                            withContext(Dispatchers.Main) {
                                progressWriter.value(done)
                                messageWriter.value("Verificando etiquetas: $done de $total")
                            }
                        }
                        when (result) {
                            is MediaResult.Success -> {
                                reportWriter.value(result.data)
                                messageWriter.value(
                                    "Verificados ${result.data.examined} archivos; " +
                                        "${result.data.differing} discrepancias. No se modificó ningún archivo.")
                            }
                            MediaResult.PermissionDenied -> {
                                // Never leave an old page displayed after permission revocation.
                                reportWriter.value(null)
                                progressWriter.value(0)
                                messageWriter.value("Acceso denegado: revisa el permiso de la biblioteca.")
                            }
                            is MediaResult.QueryFailed -> {
                                reportWriter.value(null)
                                progressWriter.value(0)
                                messageWriter.value("Verificación detenida: cambió el catálogo o falló el proveedor. Reinicia desde el primer lote.")
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        progressWriter.value(0)
                        messageWriter.value("Verificación cancelada; los lotes anteriores permanecen para consulta. No se modificaron etiquetas.")
                        throw cancelled
                    }
                    catch (_: Exception) {
                        reportWriter.value(null)
                        progressWriter.value(0)
                        messageWriter.value("No fue posible verificar las etiquetas.")
                    }
                    finally { busyWriter.value(false) }
                }
            }
        }
    }
}
