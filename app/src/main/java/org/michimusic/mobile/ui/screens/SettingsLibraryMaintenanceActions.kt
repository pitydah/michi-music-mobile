package org.michimusic.mobile.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.michimusic.mobile.library.MusicRootAuthority

/** All foreground library maintenance shares one atomic lease and the existing owner. */
internal class SettingsLibraryMaintenanceActions(
    private val scope: CoroutineScope,
    private val gate: SettingsLibraryOperationGate,
    private val authority: MusicRootAuthority,
    private val permissionReader: SettingsLibraryPermissionReader,
    private val defaultRoot: Boolean,
    private val onBusy: (Boolean) -> Unit,
    private val onProgress: (Int) -> Unit,
    private val onMessage: (String?) -> Unit,
    private val onPermissions: (SettingsLibraryPermissionTruth) -> Unit,
    private val onRescanSuccess: () -> Unit,
) {
    fun restore() {
        gate.launch(scope, SettingsLibraryOperation.RESTORE) {
            onBusy(true)
            onMessage(null)
            try {
                val result = authority.restoreDefault()
                if (result.success) {
                    // The previous root's verification token is no longer authoritative.
                    onRescanSuccess()
                    onPermissions(permissionReader.read())
                    onMessage("Biblioteca restaurada a Music/")
                } else {
                    onMessage(result.message ?: "No se pudo restaurar Music/. La biblioteca anterior sigue intacta.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onMessage("No se pudo restaurar Music/.")
            } finally {
                onBusy(false)
            }
        }
    }

    fun rescan() {
        gate.launch(scope, SettingsLibraryOperation.RESCAN) {
            onBusy(true)
            onProgress(0)
            onMessage(null)
            try {
                val result = authority.rescan(onProgress)
                if (result.success) {
                    onRescanSuccess()
                    onPermissions(permissionReader.read())
                    onMessage(if (defaultRoot) "Biblioteca actualizada."
                        else if (result.trackCount == 0) "La carpeta no contiene pistas compatibles."
                        else "Biblioteca actualizada: ${result.trackCount} pista(s).")
                } else {
                    onPermissions(permissionReader.read())
                    onMessage(result.message ?: "No se pudo volver a leer la carpeta.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onMessage("No se pudo volver a leer la carpeta.")
            } finally {
                onBusy(false)
            }
        }
    }
}
