package org.michimusic.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.michimusic.core.intelligence.MichiPlaybackIntentFence
import org.michimusic.core.intelligence.MichiPlaybackEvidenceAccumulator
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.intelligence.MichiIntelligenceRepository
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * MI-03: consumes the EXISTING AudioController's Media3 listener.
 * No additional listener, service, flow, polling or decoder work.
 * The only IO is a suspended consent-gated SQLite capture after a final event.
 */
internal class MichiIntelligencePlaybackBridge(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val evidence = MichiPlaybackEvidenceAccumulator()
    private val processNonce = UUID.randomUUID().toString()

    companion object {
        /** Media3's authoritative current index is O(1), even for a 50k queue.
         * Never infer owner by searching for the first track with matching ID. */
        internal fun hasCanonicalQueuePosition(queue: List<Track>, index: Int, selected: Track): Boolean =
            index >= 0 && queue.getOrNull(index) === selected &&
                selected.source == TrackSource.LOCAL && selected.filepath.isNotBlank()
    }

    private fun authority(): PlayerDependencies.OutputAuthority = PlayerDependencies.outputAuthority.value
    private fun local(authority: PlayerDependencies.OutputAuthority): Boolean =
        authority.isLocal && authority.endpointId == "local_phone"

    fun onPlaying(isPlaying: Boolean) {
        val owner = authority()
        val intent = MichiPlaybackIntentFence.snapshot()
        if (!intent.localAllowed) { evidence.discard(); return }
        // An in-flight track that lost consent must not accrue further listening.
        // Do not create a new track mid-song if consent is granted later.
        if (!MichiIntelligenceConsentBarrier.permits(MichiIntelligenceConsentBarrier.epoch())) {
            evidence.discard()
            return
        }
        evidence.playing(isPlaying && local(owner), SystemClock.elapsedRealtime(),
            owner.revision, intent.generation)
    }

    fun markExplicitSkip() {
        val owner = authority()
        val intent = MichiPlaybackIntentFence.snapshot()
        if (local(owner) && intent.localAllowed)
            evidence.explicitSkip(SystemClock.elapsedRealtime(), owner.revision, intent.generation)
    }

    /** A same-track seek invalidates elapsed-time evidence; no seek-to-end rewards. */
    fun onSeekWithinTrack() = evidence.discard()

    /** The queue snapshot and matching media ID must prove owner and source. */
    fun onTransition(mediaId: String?, next: Track?, queue: List<Track>,
        queueIndex: Int, reason: Int, isPlaying: Boolean) {
        val owner = authority()
        val intent = MichiPlaybackIntentFence.snapshot()
        val kind = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> MichiPlaybackEvidenceAccumulator.Transition.AUTO
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> MichiPlaybackEvidenceAccumulator.Transition.REPEAT
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> MichiPlaybackEvidenceAccumulator.Transition.SEEK
            else -> MichiPlaybackEvidenceAccumulator.Transition.REPLACED
        }
        record(evidence.finish(kind, SystemClock.elapsedRealtime(), owner.revision,
            intent.generation))
        val verified = intent.localAllowed && local(owner) && next != null &&
            hasCanonicalQueuePosition(queue, queueIndex, next) &&
            next.source == TrackSource.LOCAL && next.filepath.isNotBlank() &&
            mediaId != null && LibraryProvider.extractSongId(mediaId) == next.id
        val safe = if (verified) {
            MichiPlaybackEvidenceAccumulator.LocalTrack(
                sourceId = next!!.sourceId,
                trackId = next.id,
                artist = next.artist,
                genre = next.genre,
                durationMs = next.duration,
                verifiedGenreValues = next.genreValues.take(16),
            )
        } else null
        evidence.begin(safe, SystemClock.elapsedRealtime(), owner.revision,
            isPlaying && local(owner), consentEpoch = MichiIntelligenceConsentBarrier.epoch(),
            handoffGeneration = intent.generation)
    }

    fun onEnded() {
        val owner = authority()
        val intent = MichiPlaybackIntentFence.snapshot()
        record(evidence.finish(MichiPlaybackEvidenceAccumulator.Transition.END,
            SystemClock.elapsedRealtime(), owner.revision, intent.generation))
    }

    fun onError() = evidence.discard()
    fun release() = evidence.discard()

    private fun record(event: MichiPlaybackEvidenceAccumulator.FinalEvent?) {
        // A handoff whose owner revision is stale must never be treated as local.
        val currentOwner = authority()
        if (event == null || !local(currentOwner) ||
            !MichiPlaybackIntentFence.permits(event.handoffGeneration) ||
            event.ownerRevision != currentOwner.revision ||
            !MichiIntelligenceConsentBarrier.permits(event.consentEpoch)) return
        val dao = PlayerDependencies.appDao ?: return
        val nowMs = System.currentTimeMillis()
        val snapshot = MichiIntelligenceRepository.PlaybackEvidence(
            eventId = "$processNonce:${event.sequence}",
            sourceId = event.track.sourceId,
            trackId = event.track.trackId,
            artist = event.track.artist,
            genre = event.track.genre,
            verifiedGenreValues = event.track.verifiedGenreValues,
            observedEpochDay = LocalDate.now(ZoneOffset.UTC).toEpochDay(),
            observedAtMs = nowMs,
            outcome = event.outcome,
            completionPermille = event.completionPermille,
            ownedLocalSession = true, // only after verifying local endpoint + unchanged epoch
            consentEpoch = event.consentEpoch,
            // A pending SQLite capture cannot borrow a different Michi Control
            // owner after this event was finalized. Recheck at DB commit, not
            // merely before launching this coroutine.
            ownerStillValid = {
                val latest = authority()
                local(latest) && latest.revision == event.ownerRevision &&
                    MichiPlaybackIntentFence.permits(event.handoffGeneration)
            },
        )
        scope.launch {
            // Session ownership may have changed between callback and IO dispatch.
            if (!snapshot.ownerStillValid()) return@launch
            // The repository itself checks explicit consent and historical opt-out.
            // A failed optional recommendation event must never interrupt playback.
            try {
                MichiIntelligenceRepository(context, dao).capture(snapshot)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Optional profile capture never affects audio. */ }
        }
    }
}
