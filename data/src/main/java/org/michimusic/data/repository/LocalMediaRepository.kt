package org.michimusic.data.repository

import android.content.Context
import android.provider.MediaStore
import org.michimusic.core.models.Album
import org.michimusic.core.models.Artist
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.ReplayGainDao
import org.michimusic.data.cache.ReplayGainEntity
import org.michimusic.data.local.MediaQueryDispatcher
import org.michimusic.data.local.ReplayGainReader

class LocalMediaRepository(
    private val context: Context,
    private val replayGainDao: ReplayGainDao,
) {

    companion object {
        private const val CACHE_TTL_MS = 30_000L
    }

    private var cachedTracks: List<Track>? = null
    private var cacheTime = 0L

    data class LocalAlbum(
        val album: Album,
        val tracks: List<Track>,
    )

    fun loadArtists(): List<Pair<Artist, List<LocalAlbum>>> {
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

    fun loadAlbums(): List<LocalAlbum> {
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
                    artist = artistName.ifEmpty { "Unknown Artist" },
                    year = first.year,
                    trackCount = albumTracks.size,
                    coverId = first.coverId,
                ),
                tracks = albumTracks,
            )
        }.sortedBy { it.album.title }
    }

    @Synchronized
    fun loadTracks(): List<Track> {
        val now = System.currentTimeMillis()
        if (cachedTracks != null && now - cacheTime < CACHE_TTL_MS) {
            return cachedTracks!!
        }
        val tracks = kotlinx.coroutines.runBlocking { queryTracks() }
        cachedTracks = tracks
        cacheTime = now
        return tracks
    }

    fun invalidateCache() {
        cachedTracks = null
    }

    private suspend fun queryTracks(): List<Track> {
        val cursor = MediaQueryDispatcher(
            context.contentResolver,
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
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dateCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)

            while (c.moveToNext()) {
                val id = c.getString(idCol) ?: continue
                val trackId = "local_$id"
                val data = c.getString(dataCol) ?: continue
                allTrackIds.add(trackId)
                tracks.add(Track(
                    id = trackId,
                    title = c.getString(titleCol) ?: "Unknown",
                    artist = c.getString(artistCol) ?: "Unknown",
                    album = c.getString(albumCol) ?: "Unknown",
                    albumId = c.getString(albumIdCol) ?: "",
                    duration = c.getLong(durCol).coerceAtLeast(0),
                    filepath = data,
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

        val rgBatch = replayGainDao.getAllReplayGains()
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
                replayGainDao.upsertAll(rgUpdates)
            }
        }
    }

    fun loadPlaylists(): List<Pair<Playlist, List<Track>>> {
        val allTracks = loadTracks()
        val trackById = allTracks.associateBy { it.id }

        val cursor = MediaQueryDispatcher(
            context.contentResolver,
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
                    context.contentResolver,
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
