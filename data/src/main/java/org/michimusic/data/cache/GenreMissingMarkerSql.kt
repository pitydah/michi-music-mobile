package org.michimusic.data.cache

/**
 * Room cannot call Kotlin, so the "no genre yet" UPDATE guard needs a literal
 * marker list. It mirrors [org.michimusic.core.intelligence.MichiGenreAbsencePolicy]
 * (plus the accented spellings a provider can return) and is asserted by
 * `GenreMissingMarkerSqlTest`; extraction, index and UI therefore share one policy.
 */
internal object GenreMissingMarkerSql {
    const val MARKERS: String =
        "'unknown','<unknown>','[unknown]','unknown genre'," +
            "'genero desconocido','género desconocido'," +
            "'genero sin identificar','género sin identificar'," +
            "'sin genero','sin género','sin clasificar','sin clasificacion','sin clasificación'," +
            "'unspecified','not specified','undefined','desconocido','none','unclassified'," +
            "'n/a','no genre','no genero','no género','?'"

    /** TRUE when the cached row has no usable genre evidence yet. */
    const val IS_MISSING: String = "TRIM(genre) = '' OR LOWER(TRIM(genre)) IN (" + MARKERS + ")"
}
