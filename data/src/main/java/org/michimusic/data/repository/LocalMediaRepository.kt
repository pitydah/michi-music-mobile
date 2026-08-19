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
            val cached = cachedTracks
            val cacheHit = cached != null && now - cacheTime < CACHE_TTL_MS
            if (cacheHit) {
                return@withContext cached!!
            }
            // A null result means the query itself failed (e.g. no permission yet) rather
            // than the library legitimately being empty, so it must not be cached - otherwise
            // a permission granted right after a failed query would stay masked by the cache.
            val result = queryTracks()
            if (result != null) {
                cachedTracks = result
                cacheTime = now
                result
            } else {
                emptyList()
            }
        }
    }

    open suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedTracks = null
        }
    }

    // Returns null when the MediaStore query itself failed (e.g. SecurityException because
    // the audio permission wasn't granted yet), as opposed to emptyList() which means the
    // query ran fine and the device legitimately has no tracks. Callers must not cache null.
    // Open/internal so tests can substitute the MediaStore-dependent part while exercising
    // the real caching logic in loadTracks().
    internal open suspend fun queryTracks(): List<Track>? {
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
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.IS_RINGTONE,
                MediaStore.Audio.Media.IS_ALARM,
                MediaStore.Audio.Media.IS_NOTIFICATION,
                MediaStore.Audio.Media.IS_PODCAST,
                MediaStore.Audio.Media.IS_AUDIOBOOK,
                MediaStore.Audio.Media.IS_RECORDING,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
            )
            // No IS_MUSIC selection: that heuristic column is unreliable for files MediaStore
            // never explicitly tagged as music (e.g. tracks downloaded outside the Music/
            // folder). Every audio row is fetched instead, and only the categories that are
            // definitely not songs are excluded below via isRealTrack().
            .setSortOrder(MediaStore.Audio.Media.TITLE + " ASC")
            .dispatch()

        if (cursor == null) {
            return null
        }

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
            val ringtoneCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE)
            val alarmCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM)
            val notificationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION)
            val podcastCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PODCAST)
            val audiobookCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_AUDIOBOOK)
            val recordingCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RECORDING)
            val relativePathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)

            while (c.moveToNext()) {
                val id = c.getString(idCol) ?: continue
                if (!isRealTrack(
                        isRingtone = c.getInt(ringtoneCol) == 1,
                        isAlarm = c.getInt(alarmCol) == 1,
                        isNotification = c.getInt(notificationCol) == 1,
                        isPodcast = c.getInt(podcastCol) == 1,
                        isAudiobook = c.getInt(audiobookCol) == 1,
                    )
                ) {
                    continue
                }
                val isRecording = c.getInt(recordingCol) == 1
                val relativePath = c.getString(relativePathCol)
                val bucketDisplayName = c.getString(bucketCol)
                if (isNonMusicAudio(isRecording, relativePath, bucketDisplayName)) {
                    continue
                }
                val title = c.getString(titleCol)
                val artist = c.getString(artistCol)
                val album = c.getString(albumCol)
                val duration = c.getLong(durCol)
                if (isLikelyNonMusicGenericAudio(title, artist, album, relativePath, bucketDisplayName, duration)) {
                    continue
                }
                val trackId = "local_$id"
                // Build content URI; works on Android Q+ where direct file path may be inaccessible.
                val contentUri = android.content.ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id.toLong()
                ).toString()
                allTrackIds.add(trackId)
                tracks.add(Track(
                    id = trackId,
                    title = title ?: "Unknown",
                    artist = artist ?: "Unknown",
                    album = album ?: "Unknown",
                    albumId = c.getString(albumIdCol) ?: "",
                    duration = duration.coerceAtLeast(0),
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
                // NaN is the project-wide sentinel for "no ReplayGain data" (see
                // ReplayGainAudioProcessor's isNaN() checks) - it must stay NaN on the Track
                // for that reason, but must never reach replaygain_cache: SQLite binds a NaN
                // REAL as SQL NULL, which violates trackGain/albumGain's NOT NULL constraint.
                // Simply not caching an incomplete reading preserves "unavailable" correctly
                // without a schema change; the next load retries the read.
                if (isReplayGainPersistable(parsed)) {
                    rgUpdates.add(ReplayGainEntity(track.id, parsed.trackGain, parsed.albumGain))
                }
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

// A row is a real, playable track unless MediaStore explicitly categorizes it as one of
// these system/utility audio types. Deliberately does not consider IS_MUSIC: that column is
// unreliable for legitimate songs MediaStore never tagged as music. internal so it can be
// unit tested directly, without Robolectric/MediaStore.
internal fun isRealTrack(
    isRingtone: Boolean,
    isAlarm: Boolean,
    isNotification: Boolean,
    isPodcast: Boolean,
    isAudiobook: Boolean,
): Boolean = !isRingtone && !isAlarm && !isNotification && !isPodcast && !isAudiobook

// Case-insensitive folder-name fragments that reliably identify chat apps, video editors,
// and voice/call recorders - not song libraries. Deliberately narrow: ordinary folders like
// "Music", "Download", or a custom user folder must never match any of these.
private val NON_MUSIC_FOLDER_PATTERNS = listOf(
    "whatsapp",
    "whatsapp audio",
    "whatsapp voice notes",
    "inshot",
    "voice recorder",
    "recorder",
    "recordings",
    "call recordings",
    "sound recorder",
)

// True when the row's IS_RECORDING flag or its folder location (RELATIVE_PATH/
// BUCKET_DISPLAY_NAME) marks it as non-song audio, as opposed to a real song with poor or
// missing tags - metadata quality (artist/album/title) is deliberately not a signal here,
// since legitimate untagged songs in Download/Music must not be excluded by it.
// internal so it can be unit tested directly, without Robolectric/MediaStore.
internal fun isNonMusicAudio(
    isRecording: Boolean,
    relativePath: String?,
    bucketDisplayName: String?,
): Boolean {
    if (isRecording) return true
    val haystack = "${relativePath.orEmpty()} ${bucketDisplayName.orEmpty()}".lowercase()
    return NON_MUSIC_FOLDER_PATTERNS.any { haystack.contains(it) }
}

// MediaStore's own sentinel for a missing tag ("<unknown>"), plus this repository's fallback
// ("Unknown", used once the cursor value is mapped onto Track) - both count as "no metadata".
private val UNKNOWN_METADATA_VALUES = setOf("<unknown>", "unknown")

private fun isUnknownMetadata(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.isEmpty() || normalized in UNKNOWN_METADATA_VALUES
}

private val UUID_PATTERN =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val LONG_HEX_PATTERN = Regex("^[0-9a-fA-F]{16,}$")
private val LONG_OPAQUE_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{20,}$")

// A title that looks machine-generated (a hash, a UUID, or a long opaque token) rather than
// something a person or a tagger would have written. Deliberately narrow: ordinary titles,
// even short or oddly-worded ones, must never match - only long, essentially-random-looking
// strings do. internal so it can be unit tested directly.
internal fun looksLikeGeneratedIdentifier(title: String?): Boolean {
    val trimmed = title?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    if (UUID_PATTERN.matches(trimmed)) return true
    if (LONG_HEX_PATTERN.matches(trimmed)) return true
    return LONG_OPAQUE_TOKEN_PATTERN.matches(trimmed) &&
        trimmed.any { it.isDigit() } &&
        trimmed.any { it.isLetter() }
}

private val MUSIC_FOLDER_PATTERNS = listOf("music")
private val DOWNLOAD_FOLDER_PATTERNS = listOf("download")
private val SUSPICIOUS_GENERIC_FOLDER_PATTERNS = listOf(
    "cache",
    "temp",
    "tmp",
    "android/data",
    "android/media",
    ".thumbnails",
    "thumbnails",
)

// MediaStore fills ALBUM with the literal folder name "Download" for untagged files placed
// there, so that value must be treated as "no album" for the Download-specific check below -
// but only there, since a real album legitimately named "Download" elsewhere must not be
// affected.
private fun isUnknownOrDownloadAlbum(album: String?): Boolean =
    isUnknownMetadata(album) || album?.trim()?.equals("download", ignoreCase = true) == true

// A conservative duration ceiling for the Download-folder generic-audio exception: short
// enough to comfortably cover clips/notifications/voice snippets, well below any real song.
// Duration alone never excludes a track - it only narrows an already-suspicious combination
// of title/artist/album signals (see isLikelyNonMusicGenericAudio).
private const val SUSPICIOUS_DOWNLOAD_AUDIO_MAX_DURATION_MS = 60_000L

// Excludes a row only when several strong signals agree at once. The general case requires
// unknown artist AND album, a title that looks machine-generated, and a folder that is neither
// a recognized music location (Music) nor an unrecognized "normal" custom folder, but one that
// looks like app cache/temp/private media storage. Any single signal alone - unknown tags, an
// odd title, or an unusual folder - is deliberately not enough: real songs commonly have
// exactly one of these without being non-music audio, and an unrecognized custom folder is
// treated as safe, not suspicious.
//
// Download gets narrower, additional handling: it stays protected for normal music (an odd
// title or missing tags alone is not enough there either), but a title that looks
// machine-generated (hash/UUID/token) combined with unknown artist, an unknown-or-"Download"
// album, AND a short duration (< SUSPICIOUS_DOWNLOAD_AUDIO_MAX_DURATION_MS) is excluded - this
// is the exact signature MediaStore leaves on short non-music clips saved into Download (e.g.
// WhatsApp/browser audio blobs) that never got real tags. A hash-titled but long file, or one
// with any real artist/album tag, is left alone. internal so it can be unit tested directly,
// without Robolectric/MediaStore.
internal fun isLikelyNonMusicGenericAudio(
    title: String?,
    artist: String?,
    album: String?,
    relativePath: String?,
    bucketDisplayName: String?,
    durationMs: Long,
): Boolean {
    if (!looksLikeGeneratedIdentifier(title)) return false
    val haystack = "${relativePath.orEmpty()} ${bucketDisplayName.orEmpty()}".lowercase()
    val isMusicFolder = MUSIC_FOLDER_PATTERNS.any { haystack.contains(it) }
    if (isMusicFolder) return false

    val isDownloadFolder = DOWNLOAD_FOLDER_PATTERNS.any { haystack.contains(it) }
    if (isDownloadFolder) {
        return isUnknownMetadata(artist) &&
            isUnknownOrDownloadAlbum(album) &&
            durationMs < SUSPICIOUS_DOWNLOAD_AUDIO_MAX_DURATION_MS
    }

    if (!isUnknownMetadata(artist) || !isUnknownMetadata(album)) return false
    return SUSPICIOUS_GENERIC_FOLDER_PATTERNS.any { haystack.contains(it) }
}

// ReplayGainEntity.trackGain/albumGain are non-nullable REAL columns. SQLite binds a NaN
// REAL as SQL NULL (it has no NaN storage class), so caching a reading with either value
// still NaN - meaning no tag was found for it - would violate their NOT NULL constraint.
// internal so it can be unit tested directly, without Room/Robolectric.
internal fun isReplayGainPersistable(data: ReplayGainReader.ReplayGainData): Boolean =
    data.trackGain.isFinite() && data.albumGain.isFinite()
