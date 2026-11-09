package org.michimusic.player

/**
 * Software-PCM evidence for one immutable six-band PEQ request, never evidence of
 * USB hardware, Android mixer output or RTP reception.
 */
internal data class HeadphonePeqRenderReceipt(
    val revision: Long,
    val processedFrames: Long,
    val clippedSamples: Long,
    val headroomDb: Float,
    val observedAtMs: Long,
)

/** Side-effect-free gate; safe to unit-test without Android or Media3. */
internal object HeadphonePeqReceiptPolicy {
    fun current(
        requestedRevision: Long,
        renderedRevision: Long,
        framesForRevision: Long,
        clippedForRevision: Long,
        headroomDb: Float,
        observedAtMs: Long,
        requestEnabled: Boolean,
        wasApplied: Boolean,
        routeAllowedNow: Boolean,
    ): HeadphonePeqRenderReceipt? {
        if (!requestEnabled || !wasApplied || !routeAllowedNow) return null
        if (requestedRevision < 0L || renderedRevision != requestedRevision) return null
        if (framesForRevision <= 0L || clippedForRevision < 0L) return null
        if (!headroomDb.isFinite() || headroomDb < 0f || observedAtMs <= 0L) return null
        return HeadphonePeqRenderReceipt(requestedRevision, framesForRevision,
            clippedForRevision, headroomDb, observedAtMs)
    }
}
