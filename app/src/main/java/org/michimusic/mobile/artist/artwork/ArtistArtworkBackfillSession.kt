package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.CancellationException

/** One user gesture; never a periodic worker or an implicit whole-library backfill.
 * Each page is already persisted durably by ArtistArtworkAutoBackfill.nextBatch().
 */
internal object ArtistArtworkBackfillSession {
    const val MAX_BATCHES = 4
    const val MAX_INSPECTED_PER_PAGE = 64
    const val MAX_QUEUED_PER_PAGE = 12

    data class Page(val inspected: Int, val queued: Int, val exhausted: Boolean)
    data class Report(val inspected: Int, val queued: Int, val batches: Int, val exhausted: Boolean)

    suspend fun run(authorized: () -> Boolean, next: suspend () -> Page): Report {
        var inspected = 0
        var queued = 0
        var batches = 0
        repeat(MAX_BATCHES) {
            if (!authorized()) throw CancellationException("El permiso o la raíz musical cambió")
            val page = next()
            require(page.inspected in 0..MAX_INSPECTED_PER_PAGE &&
                page.queued in 0..MAX_QUEUED_PER_PAGE && page.queued <= page.inspected) {
                "Lote de revisión fuera del presupuesto autorizado"
            }
            inspected += page.inspected
            queued += page.queued
            batches++
            // Zero progress is terminal even if a faulty DAO incorrectly claims more pages.
            if (page.exhausted || page.inspected == 0) {
                return Report(inspected, queued, batches, true)
            }
        }
        return Report(inspected, queued, batches, false)
    }
}
