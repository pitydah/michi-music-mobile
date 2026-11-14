package org.michimusic.core.intelligence

/**
 * MI-03: tiny state machine driven by the EXISTING Media3 listener. No threads,
 * storage, player, timers, network or position polling. Elapsed audible wall-time
 * is accumulated only while playback is confirmed and the local owner stays stable.
 * Seek-to-end cannot fabricate a completed listen.
 */
class MichiPlaybackEvidenceAccumulator {
    data class LocalTrack(
        val sourceId: String,
        val trackId: String,
        val artist: String,
        val genre: String,
        val durationMs: Long,
        val verifiedGenreValues: List<String> = emptyList(),
    )

    enum class Transition { AUTO, REPEAT, SEEK, END, REPLACED, ERROR }
    data class FinalEvent(
        val track: LocalTrack,
        val sequence: Long,
        val outcome: MichiIntelligencePolicy.PlaybackOutcome,
        val completionPermille: Int,
        /** Consent generation observed when this track BEGAN, not when it ended. */
        val consentEpoch: Long,
        /** Local authority revision frozen when this song started. */
        val ownerRevision: Long,
        /** Session handoff intent generation, captured when the song BEGAN. */
        val handoffGeneration: Long = 0L,
    )

    private data class Active(
        val track: LocalTrack,
        val ownerRevision: Long,
        val handoffGeneration: Long,
        val sequence: Long,
        val consentEpoch: Long,
        var accumulatedMs: Long = 0L,
        var sinceMs: Long? = null,
        var skipMarkedAtMs: Long? = null,
        var valid: Boolean = true,
    )

    private var sequence = 0L
    private var active: Active? = null

    /** Local eligibility and correct source owner are verified by the caller. */
    fun begin(
        track: LocalTrack?,
        nowMs: Long,
        ownerRevision: Long,
        isPlaying: Boolean,
        consentEpoch: Long = 0L,
        handoffGeneration: Long = 0L,
    ) {
        active = track?.takeIf { it.trackId.isNotBlank() && it.durationMs > 0L &&
            it.durationMs <= 86_400_000L && nowMs >= 0L && consentEpoch >= 0L }?.let {
            Active(it, ownerRevision, handoffGeneration, ++sequence, consentEpoch,
                sinceMs = if (isPlaying) nowMs else null)
        }
    }

    fun playing(isPlaying: Boolean, nowMs: Long, ownerRevision: Long,
        handoffGeneration: Long = 0L) {
        val item = active ?: return
        if (nowMs < 0 || item.ownerRevision != ownerRevision ||
            item.handoffGeneration != handoffGeneration) {
            item.valid = false
            item.sinceMs = null
            return
        }
        accrue(item, nowMs)
        item.sinceMs = if (isPlaying) nowMs else null
    }

    /** Called only for an accepted next/previous/queue-index UI command. */
    fun explicitSkip(nowMs: Long, ownerRevision: Long,
        handoffGeneration: Long = 0L) {
        val item = active ?: return
        if (item.valid && item.ownerRevision == ownerRevision &&
            item.handoffGeneration == handoffGeneration && nowMs >= 0) {
            item.skipMarkedAtMs = nowMs
        }
    }

    /** Exactly one transition may finish an active item. A repeated event returns null. */
    fun finish(kind: Transition, nowMs: Long, ownerRevision: Long,
        handoffGeneration: Long = 0L): FinalEvent? {
        val item = active ?: return null
        active = null
        if (!item.valid || item.ownerRevision != ownerRevision ||
            item.handoffGeneration != handoffGeneration || nowMs < 0L) return null
        accrue(item, nowMs)
        if (!item.valid) return null
        val played = item.accumulatedMs.coerceIn(0L, item.track.durationMs)
        val permille = (played * 1000L / item.track.durationMs).toInt().coerceIn(0, 1000)
        val outcome = when {
            kind == Transition.AUTO || kind == Transition.REPEAT || kind == Transition.END ->
                if (permille >= 700) MichiIntelligencePolicy.PlaybackOutcome.COMPLETED else return null
            kind == Transition.SEEK && item.skipMarkedAtMs != null &&
                nowMs - item.skipMarkedAtMs!! in 0L..1500L &&
                played >= 1000L && permille < 300 -> MichiIntelligencePolicy.PlaybackOutcome.EXPLICIT_SKIP
            else -> return null
        }
        return FinalEvent(item.track, item.sequence, outcome, permille, item.consentEpoch,
            item.ownerRevision, item.handoffGeneration)
    }

    fun discard() { active = null }

    private fun accrue(item: Active, nowMs: Long) {
        val start = item.sinceMs ?: return
        if (nowMs < start) { item.valid = false; return }
        val delta = (nowMs - start).coerceAtMost(item.track.durationMs)
        item.accumulatedMs = (item.accumulatedMs + delta).coerceAtMost(item.track.durationMs)
    }
}
