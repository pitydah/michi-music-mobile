package org.michimusic.mobile.ui.screens

import org.michimusic.core.intelligence.MichiGenreAbsencePolicy
import org.michimusic.core.intelligence.MichiGenreDisambiguationPolicy

/** Shared MI-26 canonical key. UI7 owns the scope; the core resolver owns synonyms. */
private val genreWhitespace = Regex("\\s+")
internal fun genreCleanLabel(label: String): String = genreWhitespace.replace(label.trim(), " ")
internal fun genreGroupingKey(label: String): String = MichiGenreDisambiguationPolicy.key(label)

/**
 * Missing-metadata markers are NOT real musical classifications. The one shared
 * list lives in [MichiGenreAbsencePolicy]; `Other`/`Various` are containers and
 * remain visible literal groups rather than silently disappearing.
 */
internal fun genreTagIsUnknownKey(key: String): Boolean = MichiGenreAbsencePolicy.isMissing(key)
internal fun genreTagIsUnknown(label: String): Boolean = genreTagIsUnknownKey(genreGroupingKey(label))

/** Stable names only for vetted spelling aliases. Every Track.genre retains its literal tag. */
internal fun genreCanonicalDisplayName(key: String, original: String): String =
    MichiGenreDisambiguationPolicy.display(key, original)

/** Stable representative from actual metadata, independent of library enumeration order. */
internal fun genrePreferredDisplayTitle(existing: String, incoming: String): String =
    if (incoming < existing) incoming else existing
