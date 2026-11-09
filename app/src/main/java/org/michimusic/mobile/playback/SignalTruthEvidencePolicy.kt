package org.michimusic.mobile.playback

import org.michimusic.core.models.SignalFormat

/** Pure, shared UI evidence gate. Never owns audio execution or transport. */
internal object SignalTruthEvidencePolicy {
    /** A generic AUDIO_OUTPUT capability is not a Michi Stream identity. */
    fun isMichiStream(
        isRemote: Boolean,
        endpoint: PlaybackEndpoint,
        streamSelectedBySession: Boolean = false,
    ): Boolean = isRemote && !endpoint.isLocal &&
        (endpoint.type == EndpointType.STREAM_RECEIVER || streamSelectedBySession)

    /** Format, owner-specific event time and all PCM dimensions must be present. */
    fun hasTimedTechnicalFormat(format: SignalFormat?, observedAtMs: Long): Boolean =
        format != null && observedAtMs > 0L && format.sampleRate > 0 &&
            format.bitDepth > 0 && format.channels > 0

    /** Sink input configuration, not Android mixer output or DAC clock. */
    fun describeAudioTrack(format: SignalFormat): String =
        "AudioTrack · ${format.sampleRate} Hz · ${format.channels} canales · " +
            "${format.bitDepth} bits (configuración del sink, no del DAC)"

    /** Never allow NaN or Infinity to become an audible-quality claim. */
    fun finiteDb(value: Float): Float? = value.takeIf { it.isFinite() }
}
