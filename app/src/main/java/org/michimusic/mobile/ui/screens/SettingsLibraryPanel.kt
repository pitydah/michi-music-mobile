package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import androidx.compose.material3.TextButton
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary






/** SAF authority, launchers and permission observers exist only on Library entry.
 * Grants remain scoped to the exact currently selected tree; no implicit write access.
 */
@Composable
internal fun SettingsLibraryPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localMediaRepository: org.michimusic.data.repository.LocalMediaRepository = koinInject()
                        val musicRootAuthority: org.michimusic.mobile.library.MusicRootAuthority = koinInject()
                        val currentRoot by musicRootAuthority.currentRoot.collectAsStateWithLifecycle()
                        val lastScan by musicRootAuthority.lastScan.collectAsStateWithLifecycle()
                        var folderBusy by remember { mutableStateOf(false) }
                        var folderProgress by remember { mutableStateOf(0) }
                        var folderMessage by remember { mutableStateOf<String?>(null) }
                        var storageSnapshot by remember {
                            mutableStateOf<org.michimusic.mobile.library.LibraryStorageSnapshot?>(null)
                        }
                        val refreshStorageSnapshot: suspend () -> Unit = {
                            storageSnapshot = withContext(Dispatchers.IO) {
                                org.michimusic.mobile.library.LibraryStorageDiagnostics.read(context)
                            }
                        }
                        LaunchedEffect(Unit) {
                            try { refreshStorageSnapshot() }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { folderMessage = "No se pudo leer el espacio utilizado." }
                        }
                        // A failed read is UNKNOWN and denies write without misleading revocation.
                        val permissionReader = remember(musicRootAuthority) {
                            SettingsLibraryPermissionReader(musicRootAuthority)
                        }
                        var permissionTruth by remember(currentRoot.sourceId, currentRoot.treeUri) {
                            mutableStateOf(SettingsLibraryPermissionTruth())
                        }
                        val hasFolderWriteAccess = permissionTruth.canWrite
                        val permissionRevoked = permissionTruth.permissionRevoked
                        var showCreateFolderDialog by remember { mutableStateOf(false) }
                        var proposedFolderName by remember { mutableStateOf("") }
                        var createFolderTargetUri by remember { mutableStateOf<String?>(null) }
                        var folderCreateError by remember { mutableStateOf<String?>(null) }
                        LaunchedEffect(currentRoot, permissionReader) {
                            permissionTruth = permissionReader.read()
                        }

                        // SAF grants can be revoked in Android Settings while the app is paused.
                        val libraryLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        androidx.compose.runtime.DisposableEffect(libraryLifecycleOwner, currentRoot, permissionReader) {
                            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                    scope.launch {
                                        val observed = permissionReader.read()
                                        if (musicRootAuthority.currentRoot.value == currentRoot)
                                            permissionTruth = observed
                                    }
                                }
                            }
                            libraryLifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { libraryLifecycleOwner.lifecycle.removeObserver(observer) }
                        }

                        // This is NOT a library-root switch: reject any folder other than
                        // the exact currently selected tree, and retain WRITE only on opt-in.
                        val writePicker = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                        ) { uri ->
                            if (uri != null && !folderBusy) scope.launch {
                                folderBusy = true
                                try {
                                    val allowed = musicRootAuthority.enableWriteForSelectedRoot(uri)
                                    permissionTruth = permissionReader.read()
                                    folderMessage = if (allowed && permissionTruth.canWrite) {
                                        "Escritura autorizada para la carpeta musical actual."
                                    } else {
                                        "No se autorizó escritura. Selecciona exactamente la carpeta musical activa."
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    permissionTruth = SettingsLibraryPermissionTruth(readFailed = true, checked = true)
                                    folderMessage = "No se pudo verificar el permiso de escritura. Acceso denegado."
                                } finally {
                                    folderBusy = false
                                }
                            }
                        }

                        val treeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                        ) { uri ->
                            if (uri != null) {
                                scope.launch {
                                    folderBusy = true
                                    folderProgress = 0
                                    folderMessage = null
                                    try {
                                        val result = musicRootAuthority.apply(
                                            org.michimusic.mobile.library.MusicRootSelection.CustomTree(uri),
                                            onProgress = { folderProgress = it },
                                        )
                                        if (result.success) {
                                            permissionTruth = permissionReader.read()
                                            folderMessage = if (result.trackCount == 0) {
                                                "Carpeta vacía confirmada. La biblioteca local está vacía."
                                            } else {
                                                "Biblioteca actualizada: ${result.trackCount} pista(s) en ${result.root.label}"
                                            }
                                        } else {
                                            permissionTruth = permissionReader.read()
                                            folderMessage = result.message
                                                ?: "No se pudo cambiar la carpeta. La biblioteca anterior sigue intacta."
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        folderMessage = "No se pudo cambiar la carpeta. La biblioteca anterior sigue intacta."
                                    } finally {
                                        folderBusy = false
                                    }
                                }
                            }
                        }


                        if (showCreateFolderDialog) {
                            SettingsLibraryCreateFolderDialog(
                                rootLabel = currentRoot.label,
                                folderBusy = folderBusy,
                                writeAllowed = hasFolderWriteAccess,
                                name = proposedFolderName,
                                error = folderCreateError,
                                onNameChange = { proposedFolderName = it; folderCreateError = null },
                                onDismiss = { showCreateFolderDialog = false },
                                onConfirm = {
                                    scope.launch {
                                        folderBusy = true
                                        try {
                                            val result = musicRootAuthority.createSubfolder(
                                                proposedFolderName, createFolderTargetUri.orEmpty())
                                            val message = when (result) {
                                                is org.michimusic.data.repository.MusicFolderCreationResult.Created -> {
                                                    showCreateFolderDialog = false
                                                    proposedFolderName = ""
                                                    "Carpeta creada: ${result.name}. Añade música y pulsa Volver a escanear."
                                                }
                                                org.michimusic.data.repository.MusicFolderCreationResult.InvalidName ->
                                                    "El nombre de carpeta no es válido."
                                                org.michimusic.data.repository.MusicFolderCreationResult.PermissionDenied ->
                                                    "Sin permiso de escritura. Vuelve a autorizar la carpeta."
                                                org.michimusic.data.repository.MusicFolderCreationResult.ProviderReadOnly ->
                                                    "El proveedor de archivos no permite crear carpetas aquí."
                                                org.michimusic.data.repository.MusicFolderCreationResult.AlreadyExists ->
                                                    "Ya existe una carpeta con ese nombre."
                                                is org.michimusic.data.repository.MusicFolderCreationResult.Failed -> result.message
                                            }
                                            folderMessage = message
                                            if (result is org.michimusic.data.repository.MusicFolderCreationResult.PermissionDenied)
                                                permissionTruth = permissionReader.read()
                                            folderCreateError = if (result is
                                                org.michimusic.data.repository.MusicFolderCreationResult.Created) null else message
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (_: Exception) {
                                            folderCreateError = "No se pudo crear la carpeta. Comprueba el permiso."
                                            folderMessage = folderCreateError
                                        } finally { folderBusy = false }
                                    }
                                },
                            )
                        }

                        SettingsLibraryDetails(
                            currentRoot = currentRoot,
                            busy = folderBusy,
                            progress = folderProgress,
                            message = folderMessage,
                            permissionRevoked = permissionRevoked,
                            permissionError = permissionTruth.readFailed,
                            permissionReady = permissionTruth.checked,
                            canWrite = hasFolderWriteAccess,
                            snapshot = storageSnapshot,
                            lastScanText = lastScan?.takeIf { it.sourceId == currentRoot.sourceId }?.let { scan ->
                                scan.error?.let { "Último escaneo: $it" }
                                    ?: "Último escaneo: ${scan.trackCount} pista(s) encontradas"
                            },
                            onSelectTree = {
                                try { treeLauncher.launch(null) }
                                catch (_: Exception) { folderMessage = "No se pudo abrir el selector de carpetas." }
                            },
                            onRefreshStorage = { scope.launch {
                                try { refreshStorageSnapshot() }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { folderMessage = "No se pudo comprobar el espacio utilizado." }
                            } },
                            onGrantWrite = {
                                val initial = currentRoot.treeUri?.let {
                                    runCatching { android.net.Uri.parse(it) }.getOrNull()
                                }
                                try { writePicker.launch(initial) }
                                catch (_: Exception) {
                                    permissionTruth = SettingsLibraryPermissionTruth(readFailed = true, checked = true)
                                    folderMessage = "No se pudo abrir la solicitud de escritura."
                                }
                            },
                            onCreateFolder = {
                                proposedFolderName = ""
                                createFolderTargetUri = currentRoot.treeUri
                                folderCreateError = null
                                showCreateFolderDialog = true
                            },
                            onRestoreDefault = {
                                scope.launch {
                                    folderBusy = true
                                    folderMessage = null
                                    try {
                                        val result = musicRootAuthority.restoreDefault()
                                        if (result.success) {
                                            permissionTruth = permissionReader.read()
                                            folderMessage = "Biblioteca restaurada a Music/"
                                        } else {
                                            folderMessage = result.message
                                                ?: "No se pudo restaurar Music/. La biblioteca anterior sigue intacta."
                                        }
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        folderMessage = "No se pudo restaurar Music/."
                                    } finally { folderBusy = false }
                                }
                            },
                            onRecoverGenres = {
                                if (!folderBusy) scope.launch {
                                    folderBusy = true
                                    folderProgress = 0
                                    folderMessage = null
                                    try {
                                        val result = localMediaRepository.recoverAllMissingGenres(
                                            onProgress = { done, total ->
                                                withContext(Dispatchers.Main) {
                                                    folderProgress = done
                                                    folderMessage = "Recuperando etiquetas: $done de $total"
                                                }
                                            },
                                        )
                                        folderMessage = when (result) {
                                            is org.michimusic.data.repository.MediaResult.Success ->
                                                "Recuperación terminada. Regresa a Biblioteca para ver los géneros actualizados."
                                            org.michimusic.data.repository.MediaResult.PermissionDenied ->
                                                "Permiso de lectura denegado. Revisa el origen de la biblioteca."
                                            is org.michimusic.data.repository.MediaResult.QueryFailed ->
                                                "No se completó la recuperación: la biblioteca cambió o el proveedor falló."
                                        }
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        folderMessage = "No fue posible recuperar las etiquetas."
                                    } finally {
                                        folderBusy = false
                                    }
                                }
                            },
                            onRescan = {
                                scope.launch {
                                    folderBusy = true
                                    folderProgress = 0
                                    folderMessage = null
                                    try {
                                        val result = musicRootAuthority.rescan { folderProgress = it }
                                        if (result.success) {
                                            permissionTruth = permissionReader.read()
                                            folderMessage = if (currentRoot.isDefault) {
                                                "Biblioteca actualizada."
                                            } else if (result.trackCount == 0) {
                                                "La carpeta no contiene pistas compatibles."
                                            } else "Biblioteca actualizada: ${result.trackCount} pista(s)."
                                        } else {
                                            permissionTruth = permissionReader.read()
                                            folderMessage = result.message ?: "No se pudo volver a leer la carpeta."
                                        }
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        folderMessage = "No se pudo volver a leer la carpeta."
                                    } finally { folderBusy = false }
                                }
                            },
                        )
}
