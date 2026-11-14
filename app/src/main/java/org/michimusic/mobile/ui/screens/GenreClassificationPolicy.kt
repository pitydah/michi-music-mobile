package org.michimusic.mobile.ui.screens

import org.michimusic.core.intelligence.MichiGenreDisambiguationPolicy

/** Shared MI-26 canonical key. UI7 owns the scope; the core resolver owns synonyms. */
private val genreWhitespace = Regex("\\s+")
internal fun genreCleanLabel(label: String): String = genreWhitespace.replace(label.trim(), " ")
internal fun genreGroupingKey(label: String): String = MichiGenreDisambiguationPolicy.key(label)

/** These are missing-metadata markers, not real musical classifications. */
private val genreUnknownKeys = setOf(
    "<unknown>", "unknown", "sin genero", "sin clasificar", "unspecified",
    "not specified", "none", "unclassified", "unknown genre", "n/a", "", "?"
)
internal fun genreTagIsUnknownKey(key: String): Boolean = key in genreUnknownKeys
internal fun genreTagIsUnknown(label: String): Boolean = genreTagIsUnknownKey(genreGroupingKey(label))

/** Stable names only for vetted spelling aliases. Every Track.genre retains its literal tag. */
internal fun genreCanonicalDisplayName(key: String, original: String): String =
    MichiGenreDisambiguationPolicy.display(key, original)

/** Stable representative from actual metadata, independent of library enumeration order. */
internal fun genrePreferredDisplayTitle(existing: String, incoming: String): String =
    if (incoming < existing) incoming else existing
