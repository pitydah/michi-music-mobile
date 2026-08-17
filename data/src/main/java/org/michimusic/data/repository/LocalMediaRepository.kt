package org.michimusic.data.repository

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.michimusic.core.models.Album
import org.michimusic.core.models.Artist
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.ReplayGainDao
import org.michimusic.data.cache.ReplayGainEntity
import org.michimusic.data.local.MediaQueryDispatcher
import org.michimusic.data.local.ReplayGainReader

open class LocalMediaRepository(
    private val context: Context? = null,
    private val replayGainDao: ReplayGainDao? = null,
) {

    companion object {
        private const val CACHE_TTL_MS = 30_000L
    }

    private var cachedTracks: List<Track>? = null
    private var cacheTime = 0L
    private val cacheMutex = Mutex()

    data class LocalAlbum(
        val album: Album,
        val tracks: List<Track>,
    )

    open suspend fun loadArtists(): List<Pair<Artist, List<LocalAlbum>>> {
        val albums = loadAlbums()
        return albums.groupBy { it.album.artist }
            .map { (name, artistAlbums) ->
                val first = artistAlbums.first().album
                Artist(
                    id = name.lowercase().replace(" ", "_"),
                    name = name.ifEmpty { "Unknown" },
                    albumCount = artistAlbums.size,
                    trackCount = artistAlbums.sumOf { it.tracks.size },
                ) to artistAlbums
            }
            .sortedBy { it.first.name }
    }

    open suspend fun loadAlbums(): List<LocalAlbum> {
        val tracks = loadTracks()
        val grouped = tracks.groupBy { it.album to it.artist }
        return grouped.map { (albumArtist, albumTracks) ->
            val (albumName, artistName) = albumArtist
            val first = albumTracks.first()
            val albumId = "${albumName.lowercase().replace(" ", "_")}_${artistName.lowercase().replace(" ", "_")}"
            LocalAlbum(
                album = Album(
                    id = "album_$albumId",
                    title = albumName.ifEmpty { "Unknown Album" },
                    artist = artistName.ifEmpty { "Unknown" },
                    year = first.year,
                    trackCount = albumTracks.size,
                    coverId = first.coverId,
                ),
                tracks = albumTracks,
            )
        }.sortedBy { it.album.title }
    }

    open suspend fun loadTracks(): List<Track> = cacheMutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (cachedTracks != null && now - cacheTime < CACHE_TTL_MS) {
                return@withContext cachedTracks!!
            }
            val tracks = queryTracks()
            cachedTracks = tracks
            cacheTime = now
            tracks
        }
    }

    open suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedTracks = null
        }
    }

    private suspend fun queryTracks(): List<Track> {
        val ctx = context ?: return emptyList()
        val cursor = MediaQueryDispatcher(
            ctx.contentResolver,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        )
            .withColumns(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATE_ADDED,
            )
            .setSelection("${MediaStore.Audio.Media.IS_MUSIC} = ?")
            .addSelection("1")
            .setSortOrder(MediaStore.Audio.Media.TITLE + " ASC")
            .dispatch()

        if (cursor == null) return emptyList()

        val tracks = mutableListOf<Track>()
        val allTrackIds = mutableListOf<String>()

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (c.moveToNext()) {
                val id = c.getString(idCol) ?: continue
                val trackId = "local_$id"
                // Build content URI; works on Android Q+ where direct file path may be inaccessible.
                val contentUri = android.content.ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id.toLong()
                ).toString()
                allTrackIds.add(trackId)
                tracks.add(Track(
                    id = trackId,
                    title = c.getString(titleCol) ?: "Unknown",
                    artist = c.getString(artistCol) ?: "Unknown",
                    album = c.getString(albumCol) ?: "Unknown",
                    albumId = c.getString(albumIdCol) ?: "",
                    duration = c.getLong(durCol).coerceAtLeast(0),
                    filepath = contentUri,
                    trackNumber = c.getInt(trackCol).coerceAtLeast(0),
                    year = c.getInt(yearCol).coerceAtLeast(0),
                    size = c.getLong(sizeCol).coerceAtLeast(0),
                    format = (c.getString(mimeCol) ?: "").substringAfterLast("/"),
                    coverId = c.getString(albumIdCol) ?: "",
                    dateAdded = if (dateCol >= 0) c.getLong(dateCol) else 0L,
                    source = TrackSource.LOCAL,
                ))
            }
        }

        val rgBatch = replayGainDao?.getAllReplayGains() ?: emptyList()
        val rgMap = rgBatch.associateBy { it.trackId }

        val rgUpdates = mutableListOf<ReplayGainEntity>()
        return tracks.map { track ->
            val cached = rgMap[track.id]
            if (cached != null) {
                track.copy(replayGainTrack = cached.trackGain, replayGainAlbum = cached.albumGain)
            } else {
                val parsed = ReplayGainReader.read(track.filepath)
                rgUpdates.add(ReplayGainEntity(track.id, parsed.trackGain, parsed.albumGain))
                track.copy(replayGainTrack = parsed.trackGain, replayGainAlbum = parsed.albumGain)
            }
        }.also {
            if (rgUpdates.isNotEmpty()) {
                replayGainDao?.upsertAll(rgUpdates)
            }
        }
    }

    open suspend fun loadPlaylists(): List<Pair<Playlist, List<Track>>> {
        val ctx = context ?: return emptyList()
        val allTracks = loadTracks()
        val trackById = allTracks.associateBy { it.id }

        val cursor = MediaQueryDispatcher(
            ctx.contentResolver,
            MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
        )
            .withColumns(
                MediaStore.Audio.Playlists._ID,
                MediaStore.Audio.Playlists.NAME,
            )
            .dispatch()

        if (cursor == null) return emptyList()

        val playlists = mutableListOf<Pair<Playlist, List<Track>>>()
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME)

            while (c.moveToNext()) {
                val playlistId = c.getString(idCol) ?: continue
                val playlistName = c.getString(nameCol) ?: continue

                val trackCursor = MediaQueryDispatcher(
                    ctx.contentResolver,
                    MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId.toLong()),
                )
                    .withColumns(MediaStore.Audio.Playlists.Members.AUDIO_ID)
                    .dispatch()

                val tracks = mutableListOf<Track>()
                trackCursor?.use { tc ->
                    val audioIdCol = tc.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.AUDIO_ID)
                    while (tc.moveToNext()) {
                        val audioId = "local_${tc.getString(audioIdCol) ?: continue}"
                        trackById[audioId]?.let { tracks.add(it) }
                    }
                }

                playlists.add(
                    Playlist(id = playlistId, name = playlistName) to tracks,
                )
            }
        }
        return playlists
    }
}
