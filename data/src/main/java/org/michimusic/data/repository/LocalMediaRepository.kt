package org.michimusic.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.michimusic.core.models.Album
import org.michimusic.core.models.Artist
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.EmbeddedTagEvidence
import org.michimusic.data.cache.RecoveredSafGenre
import org.michimusic.data.cache.LibraryExclusionDao
import org.michimusic.data.cache.LibraryExclusionEntity
import org.michimusic.data.cache.ReplayGainDao
import org.michimusic.data.cache.ReplayGainEntity
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.cache.toTrack
import org.michimusic.data.local.MediaQueryDispatcher
import org.michimusic.data.local.ReplayGainReader

open class LocalMediaRepository(
    private val context: Context? = null,
    private val replayGainDao: ReplayGainDao? = null,
    private val importedTrackDao: TrackDao? = null,
    private val rootPreferences: MusicRootPreferences? = null,
    private val libraryExclusionDao: LibraryExclusionDao? = null,
) {

    private data class PendingTrack(val track: Track)

    companion object {
        private const val MAX_EMBEDDED_GENRE_READS_PER_QUERY = 32
        private const val CACHE_TTL_MS = 30_000L

        /** Android 13+ split audio permission; Android 12 still uses the storage grant. */
        private fun hasAudioPermission(context: Context): Boolean {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Position of the next batch across repeated foreground library refreshes. */
    private var nextGenreScanRow = 0
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

    /**
     * Legacy list API kept for callers and test doubles that override [loadTracks]; the
     * typed path used by the UI is [fetchAlbumsResult], which never loses the failure.
     */
    open suspend fun loadAlbums(): List<LocalAlbum> = groupAlbums(loadTracks())

    /**
     * Typed variant: a denied permission or a failed MediaStore query must reach the UI
     * as such, never as an empty album list.
     */
    open suspend fun fetchAlbumsResult(): MediaResult<List<LocalAlbum>> =
        when (val tracks = fetchTracksResult()) {
            is MediaResult.Success -> MediaResult.Success(groupAlbums(tracks.data), tracks.coverage)
            is MediaResult.PermissionDenied -> MediaResult.PermissionDenied
            is MediaResult.QueryFailed -> tracks
        }

    internal fun groupAlbums(tracks: List<Track>): List<LocalAlbum> {
        // MediaStore's album ID is the authoritative disc identity, so a
        // compilation stays one album even when every song has a guest artist.
        // Tag-based libraries group by album title + album artist; the track
        // artist is only a last-resort fallback when no album credit exists.
        val grouped = tracks.groupBy { track ->
            if (track.albumId.isNotBlank()) {
                "mediastore:${track.albumId}"
            } else {
                val albumArtist = track.albumArtist.ifBlank { track.artist }
                "tags:${track.album.trim().lowercase()}|${albumArtist.trim().lowercase()}"
            }
        }
        return grouped.map { (_, albumTracks) ->
            val first = albumTracks.first()
            val albumName = first.album.ifEmpty { "Unknown Album" }
            val artistName = first.albumArtist.ifBlank { first.artist }.ifEmpty { "Unknown" }
            val albumId = if (first.albumId.isNotBlank()) {
                "mediastore_${first.albumId}"
            } else {
                "${albumName.lowercase().replace(" ", "_")}_${artistName.lowercase().replace(" ", "_")}"
            }
            LocalAlbum(
                album = Album(
                    id = "album_$albumId",
                    title = albumName,
                    artist = artistName,
                    year = first.year,
                    trackCount = albumTracks.size,
                    coverId = first.coverId,
                ),
                tracks = albumTracks,
            )
        }.sortedBy { it.album.title }
    }

    open suspend fun fetchTracksResult(): MediaResult<List<Track>> = cacheMutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = cachedTracks
            val cacheHit = cached != null && now - cacheTime < CACHE_TTL_MS
            if (cacheHit) {
                return@withContext MediaResult.Success(cached!!)
            }
            val result = queryTracksResult()
            if (result is MediaResult.Success) {
                // The media index is complete. User exclusions are a visibility overlay,
                // never a DELETE from MediaStore, SAF, Room cached tracks or filesystem.
                val root = rootPreferences?.currentRoot() ?: MusicRoot.Default
                val source = libraryVisibilitySource(root)
                val excluded = libraryExclusionDao?.forSource(source)
                    ?.associateBy { it.trackId }.orEmpty()
                val visible = result.data.filterNot { track ->
                    isLibraryTrackHidden(excluded[track.id], source, track)
                }
                cachedTracks = visible
                cacheTime = now
                MediaResult.Success(visible, result.coverage)
            } else result
        }
    }

    open suspend fun loadTracks(): List<Track> {
        return when (val result = fetchTracksResult()) {
            is MediaResult.Success -> result.data
            is MediaResult.PermissionDenied -> emptyList()
            is MediaResult.QueryFailed -> emptyList()
        }
    }

    open suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedTracks = null
        }
    }
    /**
     * Manual, cancelable recovery. Every 32-track page is a committed Room
     * transaction + synchronized cache receipt. A new explicit invocation
     * resumes from genres still missing; no worker or durable cursor is needed.
     * Previously confirmed pages remain valid if the user stops mid-run.
     */
    suspend fun recoverAllMissingGenres(
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): MediaResult<List<Track>> {
        val current = fetchTracksResult()
        if (current !is MediaResult.Success) return current
        val ctx = context ?: return current
        val root = rootPreferences?.currentRoot() ?: MusicRoot.Default
        val owner = if (root.isDefault) TagEvidencePolicy.MEDIASTORE_OWNER
            else root.sourceId ?: libraryVisibilitySource(root)
        val original = current.data
        val pending = original.map {
            GenreMetadataMerge.choose(null, it.genre, null).isBlank()
        }.toMutableList()
        val total = pending.count { it }
        if (total == 0) return current
        return withContext(Dispatchers.IO) {
            var snapshot = original
            var completed = 0
            var next = 0
            onProgress(0, total)
            while (completed < total) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val batch = GenreBackfill.nextBatch(pending, next, 32, total - completed)
                if (batch.indices.isEmpty()) break
                // Never allocate a whole-source evidence map for one manual page.
                val indexedEvidence: Map<String, EmbeddedTagEvidence> = if (root.isDefault) emptyMap()
                    else try {
                        val dao = importedTrackDao ?: throw IllegalStateException("Sin índice SAF")
                        dao.getTagEvidenceForTracks(owner, batch.indices.map { snapshot[it].id })
                            .associateBy { it.trackId }
                    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        return@withContext MediaResult.QueryFailed(
                            IllegalStateException("Falló la lectura del índice SAF", failure))
                    }
                val proposals = HashMap<Int, Track>()
                val freshEvidence = ArrayList<EmbeddedTagEvidence>()
                val safUpdates = ArrayList<RecoveredSafGenre>()
                for (i in batch.indices) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val song = snapshot[i]
                    val uri = android.net.Uri.parse(song.filepath)
                    val sizeKey = if (root.isDefault) MediaStore.Audio.Media.SIZE
                        else android.provider.DocumentsContract.Document.COLUMN_SIZE
                    val modifiedKey = if (root.isDefault) MediaStore.Audio.Media.DATE_MODIFIED
                        else android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    fun stamp(): RecoveryDocumentStamp? = try {
                        ctx.contentResolver.query(uri, arrayOf(sizeKey, modifiedKey), null, null, null)
                            ?.use { cursor ->
                                if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) null
                                else RecoveryDocumentStamp(cursor.getLong(0), cursor.getLong(1))
                            }
                    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                    val before = stamp()
                    val fields = if (before != null) UriEmbeddedTags.read(ctx, uri) else null
                    val after = if (fields != null) stamp() else null
                    val trusted = RecoveryFingerprintPolicy.isCurrent(
                        owner, song.id, song.size, before, after,
                        indexedEvidence[song.id], root.isDefault,
                    )
                    val values = TagGenreBounds.safeValues(fields)
                    val genre = if (trusted && !values.isNullOrEmpty())
                        GenreMetadataMerge.choose(values.joinToString("; "), null, null) else ""
                    if (genre.isNotBlank() && fields != null) {
                        proposals[i] = song.copy(genre = genre, genreValues = values.orEmpty())
                        if (!root.isDefault) {
                            safUpdates.add(RecoveredSafGenre(
                                owner, song.id, song.filepath, song.size, genre, before!!.modifiedAt))
                        }
                        freshEvidence.add(TagEvidencePolicy.record(
                            owner, song.id, song.size, before!!.modifiedAt, fields))
                    }
                }
                // Atomic unit of progress is ONE page: never advertise it as done
                // until its source-owned Room transaction and cache receipt agree.
                val pageResult = cacheMutex.withLock {
                    if (cachedTracks !== snapshot ||
                        (rootPreferences?.currentRoot() ?: MusicRoot.Default) != root) {
                        return@withLock MediaResult.QueryFailed(
                            IllegalStateException("La biblioteca cambió durante la recuperación"))
                    }
                    try {
                        val applied = importedTrackDao?.persistRecoveredGenres(freshEvidence, safUpdates)
                            ?: if (root.isDefault) emptySet() else throw IllegalStateException(
                                "No está disponible el índice SAF para confirmar la página")
                        val confirmed = RecoveryPageCommitPolicy.apply(
                            snapshot, proposals, applied, owner, root.isDefault)
                        cachedTracks = confirmed
                        cacheTime = System.currentTimeMillis()
                        MediaResult.Success(confirmed, current.coverage)
                    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (error: Exception) { MediaResult.QueryFailed(error) }
                }
                if (pageResult !is MediaResult.Success) return@withContext pageResult
                snapshot = pageResult.data
                for (i in batch.indices) pending[i] = false
                completed += batch.indices.size
                next = batch.nextCursor
                onProgress(completed, total)
                kotlinx.coroutines.yield()
            }
            MediaResult.Success(snapshot, current.coverage)
        }
    }

    /**
     * Explicit, READ-ONLY comparison of existing provider/cache genre with embedded tags.
     * A tap verifies up to 128 tracks; subsequent taps advance by [startOffset].
     * No genre mutations, automatic classification, background workers or full-file hashes.
     * The existing fetchTracksResult may still perform its normal foreground cache refresh.
     */
    suspend fun verifyExistingGenres(
        startOffset: Int = 0,
        expectedSnapshotToken: String? = null,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): MediaResult<ExistingGenreVerificationReport> {
        val current = fetchTracksResult()
        if (current !is MediaResult.Success) return when (current) {
            MediaResult.PermissionDenied -> MediaResult.PermissionDenied
            is MediaResult.QueryFailed -> current
            else -> MediaResult.QueryFailed(IllegalStateException("Biblioteca no disponible"))
        }
        val ctx = context ?: return MediaResult.QueryFailed(
            IllegalStateException("Biblioteca local no disponible"))
        val root = rootPreferences?.currentRoot() ?: MusicRoot.Default
        val owner = if (root.isDefault) TagEvidencePolicy.MEDIASTORE_OWNER
            else root.sourceId ?: libraryVisibilitySource(root)
        return withContext(Dispatchers.IO) {
            // Even the O(library-size) in-memory eligibility filter stays off Main.
            val eligible = current.data.filter { song ->
                song.filepath.isNotBlank() &&
                    GenreMetadataMerge.choose(null, song.genre, null).isNotBlank()
            }
            val snapshotToken = ExistingGenreVerificationPolicy.snapshotToken(owner, eligible)
            // Offset alone is unsafe. Refuse negative, out-of-range or stale cursors.
            val start = ExistingGenreVerificationPolicy.continuationStart(
                startOffset, eligible.size, snapshotToken, expectedSnapshotToken)
                ?: return@withContext MediaResult.QueryFailed(
                    IllegalStateException("El cursor o catálogo cambió; reinicia la verificación"))
            val page = eligible.subList(start,
                minOf(start + ExistingGenreVerificationPolicy.PAGE_SIZE, eligible.size))
            val indexed = if (root.isDefault) emptyMap() else try {
                val dao = importedTrackDao ?: throw IllegalStateException("Sin índice SAF")
                dao.getTagEvidenceForTracks(owner, page.map { it.id })
                    .associateBy { it.trackId }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Exception) {
                return@withContext MediaResult.QueryFailed(failure)
            }
            var matching = 0
            var differing = 0
            var noGenre = 0
            var unreadable = 0
            var permissionDenied = 0
            var unverifiable = 0
            val differences = ArrayList<ExistingGenreDifference>()
            onProgress(0, page.size)
            for ((position, song) in page.withIndex()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if ((rootPreferences?.currentRoot() ?: MusicRoot.Default) != root)
                    return@withContext MediaResult.QueryFailed(
                        IllegalStateException("La biblioteca cambió durante la verificación"))
                val uri = android.net.Uri.parse(song.filepath)
                val sizeColumn = if (root.isDefault) MediaStore.Audio.Media.SIZE
                    else android.provider.DocumentsContract.Document.COLUMN_SIZE
                val modifiedColumn = if (root.isDefault) MediaStore.Audio.Media.DATE_MODIFIED
                    else android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
                fun stamp(): RecoveryDocumentStamp? = try {
                    ctx.contentResolver.query(uri, arrayOf(sizeColumn, modifiedColumn), null, null, null)
                        ?.use { cursor ->
                            if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) null
                            else RecoveryDocumentStamp(cursor.getLong(0), cursor.getLong(1))
                        }
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { null }
                val before = stamp()
                val outcome = if (before == null) EmbeddedTagReadResult.Unverifiable
                    else UriEmbeddedTags.readDetailed(ctx, uri)
                val fields = EmbeddedTagReadPolicy.fieldsOrNull(outcome)
                val after = if (fields != null) stamp() else null
                val trusted = RecoveryFingerprintPolicy.isCurrent(
                    owner, song.id, song.size, before, after, indexed[song.id], root.isDefault)
                when {
                    outcome == EmbeddedTagReadResult.PermissionDenied -> {
                        unreadable++
                        permissionDenied++
                    }
                    outcome == EmbeddedTagReadResult.ReadFailed -> unreadable++
                    outcome == EmbeddedTagReadResult.Unverifiable -> unverifiable++
                    !trusted -> unverifiable++
                    outcome is EmbeddedTagReadResult.NoGenreTag -> noGenre++
                    outcome is EmbeddedTagReadResult.Parsed ->
                        when (ExistingGenreVerificationPolicy.compare(song.genre, fields)) {
                            ExistingGenreComparison.MATCH -> matching++
                            ExistingGenreComparison.DIFFERENT -> {
                                differing++
                                if (differences.size < ExistingGenreVerificationPolicy.MAX_VISIBLE_DIFFERENCES) {
                                    differences.add(ExistingGenreDifference(
                                        song.id, song.title, song.genre, outcome.fields.genre.orEmpty()))
                                }
                            }
                            ExistingGenreComparison.NO_EMBEDDED_GENRE -> noGenre++
                            ExistingGenreComparison.READ_FAILED -> unreadable++
                        }
                }
                if ((position + 1) % 8 == 0 || position + 1 == page.size)
                    onProgress(position + 1, page.size)
                if ((position + 1) % 16 == 0) kotlinx.coroutines.yield()
            }
            if ((rootPreferences?.currentRoot() ?: MusicRoot.Default) != root ||
                !cacheMutex.withLock { cachedTracks === current.data }) {
                return@withContext MediaResult.QueryFailed(
                    IllegalStateException("La biblioteca cambió durante la verificación"))
            }
            MediaResult.Success(ExistingGenreVerificationReport(
                examined = page.size,
                candidates = eligible.size,
                nextOffset = (start + page.size).takeIf { it < eligible.size },
                snapshotToken = snapshotToken,
                matching = matching,
                differing = differing,
                withoutEmbeddedGenre = noGenre,
                unreadable = unreadable,
                permissionDenied = permissionDenied,
                unverifiable = unverifiable,
                differences = differences,
            ), current.coverage)
        }
    }

    /** Explicit catalog command: no file system API is called by either mutation. */
    suspend fun hideFromCurrentLibrary(
        trackId: String,
        expectedUri: String,
        expectedFingerprint: String? = null,
    ): LocalVisibilityResult {
        val dao = libraryExclusionDao
            ?: return LocalVisibilityResult.Rejected("El registro de exclusiones no está disponible.")
        if (trackId.isBlank() || expectedUri.isBlank()) {
            return LocalVisibilityResult.Rejected("Identidad inválida.")
        }
        val root = rootPreferences?.currentRoot() ?: MusicRoot.Default
        invalidateCache()
        val tracks = fetchTracksResult()
        val visible = (tracks as? MediaResult.Success)?.data
            ?: return LocalVisibilityResult.Rejected(
                "La biblioteca no está disponible. Comprueba los permisos.",
            )
        val track = visible.singleOrNull { it.id == trackId && it.filepath == expectedUri }
            ?: return LocalVisibilityResult.Rejected("La canción cambió de origen o ya está oculta.")
        if (expectedFingerprint != null &&
            expectedFingerprint != libraryVisibilityFingerprint(track)
        ) return LocalVisibilityResult.Rejected("El archivo cambió desde que abriste el menú.")
        if ((rootPreferences?.currentRoot() ?: MusicRoot.Default) != root) {
            return LocalVisibilityResult.Rejected("La biblioteca cambió durante la operación.")
        }
        val row = LibraryExclusionEntity(
            sourceId = libraryVisibilitySource(root),
            trackId = track.id,
            guardFingerprint = libraryVisibilityFingerprint(track),
            title = track.title,
            artist = track.artist,
            album = track.album,
        )
        return try {
            if (dao.hide(row) == -1L) {
                LocalVisibilityResult.Rejected("La canción ya estaba oculta.")
            } else {
                invalidateCache()
                LocalVisibilityResult.Hidden
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            LocalVisibilityResult.Rejected("No se pudo guardar la exclusión.")
        }
    }

    fun currentVisibilitySource(): String =
        libraryVisibilitySource(rootPreferences?.currentRoot() ?: MusicRoot.Default)

    /** Copy metadata before migrating a SAF source id; originals never change. */
    suspend fun copyVisibilityToMigratedSource(oldSource: String, newSource: String) {
        if (oldSource.isNotBlank() && oldSource != newSource) {
            libraryExclusionDao?.copyLegacySource(oldSource, newSource)
        }
    }

    /**
     * Atomically hides the exact tracks of one selected album/artist snapshot.
     * Every identity and fingerprint must still match the active source; otherwise
     * no exclusion is written. Neither audio files nor playlist entries are changed.
     */
    suspend fun hideSelectionFromCurrentLibrary(
        selection: List<LocalVisibilitySelection>,
        expectedSource: String,
    ): LocalVisibilityBatchResult {
        val dao = libraryExclusionDao
            ?: return LocalVisibilityBatchResult.Rejected(
                "El registro de exclusiones no está disponible.",
            )
        if (selection.isEmpty() || selection.size > 5_000 ||
            selection.any { it.trackId.isBlank() || it.uri.isBlank() || it.fingerprint.isBlank() } ||
            selection.map { it.trackId }.distinct().size != selection.size
        ) return LocalVisibilityBatchResult.Rejected(
            "La selección contiene identidades inválidas o duplicadas.",
        )
        val currentSource = currentVisibilitySource()
        if (currentSource != expectedSource) {
            return LocalVisibilityBatchResult.Rejected(
                "La biblioteca cambió. No se ocultó ninguna canción.",
            )
        }
        invalidateCache()
        val result = fetchTracksResult()
        val visible = (result as? MediaResult.Success)?.data
            ?: return LocalVisibilityBatchResult.Rejected(
                "No se pudo leer la biblioteca; no se ocultó ninguna canción.",
            )
        val candidates = visible.associateBy { it.id }
        val verified = selection.map { expected ->
            candidates[expected.trackId]?.takeIf {
                it.filepath == expected.uri &&
                    libraryVisibilityFingerprint(it) == expected.fingerprint
            }
        }
        if (verified.any { it == null }) {
            return LocalVisibilityBatchResult.Rejected(
                "El contenido de la selección cambió. Actualiza la vista e inténtalo de nuevo.",
            )
        }
        if (currentVisibilitySource() != expectedSource) {
            return LocalVisibilityBatchResult.Rejected(
                "La biblioteca cambió durante la comprobación.",
            )
        }
        val rows = verified.filterNotNull().map { track ->
            LibraryExclusionEntity(
                sourceId = currentSource,
                trackId = track.id,
                guardFingerprint = libraryVisibilityFingerprint(track),
                title = track.title,
                artist = track.artist,
                album = track.album,
            )
        }
        return try {
            val inserted = dao.hideBatch(rows)
            invalidateCache()
            LocalVisibilityBatchResult.Hidden(inserted.size)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            LocalVisibilityBatchResult.Rejected(
                "No fue posible guardar toda la selección. No se modificaron archivos.",
            )
        }
    }

    suspend fun hiddenInCurrentLibrary(): List<LibraryExclusionEntity> =
        libraryExclusionDao?.forSource(
            libraryVisibilitySource(rootPreferences?.currentRoot() ?: MusicRoot.Default),
        ).orEmpty()

    suspend fun hiddenIdsInCurrentLibrary(): Set<String> =
        hiddenInCurrentLibrary().mapTo(HashSet()) { it.trackId }

    /**
     * Search also reads the synced cache. A cached row from the active local source
     * must obey the same source/fingerprint exclusion as the live MediaStore/SAF list.
     */
    suspend fun filterHiddenSynced(cached: List<CachedTrack>): List<CachedTrack> {
        val source = currentVisibilitySource()
        val hidden = hiddenInCurrentLibrary().associateBy { it.trackId }
        if (hidden.isEmpty()) return cached
        return cached.filterNot { row ->
            row.sourceId == source &&
                isLibraryTrackHidden(hidden[row.id], source, row.toTrack())
        }
    }

    suspend fun restoreInCurrentLibrary(
        trackId: String,
        expectedSource: String,
    ): LocalVisibilityResult {
        val dao = libraryExclusionDao
            ?: return LocalVisibilityResult.Rejected(
                "El registro de exclusiones no está disponible.",
            )
        if (trackId.isBlank()) return LocalVisibilityResult.Rejected("Identidad inválida.")
        val source = libraryVisibilitySource(rootPreferences?.currentRoot() ?: MusicRoot.Default)
        if (source != expectedSource) {
            return LocalVisibilityResult.Rejected(
                "La biblioteca cambió. No se restauró otra fuente.",
            )
        }
        return try {
            if (dao.restore(source, trackId) == 0) {
                LocalVisibilityResult.Rejected("La exclusión ya no existe.")
            } else {
                invalidateCache()
                LocalVisibilityResult.Restored
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            LocalVisibilityResult.Rejected("No se pudo restaurar la canción.")
        }
    }

    // Returns MediaResult typed status. Callers must not cache non-Success.
    internal open suspend fun queryTracksResult(): MediaResult<List<Track>> {
        val ctx = context ?: return MediaResult.Success(emptyList())
        val root = rootPreferences?.currentRoot() ?: MusicRoot.Default
        if (!root.isDefault) return customRootTracks()
        // Some MediaStore providers return zero rows instead of throwing when the audio
        // permission is missing. Checking the grant first makes "denied" a fact instead of
        // guessing from an empty cursor.
        if (!hasAudioPermission(ctx)) return MediaResult.PermissionDenied
        val primaryAudio = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val baseColumns = arrayOf(
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
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_ALARM,
            MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_PODCAST,
            MediaStore.Audio.Media.IS_AUDIOBOOK,
            MediaStore.Audio.Media.IS_RECORDING,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
        )
        val requestsGenre = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val preferredColumns = if (requestsGenre) {
            baseColumns + MediaStore.Audio.Media.GENRE
        } else {
            baseColumns
        }
        fun dispatch(columns: Array<String>) = MediaQueryDispatcher(
            ctx.contentResolver,
            primaryAudio,
        )
            .withColumns(*columns)
            .setSelection(mediaStoreMusicRootSelection(MediaStore.Audio.Media.RELATIVE_PATH))
            .addArgs(*MUSIC_ROOT_QUERY_ARGS)
            .setSortOrder(MediaStore.Audio.Media.TITLE + " ASC")
            .dispatchWithResult()

        var queryResult = dispatch(preferredColumns)
        var genreProjected = requestsGenre
        if (shouldRetryGenreProjection(requestsGenre, queryResult)) {
            // Some providers reject GENRE even on API 30+. Retry without it
            // instead of presenting an empty library as a permission failure.
            queryResult = dispatch(baseColumns)
            genreProjected = false
        }

        val cursor = when (queryResult) {
            is MediaResult.Success -> queryResult.data ?: return MediaResult.Success(emptyList())
            is MediaResult.PermissionDenied -> return MediaResult.PermissionDenied
            is MediaResult.QueryFailed -> return queryResult
        }

        val pendingTracks = mutableListOf<PendingTrack>()
        var needsGenreMembershipRecovery = !genreProjected
        var genreReadBudget = MAX_EMBEDDED_GENRE_READS_PER_QUERY
        val scanFromRow = nextGenreScanRow
        var nextRow = scanFromRow
        val savedEvidence = try {
            importedTrackDao?.getTagEvidenceForSource(TagEvidencePolicy.MEDIASTORE_OWNER)
                ?.associateBy { it.trackId }.orEmpty()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) { emptyMap<String, EmbeddedTagEvidence>() }
        val evidenceUpdates = ArrayList<EmbeddedTagEvidence>()
        // Album-level credit lives in the albums table, not in each song row.
        // It is the stable grouping key for compilations with guest artists.
        val albumArtistById = mutableMapOf<String, String>()
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Albums._ID, MediaStore.Audio.Albums.ARTIST),
                null,
                null,
                null,
            )?.use { albums ->
                val idCol = albums.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID)
                val artistCol = albums.getColumnIndexOrThrow(MediaStore.Audio.Albums.ARTIST)
                while (albums.moveToNext()) {
                    val id = albums.getString(idCol) ?: continue
                    val artist = albums.getString(artistCol).orEmpty()
                    if (artist.isNotBlank()) albumArtistById[id] = artist
                }
            }
        }

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
            val displayNameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val ringtoneCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE)
            val alarmCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM)
            val notificationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION)
            val podcastCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PODCAST)
            val audiobookCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_AUDIOBOOK)
            val recordingCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RECORDING)
            val relativePathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
            val genreCol = if (genreProjected) {
                c.getColumnIndex(MediaStore.Audio.Media.GENRE)
            } else {
                -1
            }
            if (genreCol < 0) needsGenreMembershipRecovery = true

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
                if (!isInsideMusicRoot(relativePath)) continue
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
                    primaryAudio,
                    id.toLong()
                ).toString()
                val albumId = c.getString(albumIdCol) ?: ""
                val providerGenre = if (genreCol >= 0) c.getString(genreCol).orEmpty() else ""
                // A fast foreground pass reads at most 32 missing-genre files.
                // The cursor advances on each refresh, avoiding starvation after 1,024 entries.
                val fileSize = c.getLong(sizeCol).coerceAtLeast(0L)
                val modified = c.getLong(modifiedCol).coerceAtLeast(0L)
                val needsProbe = TagEvidencePolicy.needsProbe(providerGenre, title, artist, album)
                // Reuse an already-verified embedded tag even when MediaStore now
                // reports all four basic fields. This adds no file I/O and stops a
                // provider-only value from masking prior TCON evidence.
                val cachedEvidence = savedEvidence[trackId]?.takeIf {
                    it.matchesMediaStore(fileSize, modified)
                }
                val embedded = if (cachedEvidence != null) cachedEvidence
                else if (!needsProbe) null
                else if (genreReadBudget > 0 && (c.position >= scanFromRow || c.count <= scanFromRow)) {
                    genreReadBudget--
                    nextRow = c.position + 1
                    val fields = UriEmbeddedTags.read(ctx, android.net.Uri.parse(contentUri))
                    val evidence = TagEvidencePolicy.record(
                        TagEvidencePolicy.MEDIASTORE_OWNER, trackId, fileSize, modified, fields)
                    if (modified > 0) evidenceUpdates.add(evidence)
                    evidence
                } else null
                val genre = GenreMetadataMerge.choose(embedded?.genre, providerGenre, null)
                pendingTracks.add(
                    PendingTrack(
                        track = Track(
                            id = trackId,
                            title = MetadataFieldMerge.choose(embedded?.title, title, "Unknown"),
                            artist = MetadataFieldMerge.choose(embedded?.artist, artist, "Unknown"),
                            album = MetadataFieldMerge.choose(embedded?.album, album, "Unknown"),
                            albumId = albumId,
                            albumArtist = MetadataFieldMerge.choose(embedded?.albumArtist,
                                albumArtistById[albumId]),
                            duration = duration.coerceAtLeast(0),
                            filepath = contentUri,
                            trackNumber = embedded?.trackNumber?.takeIf { it > 0 }
                                ?: c.getInt(trackCol).coerceAtLeast(0),
                            year = embedded?.date?.let(TagEvidencePolicy::exactYear)
                                ?: c.getInt(yearCol).coerceAtLeast(0),
                            size = c.getLong(sizeCol).coerceAtLeast(0),
                            format = (c.getString(mimeCol) ?: "").substringAfterLast("/"),
                            coverId = c.getString(albumIdCol) ?: "",
                            dateAdded = if (dateCol >= 0) c.getLong(dateCol) else 0L,
                            genre = genre,
                            // Original independent tags (not guessed from a semicolon).
                            // This transient field is never sent over Michi Link.
                            genreValues = if (embedded?.genre == genre &&
                                embedded.genreValues.isNotBlank()) {
                                TrustedGenreEncoding.decode(embedded.genre, embedded.genreValues)
                            } else emptyList(),
                            source = TrackSource.LOCAL,
                            sourceMimeType = c.getString(mimeCol).orEmpty(),
                            sourceFileExtension = sourceFileExtension(c.getString(displayNameCol)),
                            trackNumberFromMediaStore = embedded?.trackNumber?.takeIf { it > 0 } == null,
                        ),
                    ),
                )
            }
        }

        // Committed only after the MediaStore query succeeds: never cache a partial scan.
        if (evidenceUpdates.isNotEmpty()) {
            try { importedTrackDao?.putTagEvidence(evidenceUpdates) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { /* Catalog remains readable; retry when Room recovers. */ }
        }
        nextGenreScanRow = if (genreReadBudget == 0) nextRow else 0
        // OEM compatibility: query existing genre-membership tables only if the
        // preferred GENRE column was refused/omitted. This executes once during
        // the normal authorized IO load, never once per file or Compose frame.
        val recovered = if (needsGenreMembershipRecovery && pendingTracks.isNotEmpty()) {
            recoverMediaStoreGenreMemberships(
                resolver = ctx.contentResolver,
                volume = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                permittedAudioIds = pendingTracks.mapNotNull {
                    it.track.id.removePrefix("local_").toLongOrNull()
                }.toSet(),
            )
        } else emptyMap()
        val completed = if (recovered.isEmpty()) pendingTracks.map { it.track } else {
            pendingTracks.map { pending ->
                val track = pending.track
                val mediaId = track.id.removePrefix("local_").toLongOrNull()
                if (mediaId == null) track else track.copy(
                    genre = preserveMediaStoreGenre(track.genre, recovered[mediaId]),
                )
            }
        }
        return MediaResult.Success(applyReplayGain(completed))
    }

    private suspend fun customRootTracks(): MediaResult<List<Track>> {
        val ctx = context ?: return MediaResult.Success(emptyList())
        val root = rootPreferences?.currentRoot() ?: MusicRoot.Default
        val treeUri = root.treeUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            ?: return MediaResult.QueryFailed(IllegalStateException("La carpeta musical no es válida"))
        val hasGrant = ctx.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }
        if (!hasGrant) {
            return MediaResult.QueryFailed(
                IllegalStateException(
                    "El permiso de la carpeta se revocó. Vuelve a elegirla en Ajustes → Biblioteca.",
                ),
            )
        }
        val sourceId = root.sourceId ?: customRootSourceId(treeUri)
        val dao = importedTrackDao ?: return MediaResult.Success(emptyList())
        val rows = try {
            dao.getTracksForSource(sourceId).first()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return MediaResult.QueryFailed(IllegalStateException("No se pudo leer la carpeta elegida"))
        }
        val tracks = rows.map { it.toTrack() }
            .filter { track ->
                !isLikelyNonMusicGenericAudio(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    relativePath = null,
                    bucketDisplayName = null,
                    durationMs = track.duration,
                )
            }
        return MediaResult.Success(applyReplayGain(tracks))
    }

    private suspend fun applyReplayGain(source: List<Track>): List<Track> {
        val rgBatch = replayGainDao?.getAllReplayGains() ?: emptyList()
        val rgMap = rgBatch.associateBy { it.trackId }
        val rgUpdates = mutableListOf<ReplayGainEntity>()
        val processed = source.map { track ->
            val cached = rgMap[track.id]
            if (cached != null) {
                track.copy(replayGainTrack = cached.trackGain, replayGainAlbum = cached.albumGain)
            } else {
                val parsed = ReplayGainReader.read(context, track.filepath)
                // NaN is the project-wide sentinel for "no ReplayGain data"; never cache it
                // because SQLite binds a NaN REAL as SQL NULL and the columns are NOT NULL.
                if (isReplayGainPersistable(parsed)) {
                    rgUpdates.add(ReplayGainEntity(track.id, parsed.trackGain, parsed.albumGain))
                }
                track.copy(replayGainTrack = parsed.trackGain, replayGainAlbum = parsed.albumGain)
            }
        }
        if (rgUpdates.isNotEmpty()) replayGainDao?.upsertAll(rgUpdates)
        return processed
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

internal fun sourceFileExtension(displayName: String?): String {
    val name = displayName?.trim().orEmpty()
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    return extension.lowercase(java.util.Locale.ROOT)
        .takeIf { it.isNotBlank() && it.length <= 12 && it.all(Char::isLetterOrDigit) }
        .orEmpty()
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
