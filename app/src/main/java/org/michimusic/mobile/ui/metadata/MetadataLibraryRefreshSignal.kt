package org.michimusic.mobile.ui.metadata

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * ME-13 T48: a completed MP3 save invalidates the live local library exactly
 * once. The editor never edits a remembered Track in place; the library screen
 * reloads from its source after this signal.
 */
object MetadataLibraryRefreshSignal {
    val revision = MutableStateFlow(0L)

    fun request() {
        revision.value = revision.value + 1L
    }
}
