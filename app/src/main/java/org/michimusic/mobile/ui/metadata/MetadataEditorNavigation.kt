package org.michimusic.mobile.ui.metadata

import androidx.compose.runtime.staticCompositionLocalOf
import org.michimusic.core.metadata.MetadataEditorSection

/** Where the single metadata editor was opened from. Kept for telemetry and copy only. */
enum class MetadataEditorOrigin {
    NOW_PLAYING,
    TRACK_MENU,
    GENRE_LIBRARY,
    ALBUM,
    SONGS,
    QUEUE,
    FILE_INFO,
}

/**
 * ME-11S: typed, temporary launch request for the ONE metadata editor destination.
 *
 * The request carries the full canonical identity needed to revalidate the exact
 * document (owner id, media id, URI and expected size). It lives only in process
 * memory, never in a navigation route or serialized state. The destination still
 * re-resolves the provider, permissions and scope on entry.
 */
data class MetadataEditorLaunchRequest(
    val sourceId: String,
    val trackId: String,
    val trackUri: String = "",
    val expectedSize: Long = 0L,
    val scopeGeneration: Long = 0L,
    val origin: MetadataEditorOrigin,
    val initialSection: MetadataEditorSection = MetadataEditorSection.BASICS,
    val selectionIds: List<String> = emptyList(),
    val focusGenre: Boolean = false,
) {
    val isBatch: Boolean get() = selectionIds.isNotEmpty()
}

/** Bounded, process-lifetime handoff between a menu and the navigation destination. */
object MetadataEditorLaunchHolder {
    @Volatile
    private var pending: MetadataEditorLaunchRequest? = null

    @Synchronized
    fun offer(request: MetadataEditorLaunchRequest) {
        pending = request
    }

    @Synchronized
    fun take(): MetadataEditorLaunchRequest? {
        val request = pending
        pending = null
        return request
    }

    /**
     * Reads the pending request without consuming it, so a configuration change
     * can rebuild the destination. The request is cleared when the editor closes.
     */
    @Synchronized
    fun peek(): MetadataEditorLaunchRequest? = pending

    @Synchronized
    fun clear() {
        pending = null
    }
}

const val METADATA_EDITOR_ROUTE = "metadata-editor"

/**
 * Screens open the editor through this single launcher instead of hosting their own
 * sheet. Hosts must dismiss their current MichiBottomSheet before invoking it.
 */
val LocalMetadataEditorLauncher = staticCompositionLocalOf<(MetadataEditorLaunchRequest) -> Unit> {
    { _ -> }
}
