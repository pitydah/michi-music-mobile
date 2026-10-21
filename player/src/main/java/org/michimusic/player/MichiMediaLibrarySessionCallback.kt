package org.michimusic.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MichiMediaLibrarySessionCallback(
    private val libraryProvider: LibraryProvider,
    private val stateStore: PlaybackStateStore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) : MediaLibraryService.MediaLibrarySession.Callback {

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        return MediaSession.ConnectionResult.accept(
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
        )
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        scope.launch { libraryProvider.refresh() }
        val root = MediaItem.Builder()
            .setMediaId(LibraryProvider.ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Michi Music")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(root, params))
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = libraryProvider.getItem(mediaId)
        return if (item != null) {
            Futures.immediateFuture(LibraryResult.ofItem(item, null))
        } else {
            Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val children = libraryProvider.getChildren(parentId)
        return if (children.isNotEmpty()) {
            Futures.immediateFuture(LibraryResult.ofItemList(children, params))
        } else {
            Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }
    }

    override fun onPlaybackResumption(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            try {
                val saved = stateStore.restore()
                if (saved.mediaIds.isEmpty()) {
                    future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                    return@launch
                }
                libraryProvider.ensureLoaded()
                val items = saved.mediaIds.mapNotNull { libraryProvider.getItem(it) }
                if (items.isEmpty()) {
                    future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                    return@launch
                }
                val resolved = libraryProvider.resolveForPlayback(items)
                future.set(
                    MediaSession.MediaItemsWithStartPosition(
                        resolved,
                        saved.startIndex.coerceIn(0, resolved.lastIndex.coerceAtLeast(0)),
                        saved.positionMs,
                    )
                )
            } catch (_: Exception) {
                future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
            }
        }
        return future
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        val future = SettableFuture.create<List<MediaItem>>()
        scope.launch {
            try {
                libraryProvider.ensureLoaded()
                val resolved = libraryProvider.resolveForPlayback(mediaItems)
                future.set(resolved)
            } catch (_: Exception) {
                future.set(emptyList())
            }
        }
        return future
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        browser: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaItemsWithStartPosition>()
        scope.launch {
            try {
                libraryProvider.ensureLoaded()

                if (mediaItems.size == 1) {
                    val expanded = maybeExpandSingleItem(mediaItems.first(), startIndex, startPositionMs)
                    if (expanded != null) {
                        future.set(expanded)
                        return@launch
                    }
                }

                val resolved = libraryProvider.resolveForPlayback(mediaItems)
                val safeIndex = if (resolved.isNotEmpty()) startIndex.coerceIn(0, resolved.lastIndex) else 0
                future.set(MediaItemsWithStartPosition(resolved, safeIndex, startPositionMs))
            } catch (_: Exception) {
                future.set(MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs))
            }
        }
        return future
    }

    private suspend fun maybeExpandSingleItem(
        mediaItem: MediaItem,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaItemsWithStartPosition? {
        val item = libraryProvider.getItem(mediaItem.mediaId) ?: return null
        if (item.mediaMetadata.isBrowsable == true) {
            val children = libraryProvider.getChildren(mediaItem.mediaId)
            if (children.isNotEmpty()) {
                val resolved = libraryProvider.resolveForPlayback(children)
                return MediaItemsWithStartPosition(resolved, 0, startPositionMs)
            }
        }
        return null
    }
}
