package org.michimusic.core.intelligence

import java.text.Normalizer
import java.util.Locale

/**
 * MI-26: the ONE canonical, pure policy for labels that do not carry a musical
 * genre. It replaces the divergent lists that used to live in the disambiguation
 * policy, the normalization policy, the UI classification policy, the metadata
 * merge and the Room UPDATE guard.
 *
 * Three situations are distinct and must never be conflated:
 * - [Kind.MISSING]: an absence marker (`Unknown Genre`, `Unspecified`,
 *   `Sin género`, `No Genre`, `N/A`, ...). A track whose only values are these has
 *   no genre evidence and belongs to the honest `Sin clasificar` bucket.
 * - [Kind.CONTAINER]: a non-musical container (`Other`, `Various`, ...). Existing
 *   data is never silently deleted, but it is not a musical equivalence either and
 *   can never be proposed as, or written as, a style.
 * - [Kind.LABEL]: everything else, including a real style the 312-entry catalog
 *   does not know (`Kuduro`, `Jangle Pop`). Unknown is NOT missing.
 *
 * The policy never infers a genre from artist, album, artwork, filename or
 * listening history; it only classifies the literal text it is given.
 */
object MichiGenreAbsencePolicy {
    /** Visible name of the honest bucket for tracks without genre evidence. */
    const val UNCLASSIFIED_LABEL = "Sin clasificar"

    /** Stable id of that bucket. It is not a genre and never has approved artwork. */
    const val UNCLASSIFIED_KEY = "__ui7_unknown_genre__"

    enum class Kind { MISSING, CONTAINER, LABEL }

    private val combiningMarks = Regex("\\p{Mn}+")
    private val whitespace = Regex("\\s+")

    /**
     * Folded absence markers. `fold` strips diacritics, so `Sin género` and
     * `sin genero` are the same marker without inventing a musical identity.
     */
    private val missing: Set<String> = setOf(
        "", "unknown", "<unknown>", "[unknown]", "unknown genre", "genero desconocido",
        "genero sin identificar", "sin genero", "sin clasificar", "sin clasificacion",
        "unspecified", "not specified", "undefined", "desconocido", "none",
        "unclassified", "n/a", "no genre", "no genero", "?",
    )

    /**
     * Non-musical containers. They are preserved as literal values (never deleted,
     * never merged with a style) but are not assignable as a musical genre.
     */
    private val containers: Set<String> = setOf(
        "other", "others", "otro", "otros", "various", "various artists", "varios",
        "varias",
    )

    /** Same folding used by [MichiGenreDisambiguationPolicy] for canonical keys. */
    fun fold(raw: String): String = whitespace.replace(
        combiningMarks.replace(Normalizer.normalize(raw.trim(), Normalizer.Form.NFD), "")
            .lowercase(Locale.ROOT),
        " ",
    )

    fun classify(raw: String): Kind {
        val folded = fold(raw)
        return when (folded) {
            in missing -> Kind.MISSING
            in containers -> Kind.CONTAINER
            else -> Kind.LABEL
        }
    }

    /** True only when the value says "there is no genre information here". */
    fun isMissing(raw: String): Boolean = classify(raw) == Kind.MISSING

    /**
     * A value that may be used as a musical genre in a proposal or a tag edit.
     * Containers such as `Other`/`Various` are deliberately excluded.
     */
    fun isAssignableLabel(raw: String): Boolean = classify(raw) == Kind.LABEL

    /**
     * Ordered markers for the Room UPDATE guard. Room SQL cannot call Kotlin, so
     * `GenreMissingMarkerSql` mirrors this list; `GenreMissingMarkerSqlTest`
     * asserts every marker is present, keeping extraction and index consistent.
     */
    val missingMarkers: List<String> = missing.filter { it.isNotEmpty() }.sorted()
}
