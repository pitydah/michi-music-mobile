package org.michimusic.mobile.artist

/** One verifiable scope record. Never confuse external historical data with local audio tags. */
internal const val ALBUM_HISTORY_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

internal fun albumHistoryPayloadSafe(profile: AlbumHistoricalProfile): Boolean =
    canonicalAlbumMbid(profile.mbid) == profile.mbid &&
        profile.title.isNotBlank() && profile.title.length <= 300 &&
        profile.disambiguation.length <= 300 &&
        (profile.description == null ||
            profile.description.length <= 2400 && !profile.wikipediaUrl.isNullOrBlank()) &&
        allowedWikipediaSource(profile.wikipediaUrl)

internal fun albumHistoryCacheRecordValid(
    releaseGroupMbid: String,
    profile: AlbumHistoricalProfile,
    storedAtMs: Long,
    nowMs: Long,
): Boolean =
    releaseGroupMbid == profile.mbid && albumHistoryPayloadSafe(profile) &&
        storedAtMs >= 0L && nowMs >= storedAtMs

internal fun albumHistoryCacheIsStale(storedAtMs: Long, nowMs: Long): Boolean =
    nowMs >= storedAtMs && nowMs - storedAtMs >= ALBUM_HISTORY_TTL_MS
