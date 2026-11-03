package org.michimusic.link

import java.io.File
import java.util.Locale

/** Fail-closed path policy for legacy package imports; no untrusted path separators. */
internal fun validatedImportDestination(root: File, trackId: String, format: String): File? {
    if (!Regex("^[A-Za-z0-9_-]{1,120}$").matches(trackId)) return null
    val ext = format.lowercase(Locale.ROOT)
    if (ext !in setOf("mp3", "flac", "wav", "aiff", "aif", "m4a", "aac", "ogg", "opus", "wv")) return null
    return runCatching {
        val destination = File(root, "$trackId.$ext")
        if (destination.canonicalFile.parentFile == root.canonicalFile && !destination.exists()) destination else null
    }.getOrNull()
}
