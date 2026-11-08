package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.Track

/** Pure file-metadata policy. Never substitutes a codec, depth or DAC capability. */
internal fun albumCodecLabel(track: Track): String? {
    val extension = codecFromExtension(track.sourceFileExtension)
    val mime = codecFromMime(track.sourceMimeType)
    if (extension != null && mime != null && !extension.compatibleWith(mime)) return null
    return extension?.label ?: mime?.label ?: codecFromProviderFormat(track.format)?.label
}

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

private data class CodecFact(val key: String, val label: String)

private fun CodecFact.compatibleWith(other: CodecFact): Boolean =
    key == other.key || other.key == "mpeg-audio" && key in setOf("mp3", "mp2") ||
        other.key == "ogg" && key == "opus"

private fun codecFromExtension(raw: String): CodecFact? = when (raw.trim().removePrefix(".").lowercase()) {
    "mp3" -> CodecFact("mp3", "MP3")
    "mp2" -> CodecFact("mp2", "MP2")
    "flac" -> CodecFact("flac", "FLAC")
    "aac" -> CodecFact("aac", "AAC")
    "alac" -> CodecFact("alac", "ALAC")
    "wav", "wave" -> CodecFact("wav", "WAV")
    "aif", "aiff" -> CodecFact("aiff", "AIFF")
    "ogg", "oga" -> CodecFact("ogg", "Ogg")
    "opus" -> CodecFact("opus", "Opus")
    "wv" -> CodecFact("wavpack", "WavPack")
    else -> null
}

private fun codecFromMime(raw: String): CodecFact? = when (raw.trim().lowercase()) {
    "audio/mpeg", "audio/mpa" -> CodecFact("mpeg-audio", "Audio MPEG")
    "audio/flac", "audio/x-flac" -> CodecFact("flac", "FLAC")
    "audio/aac", "audio/aacp" -> CodecFact("aac", "AAC")
    "audio/alac", "audio/x-alac" -> CodecFact("alac", "ALAC")
    "audio/wav", "audio/wave", "audio/x-wav" -> CodecFact("wav", "WAV")
    "audio/aiff", "audio/x-aiff" -> CodecFact("aiff", "AIFF")
    "audio/ogg", "application/ogg" -> CodecFact("ogg", "Ogg")
    "audio/opus" -> CodecFact("opus", "Opus")
    "audio/wavpack", "audio/x-wavpack" -> CodecFact("wavpack", "WavPack")
    "audio/mp2", "audio/mpeg-l2" -> CodecFact("mp2", "MP2")
    "audio/mp3", "audio/x-mp3" -> CodecFact("mp3", "MP3")
    else -> null
}

private fun codecFromProviderFormat(raw: String): CodecFact? {
    val normalized = raw.trim().lowercase()
    return codecFromMime(normalized) ?: codecFromExtension(normalized.substringAfterLast('/'))
}
