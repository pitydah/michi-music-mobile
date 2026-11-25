package org.michimusic.mobile.ui.metadata

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.michimusic.core.metadata.MetadataCommittedLibraryChange

/**
 * Post-readback notifications for exact committed metadata transactions.
 *
 * This flow is presentation coordination, never mutation authority. Consumers
 * may patch an already loaded cache from the verified snapshot, but must not
 * infer a successful source write from delivery alone.
 */
object MetadataLibraryRefreshSignal {
    private val mutableChanges = MutableSharedFlow<MetadataCommittedLibraryChange>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes = mutableChanges.asSharedFlow()

    fun request(change: MetadataCommittedLibraryChange): Boolean =
        mutableChanges.tryEmit(change)
}
