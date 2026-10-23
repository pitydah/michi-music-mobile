package org.michimusic.core.models

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * QueueEntry models a unique position and instance in the playback queue.
 * Disambiguates duplicate tracks (e.g., A, B, A) and distinct physical representations
 * of the same musical composition.
 */
@Serializable
data class QueueEntry(
    val entryId: String = UUID.randomUUID().toString(),
    val track: Track,
    val position: Int = 0,
    val canonicalTrackId: String = track.id,
    val locationId: String? = null,
)
