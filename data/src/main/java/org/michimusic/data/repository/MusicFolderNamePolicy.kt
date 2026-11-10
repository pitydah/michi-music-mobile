package org.michimusic.data.repository

/**
 * A display name, never an absolute path or a sequence of path components.
 * Provider-specific forbidden characters are handled by the SAF provider;
 * this policy blocks the dangerous/unreadable cases before a write is attempted.
 */
fun normalizedMusicFolderName(raw: String): String? {
    val name = raw.trim()
    if (name.isEmpty() || name.length > 80 || name == "." || name == "..") return null
    if (name.any { ch ->
            ch == '/' || ch == '\\' || Character.isISOControl(ch) ||
                Character.getType(ch) == Character.FORMAT.toInt()
        }
    ) return null
    return name
}
