package org.michimusic.mobile.library

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.MusicRoot
import org.michimusic.data.repository.MusicRootPreferences
import org.michimusic.data.repository.MusicRootType
import org.michimusic.data.repository.RootScanSummary
import org.michimusic.data.repository.SafMediaRepository
import org.michimusic.data.repository.TreeIndexResult
import org.michimusic.player.AudioController

/** A prospective choice held by the Wizard until its completion transaction. */
sealed interface MusicRootSelection {
    data object DefaultMusic : MusicRootSelection
    data class CustomTree(val treeUri: Uri) : MusicRootSelection
}

enum class MusicRootError {
    PERMISSION_DENIED,
    READ_FAILED,
    SAVE_FAILED,
    NO_PERMISSION,
}

data class MusicRootChange(
    val success: Boolean,
    val root: MusicRoot,
    val previousRoot: MusicRoot? = null,
    val trackCount: Int = 0,
    val error: MusicRootError? = null,
    val message: String? = null,
    internal val newSourceId: String? = null,
    internal val previousSourceId: String? = null,
)

/**
 * One app-wide authority for the local music root. Wizard and Settings never
 * write SharedPreferences or Room directly: both prepare/index and then confirm
 * through this object, so the last confirmed root is the only active catalog.
 */
class MusicRootAuthority(
    private val preferences: MusicRootPreferences,
    private val safRepository: SafMediaRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val audioController: AudioController,
) {
    private val changeMutex = Mutex()
    private val _currentRoot = MutableStateFlow(preferences.currentRoot())
    val currentRoot: StateFlow<MusicRoot> = _currentRoot.asStateFlow()

    /** Emits only after a confirmed change; Library/Search observers reload from it. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _lastScan = MutableStateFlow(preferences.lastScan())
    val lastScan: StateFlow<RootScanSummary?> = _lastScan.asStateFlow()

    /** Validates the native tree grant and returns its user-visible name. */
    suspend fun previewCustomRoot(treeUri: Uri): String? = safRepository.prepareSelection(treeUri)

    /** This property never grants access; it reflects an already-retained opt-in. */
    fun canWriteCurrentRoot(): Boolean {
        val root = _currentRoot.value
        if (root.isDefault) return false
        val uri = root.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return safRepository.hasPermission(uri) && safRepository.hasWritePermission(uri)
    }

    /** Explicit second picker action. Never grant WRITE to a different folder. */
    suspend fun enableWriteForSelectedRoot(selectedTree: Uri): Boolean = changeMutex.withLock {
        val root = preferences.currentRoot()
        if (root.isDefault || root.treeUri != selectedTree.toString() ||
            !safRepository.hasPermission(selectedTree)
        ) return@withLock false
        safRepository.takePersistableWritePermission(selectedTree)
    }

    /** No implicit folder creation when choosing a library or scanning music. */
    suspend fun createSubfolder(
        name: String,
        expectedRootTreeUri: String,
    ): org.michimusic.data.repository.MusicFolderCreationResult = changeMutex.withLock {
        val current = preferences.currentRoot()
        if (current.isDefault) {
            return@withLock org.michimusic.data.repository.MusicFolderCreationResult.PermissionDenied
        }
        if (current.treeUri != expectedRootTreeUri) {
            return@withLock org.michimusic.data.repository.MusicFolderCreationResult.Failed(
                "La carpeta musical cambió. Vuelve a confirmar la ubicación.",
            )
        }
        val tree = current.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return@withLock org.michimusic.data.repository.MusicFolderCreationResult.Failed(
                "La carpeta musical ya no está disponible.",
            )
        safRepository.createRootSubfolder(tree, name)
    }

    /** Single authority for local catalog visibility; no deletion of original audio. */
    suspend fun hideLocalTrack(
        trackId: String,
        expectedUri: String,
        expectedFingerprint: String? = null,
    ): org.michimusic.data.repository.LocalVisibilityResult = changeMutex.withLock {
        val result = localMediaRepository.hideFromCurrentLibrary(
            trackId,
            expectedUri,
            expectedFingerprint,
        )
        if (result == org.michimusic.data.repository.LocalVisibilityResult.Hidden) {
            _revision.value += 1
        }
        result
    }

    suspend fun restoreHiddenTrack(
        trackId: String,
        expectedSource: String,
    ): org.michimusic.data.repository.LocalVisibilityResult = changeMutex.withLock {
        val result = localMediaRepository.restoreInCurrentLibrary(trackId, expectedSource)
        if (result == org.michimusic.data.repository.LocalVisibilityResult.Restored) {
            _revision.value += 1
        }
        result
    }

    fun currentLocalVisibilitySource(): String = localMediaRepository.currentVisibilitySource()

    /** Shares the same mutex as SAF root changes and single-track removals. */
    suspend fun hideLocalSelection(
        selection: List<org.michimusic.data.repository.LocalVisibilitySelection>,
        expectedSource: String,
    ): org.michimusic.data.repository.LocalVisibilityBatchResult = changeMutex.withLock {
        val result = localMediaRepository.hideSelectionFromCurrentLibrary(selection, expectedSource)
        if (result is org.michimusic.data.repository.LocalVisibilityBatchResult.Hidden) {
            _revision.value += 1
        }
        result
    }

    suspend fun hiddenTracks(): List<org.michimusic.data.cache.LibraryExclusionEntity> =
        changeMutex.withLock { localMediaRepository.hiddenInCurrentLibrary() }

    fun permissionRevoked(): Boolean {
        val root = _currentRoot.value
        val uri = root.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return !root.isDefault && !safRepository.hasPermission(uri)
    }

    /**
     * Indexes a custom tree before touching the active root, then commits the
     * preference. [clearPlayback] is false for the Wizard, which activates the
     * change only after onboarding completion succeeds.
     */
    suspend fun apply(
        selection: MusicRootSelection,
        clearPlayback: Boolean = true,
        onProgress: (Int) -> Unit = {},
    ): MusicRootChange = changeMutex.withLock {
        val previous = preferences.currentRoot()
        val previousSource = previous.sourceId
        val candidate = when (selection) {
            MusicRootSelection.DefaultMusic -> MusicRoot.Default to 0
            is MusicRootSelection.CustomTree -> {
                when (val indexed = safRepository.indexTree(selection.treeUri, onProgress)) {
                    is TreeIndexResult.Success -> MusicRoot(
                        type = MusicRootType.CUSTOM_TREE,
                        treeUri = indexed.treeUri.toString(),
                        displayName = indexed.displayName,
                        sourceId = indexed.sourceId,
                    ) to indexed.trackCount
                    TreeIndexResult.PermissionDenied -> return@withLock MusicRootChange(
                        success = false,
                        root = previous,
                        error = MusicRootError.PERMISSION_DENIED,
                        message = "El permiso de la carpeta no está disponible.",
                    )
                    is TreeIndexResult.Failed -> return@withLock MusicRootChange(
                        success = false,
                        root = previous,
                        error = MusicRootError.READ_FAILED,
                        message = indexed.message,
                    )
                }
            }
        }
        val root = candidate.first
        val count = candidate.second
        val changed = root != previous

        if (changed && !preferences.saveRoot(root)) {
            if (selection is MusicRootSelection.CustomTree && root.sourceId != previousSource) {
                root.sourceId?.let { safRepository.clearSource(it) }
            }
            return@withLock MusicRootChange(
                success = false,
                root = previous,
                error = MusicRootError.SAVE_FAILED,
                message = "No se pudo guardar la carpeta de música.",
            )
        }

        _currentRoot.value = root
        if (!root.isDefault) {
            val summary = RootScanSummary(System.currentTimeMillis(), count, sourceId = root.sourceId)
            preferences.recordScan(count, sourceId = root.sourceId)
            _lastScan.value = summary
        }

        if (clearPlayback && (changed || !root.isDefault)) {
            activateLocked(previous)
        }

        MusicRootChange(
            success = true,
            root = root,
            previousRoot = if (changed) previous else null,
            trackCount = count,
            newSourceId = root.sourceId,
            previousSourceId = previousSource,
        )
    }

    /** Called by the Wizard after its onboarding completion transaction succeeds. */
    suspend fun activate(change: MusicRootChange) {
        if (!change.success || change.previousRoot == null) return
        changeMutex.withLock {
            activateLocked(change.previousRoot)
        }
    }

    /** Restores the prior root if the Wizard's completion transaction fails. */
    suspend fun rollback(change: MusicRootChange): Boolean = changeMutex.withLock {
        val previous = change.previousRoot ?: return@withLock true
        if (!preferences.saveRoot(previous)) return@withLock false
        _currentRoot.value = previous
        val newSource = change.newSourceId
        if (newSource != null && newSource != change.previousSourceId) {
            safRepository.clearSource(newSource)
        }
        true
    }

    suspend fun restoreDefault(clearPlayback: Boolean = true): MusicRootChange =
        apply(MusicRootSelection.DefaultMusic, clearPlayback)

    suspend fun rescan(
        onProgress: (Int) -> Unit = {},
    ): MusicRootChange = changeMutex.withLock {
        val root = preferences.currentRoot()
        if (root.isDefault) {
            localMediaRepository.invalidateCache()
            _revision.value += 1
            return@withLock MusicRootChange(success = true, root = root)
        }
        val treeUri = root.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return@withLock MusicRootChange(
                success = false,
                root = root,
                error = MusicRootError.READ_FAILED,
                message = "La carpeta guardada no es válida.",
            )
        when (val indexed = safRepository.indexTree(treeUri, onProgress)) {
            is TreeIndexResult.Success -> {
                val sourceChanged = indexed.sourceId != root.sourceId
                val activeRoot = if (!sourceChanged) {
                    root
                } else {
                    val migrated = root.copy(sourceId = indexed.sourceId)
                    // Keep excluded songs excluded when upgrading a legacy SAF source.
                    // Only copy metadata; leave old rows for safe rollback/onboarding recovery.
                    try {
                        root.sourceId?.let { oldSource ->
                            localMediaRepository.copyVisibilityToMigratedSource(
                                oldSource,
                                indexed.sourceId,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@withLock MusicRootChange(
                            success = false,
                            root = root,
                            error = MusicRootError.SAVE_FAILED,
                            message = "No se pudo preservar la lista de canciones ocultas.",
                        )
                    }
                    if (!preferences.saveRoot(migrated)) {
                        return@withLock MusicRootChange(
                            success = false,
                            root = root,
                            error = MusicRootError.SAVE_FAILED,
                            message = "No se pudo actualizar el índice de la carpeta. La biblioteca anterior sigue intacta.",
                        )
                    }
                    _currentRoot.value = migrated
                    root.sourceId?.takeIf { it != indexed.sourceId }?.let { safRepository.clearSource(it) }
                    migrated
                }
                localMediaRepository.invalidateCache()
                // Re-indexing the same root keeps deterministic SAF identities and file
                // locations valid, so the active queue and position remain authoritative.
                // Only a legacy source migration changes those identities enough to require
                // dropping the local queue.
                if (sourceChanged) audioController.clearQueue()
                preferences.recordScan(indexed.trackCount, sourceId = activeRoot.sourceId)
                _lastScan.value = RootScanSummary(
                    System.currentTimeMillis(),
                    indexed.trackCount,
                    sourceId = activeRoot.sourceId,
                )
                _revision.value += 1
                MusicRootChange(success = true, root = activeRoot, trackCount = indexed.trackCount)
            }
            TreeIndexResult.PermissionDenied -> MusicRootChange(
                success = false,
                root = root,
                error = MusicRootError.PERMISSION_DENIED,
                message = "El permiso de esta carpeta se revocó. Vuelve a elegirla.",
            )
            is TreeIndexResult.Failed -> MusicRootChange(
                success = false,
                root = root,
                error = MusicRootError.READ_FAILED,
                message = indexed.message,
            )
        }
    }

    private suspend fun activateLocked(previous: MusicRoot) {
        audioController.clearQueue()
        localMediaRepository.invalidateCache()
        _revision.value += 1
        val previousSource = previous.sourceId
        if (!previous.isDefault && previousSource != _currentRoot.value.sourceId) {
            previousSource?.let { safRepository.clearSource(it) }
        }
    }
}
