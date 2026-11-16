package org.michimusic.mobile.artist

/**
 * K48-R05: the exact target of one destructive enrichment operation.
 *
 * A deletion is only ever allowed to touch the identity that asked for it: the library,
 * the literal credit, the album set, the verified MBID and the association group are all
 * captured before the user confirms, and [sequence] makes a stale receipt identifiable.
 * Nothing here can be re-targeted by a later re-bind.
 */
data class EnrichmentDeleteReceipt(
    val libraryId: String?,
    val artistCredit: String,
    val albumIds: List<String>,
    val mbid: String?,
    val associationGroupKey: String?,
    val sequence: Long,
) {
    /** True only when [libraryId], [artistCredit] and the album set are still the same ones. */
    fun targets(libraryId: String?, artistCredit: String, albumIds: List<String>): Boolean =
        this.libraryId == libraryId &&
            this.artistCredit == artistCredit &&
            this.albumIds.sorted() == albumIds.sorted()
}

/**
 * Why a destructive operation did not fully complete. Closed on purpose: the UI receives a
 * stable reason instead of a raw exception message, path or URL.
 */
enum class EnrichmentDeleteFailure {
    ASSOCIATION_NOT_REMOVED,
    ARTWORK_FILES_REMAIN,
}

// Same user-facing copy the panel already shipped, so the reason never reads as success.
fun EnrichmentDeleteFailure.userMessage(): String = when (this) {
    EnrichmentDeleteFailure.ASSOCIATION_NOT_REMOVED ->
        "No fue posible borrar todos los datos guardados."
    EnrichmentDeleteFailure.ARTWORK_FILES_REMAIN ->
        "Se borró la asociación, pero no todos los archivos de imagen."
}

/**
 * K48-R05: the delete lifecycle is explicit. [Confirming] is the only state in which nothing
 * has been deleted yet; [PartiallyCompleted] is never rendered as a full success.
 */
sealed interface EnrichmentDeleteState {
    data object Idle : EnrichmentDeleteState
    data class Confirming(val receipt: EnrichmentDeleteReceipt) : EnrichmentDeleteState
    data class Deleting(val receipt: EnrichmentDeleteReceipt) : EnrichmentDeleteState
    data class Completed(val receipt: EnrichmentDeleteReceipt) : EnrichmentDeleteState
    data class PartiallyCompleted(
        val receipt: EnrichmentDeleteReceipt,
        val failure: EnrichmentDeleteFailure,
    ) : EnrichmentDeleteState

    data class Failed(
        val receipt: EnrichmentDeleteReceipt,
        val failure: EnrichmentDeleteFailure,
    ) : EnrichmentDeleteState
}
