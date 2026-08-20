package org.michimusic.player

import android.util.Log
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
        val saved = stateStore.restore()
        if (saved.mediaIds.isEmpty()) {
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            )
        }
        scope.launch { libraryProvider.refresh() }
        val items = saved.mediaIds.mapNotNull { libraryProvider.getItem(it) }
        if (items.isEmpty()) {
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            )
        }
        val resolved = libraryProvider.resolveForPlayback(items)
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(
                resolved,
                saved.startIndex.coerceIn(0, resolved.lastIndex),
                saved.positionMs,
            )
        )
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        val future = SettableFuture.create<List<MediaItem>>()
        scope.launch {
            try {
                // Guarantees LibraryProvider's cache is populated before resolving - the
                // browsing-only callbacks that used to populate it (onGetLibraryRoot,
                // onPlaybackResumption) are never invoked by a plain MediaController client.
                libraryProvider.ensureLoaded()
                Log.d(
                    "MichiPlaybackDebug",
                    "onAddMediaItems received=${mediaItems.size} ids=${mediaItems.map { it.mediaId }}",
                )
                val resolved = libraryProvider.resolveForPlayback(mediaItems)
                Log.d(
                    "MichiPlaybackDebug",
                    "onAddMediaItems resolved=${resolved.size} ids=${resolved.map { it.mediaId }}",
                )
                future.set(if (resolved.isNotEmpty()) resolved else mediaItems)
            } catch (e: Exception) {
                Log.e("MichiPlaybackDebug", "onAddMediaItems failed to resolve mediaItems", e)
                future.set(mediaItems)
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
                // Same guarantee as onAddMediaItems: ensure the cache is populated before
                // either the single-item expansion path or resolveForPlayback read it.
                libraryProvider.ensureLoaded()

                if (mediaItems.size == 1) {
                    val expanded = maybeExpandSingleItem(mediaItems.first(), startIndex, startPositionMs)
                    if (expanded != null) {
                        future.set(expanded)
                        return@launch
                    }
                }

                Log.d(
                    "MichiPlaybackDebug",
                    "onSetMediaItems received=${mediaItems.size} ids=${mediaItems.map { it.mediaId }}",
                )
                val resolved = libraryProvider.resolveForPlayback(mediaItems)
                Log.d(
                    "MichiPlaybackDebug",
                    "onSetMediaItems resolved=${resolved.size} ids=${resolved.map { it.mediaId }}",
                )
                future.set(MediaItemsWithStartPosition(resolved, startIndex, startPositionMs))
            } catch (e: Exception) {
                Log.e("MichiPlaybackDebug", "onSetMediaItems failed to resolve mediaItems", e)
                future.set(MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs))
            }
        }
        return future
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
