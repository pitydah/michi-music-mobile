package org.michimusic.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.michimusic.core.models.Album
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.repository.LocalMediaRepository

class LibraryProvider(
    private val context: Context,
    private val repository: LocalMediaRepository,
) {

    companion object {
        const val ROOT = "__ROOT__"
        const val ALBUMS = "albums"
        const val ALBUM = "album"
        const val ARTISTS = "artists"
        const val ARTIST = "artist"
        const val SONGS = "songs"
        const val PLAYLISTS = "playlists"
        const val PLAYLIST = "playlist"
        const val QUEUE = "queue"
        const val SEP = "/"
        const val SONG_PREFIX = "song"

        fun buildAlbumId(albumCoverId: String) = "$ALBUM$SEP$albumCoverId"
        fun buildSongId(songId: String) = "$SONG_PREFIX$SEP$songId"
        fun buildPlaylistId(playlistId: String) = "$PLAYLIST$SEP$playlistId"
        fun buildAlbumSongId(albumCoverId: String, songId: String) =
            "$ALBUM$SEP$albumCoverId$SEP$SONG_PREFIX$SEP$songId"
        fun buildPlaylistSongId(playlistId: String, songId: String) =
            "$PLAYLIST$SEP$playlistId$SEP$SONG_PREFIX$SEP$songId"

        fun extractAlbumCoverId(mediaId: String): String? {
            val parts = mediaId.split(SEP)
            return if (parts.size >= 2 && parts[0] == ALBUM) parts[1] else null
        }

        fun extractPlaylistId(mediaId: String): String? {
            val parts = mediaId.split(SEP)
            return if (parts.size >= 2 && parts[0] == PLAYLIST) parts[1] else null
        }

        fun extractSongId(mediaId: String): String? {
            val parts = mediaId.split(SEP)
            val idx = parts.indexOf(SONG_PREFIX)
            return if (idx >= 0 && idx + 1 < parts.size) parts[idx + 1] else null
        }

        private fun coverUri(albumId: String): Uri =
            Uri.parse("content://media/external/audio/albumart/$albumId")
    }

    private var cachedAlbums: List<LocalMediaRepository.LocalAlbum> = emptyList()
    private var cachedTracks: List<Track> = emptyList()
    private var cachedPlaylists: List<Pair<Playlist, List<Track>>> = emptyList()

    fun refresh() {
        cachedTracks = repository.loadTracks()
        cachedAlbums = repository.loadAlbums()
        cachedPlaylists = repository.loadPlaylists()
    }

    fun getRootChildren(): List<MediaItem> = listOf(
        MediaItem.Builder()
            .setMediaId(ALBUMS)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Albums")
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build(),
        MediaItem.Builder()
            .setMediaId(SONGS)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Songs")
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build(),
        MediaItem.Builder()
            .setMediaId(PLAYLISTS)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Playlists")
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build(),
    )

    fun getChildren(parentId: String): List<MediaItem> = when (parentId) {
        ALBUMS -> cachedAlbums.map { it.toBrowsableAlbum() }
        SONGS -> cachedTracks.map { it.toPlayableSong(it.coverId.let { cid -> "$ALBUM$SEP$cid" }) }
        PLAYLISTS -> cachedPlaylists.map { it.toBrowsablePlaylist() }
        else -> {
            val albumCoverId = extractAlbumCoverId(parentId)
            if (albumCoverId != null) {
                cachedAlbums
                    .firstOrNull { it.album.coverId == albumCoverId }
                    ?.tracks?.map { track ->
                        track.toPlayableSong(buildAlbumId(albumCoverId))
                    } ?: emptyList()
            } else {
                val playlistId = extractPlaylistId(parentId)
                if (playlistId != null) {
                    cachedPlaylists
                        .firstOrNull { it.first.id == playlistId }
                        ?.second?.map { track ->
                            track.toPlayableSong(buildPlaylistId(playlistId))
                        } ?: emptyList()
                } else emptyList()
            }
        }
    }

    fun getItem(mediaId: String): MediaItem? {
        val songId = extractSongId(mediaId)
        if (songId != null) {
            return cachedTracks.firstOrNull { it.id == songId }?.toPlayableSong()
        }
        val albumCoverId = extractAlbumCoverId(mediaId)
        if (albumCoverId != null) {
            return cachedAlbums.firstOrNull { it.album.coverId == albumCoverId }?.toBrowsableAlbum()
        }
        val playlistId = extractPlaylistId(mediaId)
        if (playlistId != null) {
            return cachedPlaylists.firstOrNull { it.first.id == playlistId }?.toBrowsablePlaylist()
        }
        return null
    }

    fun resolveForPlayback(mediaItems: List<MediaItem>): List<MediaItem> {
        val resolved = mutableListOf<MediaItem>()
        for (item in mediaItems) {
            val mediaId = item.mediaId
            when {
                mediaId == ALBUMS -> {
                    cachedAlbums.forEach { album ->
                        album.tracks.forEach { track ->
                            resolved.add(track.toPlayableSong(buildAlbumId(album.album.coverId)))
                        }
                    }
                }
                mediaId == SONGS -> {
                    cachedTracks.forEach { track ->
                        resolved.add(track.toPlayableSong())
                    }
                }
                mediaId == PLAYLISTS -> {
                    cachedPlaylists.forEach { (_, tracks) ->
                        tracks.forEach { track ->
                            resolved.add(track.toPlayableSong())
                        }
                    }
                }
                extractAlbumCoverId(mediaId) != null -> {
                    val albumCoverId = extractAlbumCoverId(mediaId)
                    cachedAlbums.firstOrNull { it.album.coverId == albumCoverId }?.tracks?.forEach { track ->
                        resolved.add(track.toPlayableSong(mediaId))
                    }
                }
                extractPlaylistId(mediaId) != null -> {
                    val playlistId = extractPlaylistId(mediaId)
                    cachedPlaylists.firstOrNull { it.first.id == playlistId }?.second?.forEach { track ->
                        resolved.add(track.toPlayableSong(mediaId))
                    }
                }
                extractSongId(mediaId) != null -> {
                    extractSongId(mediaId)?.let { songId ->
                        cachedTracks.firstOrNull { it.id == songId }?.let { track ->
                            resolved.add(track.toPlayableSong())
                        }
                    }
                }
            }
        }
        return resolved
    }

    private fun LocalMediaRepository.LocalAlbum.toBrowsableAlbum(): MediaItem =
        MediaItem.Builder()
            .setMediaId(buildAlbumId(album.coverId))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(album.title)
                    .setArtist(album.artist)
                    .setArtworkUri(coverUri(album.coverId))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()

    private fun Pair<Playlist, List<Track>>.toBrowsablePlaylist(): MediaItem =
        MediaItem.Builder()
            .setMediaId(buildPlaylistId(first.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(first.name)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()

    private fun Track.toPlayableSong(contextMediaId: String? = null): MediaItem {
        val uri = when {
            filepath.startsWith("content://") || filepath.startsWith("http://") || filepath.startsWith("https://") ->
                Uri.parse(filepath)
            filepath.startsWith("/") -> Uri.parse("file://$filepath")
            else -> Uri.parse(filepath)
        }
        val mediaId = contextMediaId?.let { "$it$SEP$SONG_PREFIX$SEP${this.id}" } ?: "${SONG_PREFIX}$SEP${this.id}"
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setTrackNumber(trackNumber)
                    .setArtworkUri(coverUri(coverId))
                    .build()
            )
            .build()
    }
}
