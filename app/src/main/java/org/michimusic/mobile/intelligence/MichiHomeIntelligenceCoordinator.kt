package org.michimusic.mobile.intelligence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.michimusic.core.models.Track
import java.util.concurrent.atomic.AtomicLong

/**
 * MI-10: screen-owned state coordinator, passive until Home explicitly refreshes.
 * No ViewModel-wide background collection, timer, worker, additional music index
 * observer, SDK or persistent job. A stale request cannot overwrite a newer one.
 */
class MichiHomeIntelligenceCoordinator(
    private val adapter: MichiHomeIntelligenceAdapter,
) {
    private val version = AtomicLong()
    private val mutable = MutableStateFlow(
        MichiHomeIntelligenceAdapter.SuggestionResult(emptyList(), 0),
    )
    val state: StateFlow<MichiHomeIntelligenceAdapter.SuggestionResult> = mutable.asStateFlow()

    suspend fun refresh(library: List<Track>, recent: List<Track>, current: Track?) {
        val requested = version.incrementAndGet()
        val epoch = MichiIntelligenceConsentBarrier.epoch()
        try {
            val next = adapter.suggest(library, recent, current)
            if (version.get() == requested && MichiIntelligenceConsentBarrier.permits(epoch) &&
                (next.sections.isEmpty() || next.sections.all { it.belongsTo(library) })) {
                mutable.value = next
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (version.get() == requested) invalidate()
        }
    }

    /** An authorization/permission failure clears immediately, never waits for IO. */
    fun invalidate() {
        version.incrementAndGet()
        // Releasing the public state is not enough: the adapter may retain a
        // bounded taste-derived digest cache until Home is opened again.
        adapter.clearTransientSelection()
        mutable.value = MichiHomeIntelligenceAdapter.SuggestionResult(emptyList(), 0)
    }

    /** One dismissed track is removed from ALL currently shown cards, not an entire Mix. */
    fun suppressTrack(selected: Track) {
        version.incrementAndGet()
        val key = MichiIntelligencePolicy.TrackKey(selected.sourceId, selected.id)
        val now = mutable.value
        mutable.value = now.copy(sections = now.sections.mapNotNull { section ->
            val remaining = section.tracks.filterNot {
                MichiIntelligencePolicy.TrackKey(it.sourceId, it.id) == key
            }
            if (remaining.isEmpty()) null else section.copy(tracks = remaining)
        })
    }
}
