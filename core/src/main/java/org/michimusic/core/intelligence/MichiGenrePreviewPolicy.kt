package org.michimusic.core.intelligence

/**
 * MI-23 5: a multi-value preview for the future GENRE editor.
 *
 * The previous dialog held a single `chosenGenre: String`; committing it would
 * have replaced a whole verified tag set (`Post-Punk, New Wave, Darkwave`) with
 * ONE value. This policy keeps every original value by default and allows
 * exactly one explicit operation at a time: add, replace one, remove one.
 *
 * It is pure and never writes a file. Physical writes stay fail-closed behind
 * `MI23_TAG_WRITES_CERTIFIED`; this model only produces the honest diff.
 */
object MichiGenrePreviewPolicy {
    const val MAX_GENRES = MichiGenreNormalizationPolicy.MAX_GENRES
    private const val MAX_LABEL_CHARS = MichiGenreNormalizationPolicy.MAX_GENRE_CHARS

    sealed interface Edit {
        data class Accepted(val preview: Preview) : Edit
        data class Rejected(val reason: String) : Edit
    }

    data class Diff(
        val added: List<String>,
        val removed: List<String>,
        /** Present in both lists, compared by canonical key. */
        val unchanged: List<String>,
    )

    data class Preview(
        /** Verified values as read from the file; never mutated by an edit. */
        val original: List<String>,
        /** What the user is currently previewing. */
        val proposed: List<String>,
    ) {
        val isModified: Boolean get() = keyList(original) != keyList(proposed)
        val isFull: Boolean get() = proposed.size >= MAX_GENRES

        /** Structural reason the preview can never become a write payload. */
        val structuralError: String?
            get() = when {
                proposed.size > MAX_GENRES -> "MAX_GENRES"
                proposed.any { !validLabel(it) } -> "INVALID_GENRE"
                else -> null
            }

        fun diff(): Diff {
            val originalKeys = keyList(original).toSet()
            val proposedKeys = keyList(proposed).toSet()
            return Diff(
                added = proposed.filter { key(it) !in originalKeys },
                removed = original.filter { key(it) !in proposedKeys },
                unchanged = proposed.filter { key(it) in originalKeys },
            )
        }

        fun add(label: String): Edit {
            val value = clean(label)
            invalidReason(value)?.let { return Edit.Rejected(it) }
            if (proposed.size >= MAX_GENRES) return Edit.Rejected("MAX_GENRES")
            if (key(value) in keyList(proposed)) return Edit.Rejected("DUPLICATE_GENRE")
            return Edit.Accepted(copy(proposed = proposed + value))
        }

        /** Leaves every other value untouched, including its original position. */
        fun removeAt(index: Int): Edit {
            if (index !in proposed.indices) return Edit.Rejected("INVALID_INDEX")
            return Edit.Accepted(copy(proposed = proposed.filterIndexed { i, _ -> i != index }))
        }

        /** Replaces exactly one position; the remaining N-1 values are preserved. */
        fun replaceAt(index: Int, label: String): Edit {
            if (index !in proposed.indices) return Edit.Rejected("INVALID_INDEX")
            val value = clean(label)
            invalidReason(value)?.let { return Edit.Rejected(it) }
            if (key(value) in keyList(proposed).filterIndexed { i, _ -> i != index })
                return Edit.Rejected("DUPLICATE_GENRE")
            val next = proposed.toMutableList().also { it[index] = value }
            return Edit.Accepted(copy(proposed = next))
        }
    }

    /**
     * Start from the verified tags exactly as they were read. Missing-metadata
     * markers are kept visible in `original` (nothing disappears silently) and are
     * also kept in `proposed` until the user removes them explicitly.
     */
    fun of(verifiedGenres: List<String>): Preview {
        val original = linkedMapOf<String, String>()
        for (raw in verifiedGenres) {
            val value = clean(raw)
            if (value.isEmpty()) continue
            original.putIfAbsent(key(value), value)
        }
        val values = original.values.toList()
        return Preview(original = values, proposed = values)
    }

    fun key(label: String): String {
        val cleaned = clean(label)
        return MichiGenreDisambiguationPolicy.key(cleaned).ifBlank {
            MichiGenreAbsencePolicy.fold(cleaned)
        }
    }

    private fun keyList(values: List<String>): List<String> = values.map(::key)

    private fun clean(label: String): String = label.trim().replace(Regex("\\s+"), " ")

    private fun validLabel(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_LABEL_CHARS &&
            '\uFFFD' !in value && value.none { it.isISOControl() }

    private fun invalidReason(value: String): String? = when {
        !validLabel(value) -> "INVALID_GENRE"
        // A placeholder is not a musical genre and is never assigned by an edit.
        !MichiGenreAbsencePolicy.isAssignableLabel(value) -> "NOT_A_MUSICAL_GENRE"
        else -> null
    }
}
