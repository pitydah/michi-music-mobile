package org.michimusic.mobile.ui.screens

import java.security.MessageDigest

/**
 * UI5 private-copy policy. Playlist artwork is decoded from a user-selected SAF
 * document and re-published inside the app's private files directory. The
 * persisted value is a stable michi-playlist:// token, never the picker URI.
 */
internal const val PLAYLIST_PRIVATE_IMAGE_SCHEME = "michi-playlist"
internal const val PLAYLIST_PRIVATE_IMAGE_HOST = "visuals"
internal const val PLAYLIST_PRIVATE_IMAGE_DIRECTORY = "playlist-visuals"
internal const val PLAYLIST_PRIVATE_IMAGE_MAX_BYTES = 25L * 1024L * 1024L
internal const val PLAYLIST_PRIVATE_IMAGE_MAX_DIMENSION = 8192
internal const val PLAYLIST_PRIVATE_IMAGE_MAX_PIXELS = 24_000_000L
internal const val PLAYLIST_PRIVATE_IMAGE_MIN_DIMENSION = 16
internal const val PLAYLIST_PRIVATE_IMAGE_NORMALIZED_DIMENSION = 2048

internal enum class PlaylistPrivateImageFormat(
    val mime: String,
    val extension: String,
) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
}

internal fun playlistIdHash(id: String): String {
    require(id.isNotBlank())
    return MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it) }
}

/**
 * Unique per import: a new selection can never overwrite the file that is
 * currently referenced by the playlist. Only a successful atomic rename
 * publishes the file and the preference is updated after that.
 */
internal fun playlistPrivateImageName(
    playlistId: String,
    field: String,
    extension: String,
    nonce: String,
): String {
    require(playlistId.isNotBlank())
    require(field == "cover" || field == "banner")
    require(extension in setOf("jpg", "png", "webp"))
    require(nonce.matches(Regex("[A-Za-z0-9]{8,32}")))
    return "${playlistIdHash(playlistId)}-$field-$nonce.$extension"
}

internal fun playlistPrivateImageToken(fileName: String): String {
    val token = "$PLAYLIST_PRIVATE_IMAGE_SCHEME://$PLAYLIST_PRIVATE_IMAGE_HOST/$fileName"
    require(playlistPrivateImageFileName(token) != null)
    return token
}

/**
 * Accepts only names produced by [playlistPrivateImageName]. This rejects
 * traversal, absolute paths, foreign extensions and malformed tokens before a
 * path is ever resolved under the private directory.
 */
internal fun playlistPrivateImageFileName(token: String?): String? {
    if (token.isNullOrBlank()) return null
    val prefix = "$PLAYLIST_PRIVATE_IMAGE_SCHEME://$PLAYLIST_PRIVATE_IMAGE_HOST/"
    if (!token.startsWith(prefix)) return null
    val name = token.removePrefix(prefix)
    if (name.contains('/') || name.contains('\\')) return null
    val match = Regex("^([a-f0-9]{32})-(cover|banner)-([A-Za-z0-9]{8,32})\\.(jpg|png|webp)$")
        .matchEntire(name) ?: return null
    return match.value
}

internal fun playlistPrivateImageOwnedBy(playlistId: String, token: String?): Boolean {
    val name = playlistPrivateImageFileName(token) ?: return false
    return name.startsWith(playlistIdHash(playlistId) + "-")
}

internal fun detectPlaylistPrivateImageFormat(header: ByteArray): PlaylistPrivateImageFormat? = when {
    header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() &&
        header[2] == 0xFF.toByte() -> PlaylistPrivateImageFormat.JPEG
    header.size >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
        header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() &&
        header[4] == 0x0D.toByte() && header[5] == 0x0A.toByte() &&
        header[6] == 0x1A.toByte() && header[7] == 0x0A.toByte() -> PlaylistPrivateImageFormat.PNG
    header.size >= 12 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
        header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
        header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
        header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte() ->
        PlaylistPrivateImageFormat.WEBP
    else -> null
}

internal fun playlistPrivateImageBoundsAllowed(width: Int, height: Int): Boolean =
    width >= PLAYLIST_PRIVATE_IMAGE_MIN_DIMENSION &&
        height >= PLAYLIST_PRIVATE_IMAGE_MIN_DIMENSION &&
        width <= PLAYLIST_PRIVATE_IMAGE_MAX_DIMENSION &&
        height <= PLAYLIST_PRIVATE_IMAGE_MAX_DIMENSION &&
        width.toLong() * height.toLong() <= PLAYLIST_PRIVATE_IMAGE_MAX_PIXELS

/** Corrupt input must never become a persisted NaN or an unusable zoom. */
internal fun safePlaylistScale(value: Float): Float =
    value.takeIf { it.isFinite() }?.coerceIn(1f, 3f) ?: 1f
