package org.michimusic.core.intelligence

/**
 * MI-28: process-local, one-use permits for explicit MusicBrainz metadata lookups.
 * A permit is minted ONLY by the UI handler after a user reviews that EXACT payload.
 * This is not a server-side identity proof; its job is to prevent a reusable Boolean
 * from accidentally authorizing other queries, stale UI sessions or a second send.
 * It stores no library information, persists nothing and has zero idle work.
 */
class MichiGenreLookupAuthorization {
    enum class Kind { SEARCH, RECORDING }

    class Permit internal constructor(
        internal val owner: Any,
        internal val kind: Kind,
        internal val payload: String,
    )

    private val owner = Any()
    private var outstanding: Permit? = null

    fun issueSearch(intent: MichiGenreNormalizationPolicy.SearchIntent): Permit? = synchronized(this) {
        if (!MichiGenreNormalizationPolicy.isPreparedSearch(intent)) {
            outstanding = null
            null
        } else Permit(owner, Kind.SEARCH, exactPayload(intent)).also { outstanding = it }
    }

    fun issueRecording(mbid: String): Permit? = synchronized(this) {
        val canonical = MichiGenreNormalizationPolicy.canonicalMbid(mbid)
        if (canonical == null) {
            outstanding = null
            null
        } else Permit(owner, Kind.RECORDING, canonical).also { outstanding = it }
    }

    fun consumeSearch(permit: Permit?, intent: MichiGenreNormalizationPolicy.SearchIntent): Boolean =
        consume(permit, Kind.SEARCH, exactPayload(intent)) &&
            MichiGenreNormalizationPolicy.isPreparedSearch(intent)

    fun consumeRecording(permit: Permit?, mbid: String): Boolean =
        consume(permit, Kind.RECORDING, MichiGenreNormalizationPolicy.canonicalMbid(mbid).orEmpty())

    private fun consume(permit: Permit?, kind: Kind, payload: String): Boolean = synchronized(this) {
        val expected = outstanding
        outstanding = null  // Even a mismatch exhausts any previous permission.
        permit != null && permit === expected && permit.owner === owner &&
            permit.kind == kind && permit.payload == payload && payload.isNotBlank()
    }

    fun revoke() = synchronized(this) { outstanding = null }

    private fun exactPayload(intent: MichiGenreNormalizationPolicy.SearchIntent): String =
        listOf(intent.title, intent.artist, intent.album).joinToString(separator = "\u0000") {
            "${it.length}:$it"
        }
}
