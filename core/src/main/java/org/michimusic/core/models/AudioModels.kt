package org.michimusic.core.models

import kotlinx.serialization.Serializable

/**
 * Represents the exact PCM audio parameters emitted by the local decoder pipeline
 * or expected by a remote receiver.
 */
@Serializable
data class PcmFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val codec: String = when (bitDepth) {
        24 -> "pcm_s24le"
        32 -> "pcm_s32le"
        else -> "pcm_s16le"
    },
)
