package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.Track

/** Pure metadata policy. Never substitutes a codec, depth or DAC capability. */
internal fun albumCodecLabel(track: Track): String? = track.format.trim()
    .substringAfterLast('/')
    .takeIf { it.isNotBlank() && it.length <= 18 }
    ?.uppercase(java.util.Locale.ROOT)

internal fun albumCodecSummary(tracks: List<Track>): String? {
    val known = tracks.mapNotNull(::albumCodecLabel).distinct()
    return when {
        tracks.isEmpty() || known.isEmpty() -> null
        known.size == 1 && tracks.all { albumCodecLabel(it) != null } -> known.single()
        else -> "Formatos mixtos"
    }
}

internal fun albumTrackCountLabel(count: Int, allLocal: Boolean = true): String =
    if (allLocal) "$count ${if (count == 1) "pista local" else "pistas locales"}"
    else "$count ${if (count == 1) "pista" else "pistas"} de la biblioteca"
