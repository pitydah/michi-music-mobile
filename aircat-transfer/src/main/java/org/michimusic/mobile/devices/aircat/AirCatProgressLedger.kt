package org.michimusic.mobile.devices.aircat

/**
 * A31: bounded, transfer-keyed UI receipts. Never starts a job, socket,
 * discovery scan or timer. Values are presentation snapshots only: the Room
 * checkpoint and journal remain the durable source of transfer truth.
 *
 * A terminal CANCELLED/COMPLETED event cannot be replaced by a delayed callback
 * from the previous worker. FAILED and PAUSED remain eligible for explicit retry.
 */
internal class AirCatProgressLedger(private val maxEntries: Int = 64) {
    init { require(maxEntries in 1..256) }

    private val entries = LinkedHashMap<String, AirCatProgressEvent>()
    // Only the accepted resume/explicit new attempt may reopen a paused/failed UI lane.
    private val permittedRestarts = mutableSetOf<String>()

    @Synchronized
    fun authorizeRestart(transferId: String): Boolean {
        val state = entries[transferId]?.state ?: return false
        if (transferId in sealed || state !in setOf(AirCatTransferState.PAUSED,
                AirCatTransferState.FAILED)) return false
        permittedRestarts.add(transferId)
        return true
    }
    // Completed/cancelled transfer IDs remain sealed after UI dismiss/eviction.
    // A transferId is single-use. Retain bounded tombstones so a late worker
    // callback cannot resurrect a dismissed transfer after the visible row goes.
    private val sealed = LinkedHashSet<String>()
    private val maxSealed = maxEntries * 4
    private fun seal(id: String) {
        sealed.add(id)
        while (sealed.size > maxSealed) sealed.remove(sealed.first())
    }

    @Synchronized
    fun latest(transferId: String): AirCatProgressEvent? = entries[transferId]

    @Synchronized
    fun snapshots(): Map<String, AirCatProgressEvent> = entries.toMap()

    /** Null means a stale/malformed event was rejected without changing UI. */
    @Synchronized
    fun record(event: AirCatProgressEvent): Map<String, AirCatProgressEvent>? {
        if (event.transferId.isBlank() || event.totalBytes < 0 || event.bytesTransferred < 0 ||
            event.totalTracks < 0 || event.currentTrackIndex < 0 ||
            (event.totalBytes > 0 && event.bytesTransferred > event.totalBytes) ||
            (event.totalTracks > 0 && event.currentTrackIndex > event.totalTracks)) return null
        if (event.transferId in sealed) return null
        // A42: COMPLETED without both receipts is a false successful batch.
        if (event.state == AirCatTransferState.COMPLETED) {
            val verified = event.totalTracks > 0 && event.totalBytes > 0L &&
                event.currentTrackIndex == event.totalTracks &&
                event.bytesTransferred == event.totalBytes &&
                event.checksumVerified && event.libraryCommitted
            if (!verified) return null
        }
        // A68-06: an empty catalogue reports NO_FILES, and only when it really moved
        // nothing. That path may never carry receipts: claiming a verified checksum or
        // a library commit for zero files is the false success this split exists to stop.
        if (event.state == AirCatTransferState.NO_FILES &&
            (event.totalTracks != 0 || event.currentTrackIndex != 0 ||
                event.totalBytes != 0L || event.bytesTransferred != 0L ||
                event.checksumVerified || event.libraryCommitted)) return null
        val previous = entries[event.transferId]
        // A41: stale callbacks after pause/failure cannot start a transfer or
        // claim a completion; only the coordinator's accepted retry may reopen it.
        if (previous?.state == AirCatTransferState.PAUSED &&
            event.state in setOf(
                AirCatTransferState.FAILED, AirCatTransferState.COMPLETED,
                AirCatTransferState.NO_FILES)) return null
        if (previous?.state == AirCatTransferState.FAILED &&
            event.state in setOf(AirCatTransferState.COMPLETED, AirCatTransferState.NO_FILES)) return null
        if (previous?.state in setOf(AirCatTransferState.PAUSED, AirCatTransferState.FAILED) &&
            event.state == AirCatTransferState.TRANSFERRING &&
            !permittedRestarts.remove(event.transferId)) return null
        if (previous?.state in setOf(
                AirCatTransferState.COMPLETED, AirCatTransferState.CANCELLED,
                AirCatTransferState.NO_FILES)) return null
        // Prefer evicting old terminal snapshots, never an active transfer.
        if (previous == null && entries.size >= maxEntries) {
            val terminal = entries.entries.firstOrNull {
                it.value.state in setOf(
                    AirCatTransferState.CANCELLED, AirCatTransferState.COMPLETED,
                    AirCatTransferState.NO_FILES, AirCatTransferState.FAILED)
            }?.key ?: return null
            // A45: an evicted FAILED receipt is not an open slot for a delayed
            // callback to resurrect a worker that has already been dismissed.
            seal(terminal)
            permittedRestarts.remove(terminal)
            entries.remove(terminal)
        }
        entries.remove(event.transferId)
        entries[event.transferId] = event
        if (event.state in setOf(
                AirCatTransferState.COMPLETED, AirCatTransferState.CANCELLED,
                AirCatTransferState.NO_FILES)) {
            permittedRestarts.remove(event.transferId)
            seal(event.transferId)
        }
        return entries.toMap()
    }

    /**
     * Hide one finished receipt without erasing a different active transfer.
     *
     * A69-01: NO_FILES is a terminal receipt too. The card offers "Aceptar", and a
     * button that cannot remove the row it belongs to is a dead control. Dismissing
     * a receipt is presentation only: it never cancels a job, deletes a checkpoint,
     * a journal or a file, and the sealed id keeps late callbacks from reopening it.
     */
    @Synchronized
    fun dismissTerminal(transferId: String): Map<String, AirCatProgressEvent>? {
        val event = entries[transferId] ?: return null
        if (event.state !in setOf(AirCatTransferState.COMPLETED,
                AirCatTransferState.CANCELLED, AirCatTransferState.FAILED,
                AirCatTransferState.NO_FILES)) return null
        entries.remove(transferId)
        return entries.toMap()
    }

    @Synchronized
    fun clear() {
        // UI clear is NOT transfer cancellation. Keep active/paused sessions
        // resumable. Tombstones for actual terminals survive clear separately.
        entries.clear()
        permittedRestarts.clear()
        // Terminal seals survive an explicit UI clear.
    }
}
