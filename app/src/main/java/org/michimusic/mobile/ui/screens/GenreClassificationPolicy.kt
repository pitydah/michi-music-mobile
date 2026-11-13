package org.michimusic.mobile.ui.screens

import java.text.Normalizer
import java.util.Locale

/** Single-pass, source-preserving semantic keys. No network, audio analysis or tag mutation. */
private val genreDiacritics = Regex("\\p{Mn}+")
private val genreWhitespace = Regex("\\s+")
internal fun genreCleanLabel(label: String): String = genreWhitespace.replace(label.trim(), " ")
internal fun genreGroupingKey(label: String): String {
    val compact = genreWhitespace.replace(
        genreDiacritics.replace(Normalizer.normalize(label.trim(), Normalizer.Form.NFD), "")
            .lowercase(Locale.ROOT), " ")
    return when (compact) {
        "synthpop", "synth pop", "synth-pop" -> "synth-pop"
        "newwave", "new-wave", "new wave" -> "new wave"
        "lofi", "lo fi", "lo-fi" -> "lo-fi"
        "hiphop", "hip hop", "hip-hop" -> "hip-hop"
        "kpop", "k-pop", "k pop" -> "k-pop"
        "jpop", "j-pop", "j pop" -> "j-pop"
        "rnb", "r&b", "r and b" -> "r&b"
        "drum and bass", "drum & bass", "drum n bass", "drum bass" -> "drum bass"
        "rock 'n' roll", "rock n roll", "rock and roll", "rock & roll", "rock roll" -> "rock roll"
        "italo-disco", "italo disco" -> "italo disco"
        else -> compact
    }
}

/** These are missing-metadata markers, not real musical classifications. */
private val genreUnknownKeys = setOf(
    "<unknown>", "unknown", "sin genero", "sin clasificar", "unspecified",
    "not specified", "none", "unclassified", "unknown genre", "n/a", "", "?"
)
internal fun genreTagIsUnknownKey(key: String): Boolean = key in genreUnknownKeys
internal fun genreTagIsUnknown(label: String): Boolean = genreTagIsUnknownKey(genreGroupingKey(label))

/** Stable names only for vetted spelling aliases. Every Track.genre retains its literal tag. */
internal fun genreCanonicalDisplayName(key: String, original: String): String = when (key) {
    "synth-pop" -> "Synth-Pop"
    "new wave" -> "New Wave"
    "lo-fi" -> "Lo-Fi"
    "hip-hop" -> "Hip-Hop"
    "k-pop" -> "K-Pop"
    "j-pop" -> "J-Pop"
    "r&b" -> "R&B"
    "drum bass" -> "Drum & Bass"
    "rock roll" -> "Rock & Roll"
    "italo disco" -> "Italo Disco"
    else -> original
}

/** Stable representative from actual metadata, independent of library enumeration order. */
internal fun genrePreferredDisplayTitle(existing: String, incoming: String): String =
    if (incoming < existing) incoming else existing
