package org.michimusic.data.repository

import android.content.Context
import android.provider.MediaStore
import org.michimusic.core.models.Album
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

class LocalMediaRepository(private val context: Context) {

    fun loadTracks(): List<Track> {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
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
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1"
        val cursor = context.contentResolver.query(uri, projection, selection, null, "${MediaStore.Audio.Media.ALBUM} ASC, ${MediaStore.Audio.Media.TRACK} ASC")
            ?: return emptyList()

        val tracks = mutableListOf<Track>()
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

            while (c.moveToNext()) {
                val id = c.getString(idCol) ?: continue
                val data = c.getString(dataCol) ?: continue
                tracks.add(
                    Track(
                        id = "local_$id",
                        title = c.getString(titleCol) ?: "Unknown",
                        artist = c.getString(artistCol) ?: "Unknown",
                        album = c.getString(albumCol) ?: "Unknown",
                        duration = c.getLong(durCol),
                        size = c.getLong(sizeCol),
                        format = c.getString(mimeCol)?.substringAfterLast("/") ?: "",
                        trackNumber = c.getInt(trackCol),
                        year = c.getInt(yearCol),
                        filepath = data,
                        source = TrackSource.LOCAL,
                        coverId = c.getLong(albumIdCol).toString(),
                    )
                )
            }
        }
        return tracks
    }

    data class LocalAlbum(
        val album: Album,
        val tracks: List<Track>,
    )

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

        val uri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Playlists._ID,
            MediaStore.Audio.Playlists.NAME,
        )
        val cursor = context.contentResolver.query(uri, projection, null, null, "${MediaStore.Audio.Playlists.DATE_MODIFIED} DESC")
            ?: return emptyList()

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

    private fun loadPlaylistTracks(playlistId: Long, trackById: Map<String, Track>): List<Track> {
        val uri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
        val projection = arrayOf(
            MediaStore.Audio.Playlists.Members.AUDIO_ID,
        )
        val cursor = context.contentResolver.query(uri, projection, null, null, "${MediaStore.Audio.Playlists.Members.PLAY_ORDER} ASC")
            ?: return emptyList()

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
