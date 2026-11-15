package org.michimusic.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackAvailabilityChecker
import org.michimusic.data.cache.AppDao
import org.michimusic.data.intelligence.MichiIntelligenceRepository
import org.michimusic.mobile.intelligence.MichiGenreIntelligenceAdapter

/**
 * MI-11 / K47-MI-01 / K47-MI-02: passive, screen-owned UI7 genre detail rail.
 * No new library view, media observer, art decoder, worker, subscription or
 * independent navigation. The authorized playable list and scope receipt come
 * from GenreLibraryViewModel.
 *
 * The suggestion snapshot is keyed only by genre/library/scope/consent. The
 * currently playing track conditions the *initial* exclusion inside
 * [MichiGenreIntelligenceAdapter.suggest] but is not a snapshot key, so starting
 * a recommendation never rebuilds or removes the rail.
 */
@Composable
internal fun MichiGenreContextRecommendations(
    genreId: String,
    genreTitle: String,
    playableTracks: List<Track>,
    scopeKey: String,
    scopeGeneration: Long,
    currentTrack: Track?,
    canPlayLocally: () -> Boolean,
    captureIntelligenceTicket: () -> org.michimusic.mobile.playback.PlaybackSessionManager.IntelligenceQueueTicket?,
    playIntelligenceQueue: (List<Track>, Int,
        org.michimusic.mobile.playback.PlaybackSessionManager.IntelligenceQueueTicket) -> Boolean,
    modifier: Modifier = Modifier,
) {
    // Legacy callers lacking a verified UI7 receipt do not touch preferences.
    // Do not even read the private taste profile while a remote owner is active
    // or this UI7 receipt has been superseded. The click also rechecks after IO.
    if (scopeKey.isBlank() || scopeGeneration <= 0 || playableTracks.isEmpty() ||
        !canPlayLocally()) return
    val context = LocalContext.current.applicationContext
    val dao: AppDao = koinInject()
    val repository = remember(context, dao) { MichiIntelligenceRepository(context, dao) }
    val adapter = remember(repository) { MichiGenreIntelligenceAdapter(repository) }
    val revision by MichiIntelligenceConsentBarrier.changes.collectAsStateWithLifecycle()
    // The operation is cancelable and runs in IO inside the adapter. It is
    // requested ONLY on an open genre detail, never during every recomposition.
    val candidate by produceState<MichiGenreIntelligenceAdapter.Section?>(
        null, adapter, genreId, scopeKey, scopeGeneration, playableTracks, revision,
    ) {
        value = try {
            adapter.suggest(genreId, scopeKey, scopeGeneration, playableTracks, currentTrack)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null // Local learning must never break the library detail view.
        }
    }
    val section = candidate?.takeIf {
        it.isAuthorized() && it.playbackPlan(genreId, scopeKey,
            scopeGeneration, playableTracks) != null
    } ?: return

    val tracksByKey = remember(section) {
        section.tracks.associateBy { MichiIntelligencePolicy.TrackKey(it.sourceId, it.id) }
    }
    val picks = remember(section, tracksByKey) {
        section.tracks
            .take(MichiGenreContextPolicyPicks.MAX_RAIL_PICKS)
            .mapNotNull { track ->
                val key = MichiIntelligencePolicy.TrackKey(track.sourceId, track.id)
                if (tracksByKey[key] == null) null
                else GenreIntelligencePick(key = key, title = track.title, reason = section.reason(track))
            }
    }
    var expanded by rememberSaveable(genreId) { mutableStateOf(false) }
    var reasonDetail by remember(genreId, scopeKey, scopeGeneration) {
        mutableStateOf<String?>(null)
    }
    val scope = rememberCoroutineScope()
    var warning by remember(genreId, scopeKey, scopeGeneration) { mutableStateOf<String?>(null) }
    val play: (Track?) -> Unit = { selected ->
        // Click-time ticket must predate availability IO; an away-and-back
        // handoff during IO invalidates the request even if the phone is local.
        val ticket = captureIntelligenceTicket()
        scope.launch {
            warning = null
            val plan = section.playbackPlan(
                genreId, scopeKey, scopeGeneration, playableTracks, selected,
            )
            if (plan == null || ticket == null || !canPlayLocally()) {
                warning = "La selección ya no está disponible en esta biblioteca."
            } else {
                val filesReady = withContext(Dispatchers.IO) {
                    plan.first.all { TrackAvailabilityChecker.isTrackDownloaded(it, context) }
                }
                // Authorization/identity must be rechecked after the suspend point.
                val stillValid = section.playbackPlan(
                    genreId, scopeKey, scopeGeneration, playableTracks, selected,
                )
                if (!filesReady || stillValid == null || !canPlayLocally() ||
                    !playIntelligenceQueue(stillValid.first, stillValid.second, ticket)) {
                    warning = "Las canciones cambiaron o dejaron de estar disponibles."
                }
            }
        }
    }

    val playingKey = currentTrack?.let { track ->
        val key = MichiIntelligencePolicy.TrackKey(track.sourceId, track.id)
        picks.firstOrNull { it.key == key }?.key
    }
    if (picks.isNotEmpty()) {
        GenreIntelligenceRail(
            state = GenreIntelligenceUiState(
                genreTitle = genreTitle,
                expanded = expanded,
                picks = picks,
                playingKey = playingKey,
                warning = warning,
                reasonDetail = reasonDetail,
            ),
            onToggleExpanded = { expanded = !expanded },
            onPlayPick = { key -> play(tracksByKey[key]) },
            onPlaySelection = { play(null) },
            onShowReason = { key ->
                val pick = picks.firstOrNull { it.key == key }
                reasonDetail = pick?.let {
                    "${it.title}: ${it.reason}. Señales de escucha de tu biblioteca; " +
                        "esta tarjeta nunca consulta la red."
                }
            },
            modifier = modifier,
        )
    }
}

/** K47-MI-01: the contract caps the rail at four suggestions. */
private object MichiGenreContextPolicyPicks {
    const val MAX_RAIL_PICKS = 4
}
