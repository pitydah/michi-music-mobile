package org.michimusic.mobile.playback

import org.michimusic.core.models.AudioSignalObservation
import org.michimusic.core.models.DspPipelineStatus

/** Prevent the UI from attaching PCM and local-DSP observations to a different
 * visible track while independent StateFlows are settling after a handoff.
 * Track ID alone is NOT a global source/library/queue identity: this is a
 * conservative bridge until an owner+epoch receipt is available end-to-end.
 */
internal object SignalTruthOwnerPolicy {
    fun isVisibleTrackCurrent(observation: AudioSignalObservation, visibleTrackId: String?): Boolean =
        visibleTrackId != null && observation.trackIdentity != null &&
            visibleTrackId == observation.trackIdentity

    fun forVisibleTrack(
        observation: AudioSignalObservation,
        visibleTrackId: String?,
        usesLocalDecoder: Boolean,
    ): AudioSignalObservation {
        if (!usesLocalDecoder || isVisibleTrackCurrent(observation, visibleTrackId)) return observation
        // Preserve route status as status, not as a sample or sink receipt.
        // In particular, a recent route event must never refresh stale PCM.
        return observation.copy(
            sourceFormat = null,
            decodedFormat = null,
            outputConfig = null,
            dspStatus = DspPipelineStatus(),
            sourceObservedAtMs = 0L,
            decodedObservedAtMs = 0L,
            outputObservedAtMs = 0L,
        )
    }
}
