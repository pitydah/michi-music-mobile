package org.michimusic.mobile.artist.artwork

import java.security.MessageDigest

/**
 * Pure one-shot wakeup policy for already persisted Room outbox deadlines.
 * Time is coalesced to an hour: thousands of artists need at most one wakeup
 * for the earliest due bucket, never one timer or Worker per track/artist.
 */
internal object ArtistArtworkDeferredWakePolicy {
    internal const val WINDOW_MS = 60L * 60 * 1000
    internal const val MAX_HORIZON_MS = 8L * 24 * WINDOW_MS

    /** Null means no valid future wakeup. Already-due rows use the immediate dispatcher. */
    fun wakeTime(nowEpochMs: Long, dueEpochMs: Long): Long? {
        if (nowEpochMs < 0 || dueEpochMs <= nowEpochMs || dueEpochMs < 0) return null
        if (dueEpochMs - nowEpochMs > MAX_HORIZON_MS) return null
        val remaining = dueEpochMs % WINDOW_MS
        val padding = if (remaining == 0L) 0L else WINDOW_MS - remaining
        if (dueEpochMs > Long.MAX_VALUE - padding) return null
        return dueEpochMs + padding
    }

    /** A stable digest of *scoped* source identity. No root URI is placed in WorkManager.Data. */
    fun sourceFingerprint(libraryId: String, sourceId: String): String? {
        if (libraryId.isBlank() || sourceId.isBlank()) return null
        val serialized = "${libraryId.length}:$libraryId:${sourceId.length}:$sourceId"
        return MessageDigest.getInstance("SHA-256")
            .digest(serialized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun uniqueWorkName(libraryId: String, sourceId: String, wakeAtMs: Long): String? {
        val fingerprint = sourceFingerprint(libraryId, sourceId) ?: return null
        if (wakeAtMs <= 0 || wakeAtMs % WINDOW_MS != 0L) return null
        return "michi.artist.artwork.deferred.${fingerprint.take(24)}.${wakeAtMs / WINDOW_MS}"
    }
}
