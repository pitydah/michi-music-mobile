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

@OptIn(UnstableApi::class)
class MichiMediaLibrarySessionCallback(
    private val libraryProvider: LibraryProvider,
    private val stateStore: PlaybackStateStore,
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
        libraryProvider.refresh()
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


    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        val resolved = libraryProvider.resolveForPlayback(mediaItems)
        return if (resolved.isNotEmpty()) {
            Futures.immediateFuture(resolved)
        } else {
            Futures.immediateFuture(mediaItems)
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        browser: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> {
        if (mediaItems.size == 1) {
            maybeExpandSingleItem(mediaItems.first(), startIndex, startPositionMs)?.also {
                return Futures.immediateFuture(it)
            }
        }
        val resolved = libraryProvider.resolveForPlayback(mediaItems)
        return Futures.immediateFuture(
            MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
        )
    }

    private fun maybeExpandSingleItem(
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
