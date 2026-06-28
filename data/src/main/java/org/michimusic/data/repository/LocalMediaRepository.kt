package org.michimusic.data.repository

import android.content.Context
import android.provider.MediaStore
import org.michimusic.core.models.Album
import org.michimusic.core.models.Artist
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import kotlinx.coroutines.runBlocking
import org.michimusic.data.cache.ReplayGainDao
import org.michimusic.data.cache.ReplayGainEntity
import org.michimusic.data.local.MediaQueryDispatcher
import org.michimusic.data.local.ReplayGainReader

class LocalMediaRepository(
    private val context: Context,
    private val replayGainDao: ReplayGainDao,
) {

    private var cachedTracks: List<Track>? = null
    private var cacheTime = 0L

    private val cacheTtlMs = 30_000L

    fun invalidateCache() {
        cachedTracks = null
        cacheTime = 0L
    }

    fun loadTracks(): List<Track> {
        val now = System.currentTimeMillis()
        if (cachedTracks != null && now - cacheTime < cacheTtlMs) {
            return cachedTracks!!
        }
        val tracks = queryTracks()
        cachedTracks = tracks
        cacheTime = now
        return tracks
    }

    private fun queryTracks(): List<Track> {
        val cursor = MediaQueryDispatcher(context.contentResolver)
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
            .setSelection("${MediaStore.Audio.Media.IS_MUSIC} = 1")
            .setSortOrder("${MediaStore.Audio.Media.ALBUM} ASC, ${MediaStore.Audio.Media.TRACK} ASC")
            .dispatch()

        if (cursor == null) return emptyList()

        val tracks = mutableListOf<Track>()
        val rgCache = mutableMapOf<String, ReplayGainEntity>()
        val rgUpdates = mutableListOf<ReplayGainEntity>()

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

                var replayGainTrack = Float.NaN
                var replayGainAlbum = Float.NaN
                val cached = rgCache[trackId]
                    ?: runBlocking { replayGainDao.getReplayGain(trackId) }
                if (cached != null) {
                    rgCache[trackId] = cached
                    replayGainTrack = cached.trackGain
                    replayGainAlbum = cached.albumGain
                } else {
                    val parsed = ReplayGainReader.read(data)
                    replayGainTrack = parsed.trackGain
                    replayGainAlbum = parsed.albumGain
                    rgUpdates.add(ReplayGainEntity(trackId, parsed.trackGain, parsed.albumGain))
                }

                tracks.add(
                    Track(
                        id = trackId,
                        title = c.getString(titleCol) ?: "Unknown",
                        artist = c.getString(artistCol) ?: "Unknown",
                        album = c.getString(albumCol) ?: "Unknown",
                        duration = c.getLong(durCol),
                        size = c.getLong(sizeCol),
                        format = c.getString(mimeCol)?.substringAfterLast("/") ?: "",
                        trackNumber = c.getInt(trackCol),
                        year = c.getInt(yearCol),
                        filepath = data,
                        dateAdded = if (dateCol >= 0) c.getLong(dateCol) else 0L,
                        source = TrackSource.LOCAL,
                        coverId = c.getLong(albumIdCol).toString(),
                        replayGainTrack = replayGainTrack,
                        replayGainAlbum = replayGainAlbum,
                    )
                )
            }
        }

        if (rgUpdates.isNotEmpty()) {
            runBlocking { replayGainDao.upsertAll(rgUpdates) }
        }

        return tracks
    }

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
        val grouped = tracks.groupBy { it.coverId }
        return grouped.map { (albumId, albumTracks) ->
            val first = albumTracks.first()
            LocalAlbum(
                album = Album(
                    id = "album_$albumId",
                    title = first.album,
                    artist = first.artist,
                    year = first.year,
                    trackCount = albumTracks.size,
                    coverId = albumId,
                ),
                tracks = albumTracks,
            )
        }.sortedBy { it.album.title }
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
            .setSortOrder("${MediaStore.Audio.Playlists.DATE_MODIFIED} DESC")
            .dispatch()

        if (cursor == null) return emptyList()

        val result = mutableListOf<Pair<Playlist, List<Track>>>()
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME)

            while (c.moveToNext()) {
                val playlistId = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue

                val tracks = loadPlaylistTracks(playlistId, trackById)
                result.add(
                    Playlist(
                        id = "pl_$playlistId",
                        name = name,
                        trackIds = tracks.map { it.id },
                        trackCount = tracks.size,
                    ) to tracks
                )
            }
        }
        return result
    }

    private fun loadPlaylistTracks(
        playlistId: Long,
        trackById: Map<String, Track>,
    ): List<Track> {
        val cursor = MediaQueryDispatcher(
            context.contentResolver,
            MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId),
        )
            .withColumns(MediaStore.Audio.Playlists.Members.AUDIO_ID)
            .setSortOrder("${MediaStore.Audio.Playlists.Members.PLAY_ORDER} ASC")
            .dispatch()

        if (cursor == null) return emptyList()

        val tracks = mutableListOf<Track>()
        cursor.use { c ->
            val audioIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.AUDIO_ID)
            while (c.moveToNext()) {
                val audioId = c.getLong(audioIdCol)
                trackById["local_$audioId"]?.let { tracks.add(it) }
            }
        }
        return tracks
    }
}
