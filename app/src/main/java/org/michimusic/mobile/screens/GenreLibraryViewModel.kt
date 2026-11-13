package org.michimusic.mobile.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedGenreMetadata
import org.michimusic.data.cache.TrackDao
import org.michimusic.mobile.ui.screens.GenreIndexLoad
import org.michimusic.mobile.ui.screens.GenreOwnerEvidence
import org.michimusic.mobile.ui.screens.GenreScopedIndex
import org.michimusic.mobile.ui.screens.buildGenreScopedIndex
import org.michimusic.mobile.ui.screens.genreScopeKey

/** The only owner of genre-index IO and owner-collision evidence. */
internal class GenreLibraryRepository(private val trackDao: TrackDao) {
    fun observeEvidence(): Flow<List<CachedGenreMetadata>> =
        trackDao.observeGenreEvidenceSnapshot()

    suspend fun buildIndex(
        tracks: List<Track>,
        evidence: List<CachedGenreMetadata>,
        scopeOwnerId: String?,
        isAllScope: Boolean,
    ): GenreScopedIndex = withContext(Dispatchers.Default) {
        buildGenreScopedIndex(
            tracks = tracks,
            cacheEvidence = evidence.map { row ->
                GenreOwnerEvidence(
                    sourceId = row.sourceId,
                    id = row.id,
                    title = row.title,
                    artist = row.artist,
                    album = row.album,
                    duration = row.duration,
                    filepath = row.filepath,
                    genre = row.genre,
                )
            },
            scopeOwnerId = scopeOwnerId,
            isAllScope = isAllScope,
        )
    }
}

/**
 * UI7 genre orchestration. The screen supplies the already-authorized scoped
 * track snapshot; this owner joins optional genre evidence off the main thread
 * and cancels the previous scope before starting a new one.
 */
class GenreLibraryViewModel(trackDao: TrackDao) : ViewModel() {
    private val repository = GenreLibraryRepository(trackDao)
    private val _state = MutableStateFlow<GenreIndexLoad>(GenreIndexLoad.Loading)
    val state: StateFlow<GenreIndexLoad> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L

    /** Stops the Room observer when the Genres destination leaves composition. */
    fun clear() {
        generation++
        loadJob?.cancel()
        loadJob = null
        _state.value = GenreIndexLoad.Loading
    }

    fun load(
        tracks: List<Track>,
        scopeOwnerId: String?,
        isAllScope: Boolean,
        libraryId: String? = null,
    ) {
        val requestGeneration = ++generation
        val requestScope = genreScopeKey(libraryId, scopeOwnerId, isAllScope)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = GenreIndexLoad.Loading
            try {
                repository.observeEvidence()
                    .distinctUntilChanged()
                    .collectLatest { rows ->
                        _state.value = GenreIndexLoad.Loading
                        val index = repository.buildIndex(tracks, rows, scopeOwnerId, isAllScope)
                        if (requestGeneration != generation) return@collectLatest
                        _state.value = GenreIndexLoad.Ready(
                            index = index,
                            scopeKey = requestScope,
                            generation = requestGeneration,
                        )
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) _state.value = GenreIndexLoad.Failed
            }
        }
    }
}
