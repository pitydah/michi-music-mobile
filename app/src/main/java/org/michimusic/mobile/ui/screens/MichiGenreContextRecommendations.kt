package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackAvailabilityChecker
import org.michimusic.data.cache.AppDao
import org.michimusic.data.intelligence.MichiIntelligenceRepository
import org.michimusic.mobile.intelligence.MichiGenreIntelligenceAdapter
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * MI-11: passive, screen-owned UI7 genre detail section. No new library view,
 * media observer, art decoder, worker, subscription or independent navigation.
 * The authorized playable list and scope receipt come from GenreLibraryViewModel.
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
        null, adapter, genreId, scopeKey, scopeGeneration, playableTracks,
        currentTrack?.sourceId, currentTrack?.id, revision,
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
    AeroSurface(
        role = AeroSurfaceRole.Raised,
        modifier = Modifier.fillMaxWidth().testTag("genre_intelligence_section"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("INTELLIGENCE · SOLO EN ESTE DISPOSITIVO",
                style = MichiTypography.microLabel, color = PrimaryPinkContainer)
            Text("Para ti en $genreTitle", style = MichiTypography.sectionTitle,
                color = PureWhite, modifier = Modifier.testTag("genre_intelligence_title"))
            Text("Selección de tu biblioteca basada en escuchas verificadas.",
                style = MichiTypography.metadata, color = TextSecondary)
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp).testTag("genre_intelligence_play_mix"),
                onClick = { play(null) },
            ) { Text("Escuchar selección (${section.tracks.size})") }
            section.tracks.forEachIndexed { index, track ->
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("genre_intelligence_track_$index"),
                        onClick = { play(track) },
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(track.title, color = PureWhite,
                                style = MichiTypography.metadata, maxLines = 2)
                            Text(section.reason(track), color = TextSecondary,
                                style = MichiTypography.microLabel)
                        }
                    }
                }
            }
            warning?.let {
                Text(it, color = PrimaryPinkContainer,
                    style = MichiTypography.metadata,
                    modifier = Modifier.testTag("genre_intelligence_warning"))
            }
        }
    }
}
