package org.michimusic.mobile.ui.screens

import java.security.MessageDigest
import org.michimusic.core.models.LibraryScope
import java.util.Locale
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

/**
 * Opaque navigation identity for a concrete library album snapshot. A MediaStore album ID
 * is NOT globally unique across libraries. Membership and local credit disambiguate it;
 * file paths, remote catalogue MBIDs, and mutable playback URIs are intentionally excluded.
 */
fun albumNavigationIdentity(group: LocalAlbum, scope: LibraryScope): String {
    val album = group.album
    val members = group.tracks.map { it.id }.distinct().sorted()
    val scopeToken = when (scope) {
        LibraryScope.All -> "all"
        is LibraryScope.One -> "one:${scope.libraryId}"
    }
    val fields = listOf("michi-album-nav-v2", scopeToken, album.id,
        album.title.trim().lowercase(Locale.ROOT),
        album.artist.trim().lowercase(Locale.ROOT), album.year.toString()) + members
    // Length-prefixed fields avoid delimiter collisions even with pathological track IDs.
    val material = fields.joinToString("") { "${it.length}:$it" }
    val hex = MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "alb2_$hex" // Uri-safe, does not expose local metadata in navigation.
}

/** Different groups with identical tokens are indistinguishable: fail closed. */
fun albumNavigationIdentityCounts(albums: List<LocalAlbum>, scope: LibraryScope): Map<String, Int> =
    albums.groupingBy { albumNavigationIdentity(it, scope) }.eachCount()

fun resolveNavigationAlbum(albums: List<LocalAlbum>, token: String, scope: LibraryScope): LocalAlbum? =
    if (!token.matches(Regex("^alb2_[0-9a-f]{64}$"))) null
    else albums.singleOrNull { albumNavigationIdentity(it, scope) == token }
