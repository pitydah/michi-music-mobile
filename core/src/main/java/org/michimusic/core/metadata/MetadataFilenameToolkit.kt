package org.michimusic.core.metadata

import java.util.Locale

/**
 * ME-01: deterministic, read-only filename/tag conversion. Never renames a URI.
 * Templates use literal separators and tokens such as "%track% - %artist% - %title%".
 * No user-supplied regular expressions, directories, globbing or filesystem access.
 */
object MetadataFilenameToolkit {
    enum class Reason {
        INVALID_TEMPLATE, INVALID_FILENAME, AMBIGUOUS_TOKENS, UNKNOWN_TOKEN,
        DUPLICATE_TOKEN, NO_MATCH, INVALID_CAPTURE, UNVERIFIED_TAGS,
        MISSING_TAG, MULTI_VALUE_AMBIGUOUS, UNSAFE_RESULT, DUPLICATE_DESTINATION,
        EMPTY_SELECTION, MIXED_SOURCES, TOO_MANY_TRACKS
    }

    sealed interface Result<out T> {
        data class Success<T>(val value: T) : Result<T>
        data class Rejected(val reason: Reason) : Result<Nothing>
    }

    data class ProposedRename(
        val target: MetadataTarget,
        val before: String,
        val after: String,
        /** This is NOT a permit to rename: an Android provider collision check is still mandatory. */
        val providerCollisionCheckRequired: Boolean = true,
    )

    private sealed interface Part {
        data class Literal(val value: String) : Part
        data class Tag(val field: MetadataField) : Part
    }

    private val tokens = mapOf(
        "track" to MetadataField.TRACK_NUMBER,
        "disc" to MetadataField.DISC_NUMBER,
        "title" to MetadataField.TITLE,
        "artist" to MetadataField.ARTIST,
        "album" to MetadataField.ALBUM,
        "albumartist" to MetadataField.ALBUM_ARTIST,
        "genre" to MetadataField.GENRE,
        "year" to MetadataField.RELEASE_DATE,
        "composer" to MetadataField.COMPOSER,
        "label" to MetadataField.LABEL,
        "work" to MetadataField.WORK,
        "movement" to MetadataField.MOVEMENT,
    )

    private fun compile(template: String): Result<List<Part>> {
        if (template.isBlank() || template.length > 240 || '\u0000' in template ||
            '/' in template || '\\' in template) return Result.Rejected(Reason.INVALID_TEMPLATE)
        val output = mutableListOf<Part>()
        val seen = mutableSetOf<MetadataField>()
        var cursor = 0
        while (cursor < template.length) {
            val open = template.indexOf('%', cursor)
            if (open < 0) {
                output += Part.Literal(template.substring(cursor))
                break
            }
            if (open > cursor) output += Part.Literal(template.substring(cursor, open))
            val end = template.indexOf('%', open + 1)
            if (end <= open + 1) return Result.Rejected(Reason.INVALID_TEMPLATE)
            val name = template.substring(open + 1, end).lowercase(Locale.ROOT)
            val field = tokens[name] ?: return Result.Rejected(Reason.UNKNOWN_TOKEN)
            if (!seen.add(field)) return Result.Rejected(Reason.DUPLICATE_TOKEN)
            if (output.lastOrNull() is Part.Tag) return Result.Rejected(Reason.AMBIGUOUS_TOKENS)
            output += Part.Tag(field)
            cursor = end + 1
        }
        if (output.none { it is Part.Tag }) return Result.Rejected(Reason.INVALID_TEMPLATE)
        return Result.Success(output)
    }

    /** Extracts individual, still-unverified proposals; caller must review each value. */
    fun tagsFromFilename(fileName: String, template: String): Result<List<MetadataChange>> {
        val parts = when (val parsed = compile(template)) {
            is Result.Rejected -> return parsed
            is Result.Success -> parsed.value
        }
        if (!validFilename(fileName)) return Result.Rejected(Reason.INVALID_FILENAME)
        val base = fileName.substringBeforeLast('.', fileName)
        val tags = parts.filterIsInstance<Part.Tag>()
        if (tags.size > 12) return Result.Rejected(Reason.INVALID_TEMPLATE)
        val regex = buildString {
            append('^')
            for (p in parts) when (p) {
                is Part.Literal -> append(Regex.escape(p.value))
                is Part.Tag -> append("(.+?)")
            }
            append('$')
        }.toRegex()
        val match = regex.matchEntire(base) ?: return Result.Rejected(Reason.NO_MATCH)
        val result = mutableListOf<MetadataChange>()
        tags.forEachIndexed { index, tag ->
            val value = match.groupValues[index + 1]
            if (value.isBlank() || value.length > tag.field.maxChars || '\u0000' in value ||
                (tag.field == MetadataField.TRACK_NUMBER || tag.field == MetadataField.DISC_NUMBER) &&
                (value.toIntOrNull() ?: 0) !in 1..9999
            ) return Result.Rejected(Reason.INVALID_CAPTURE)
            result += MetadataChange.Replace(tag.field, listOf(value))
        }
        return Result.Success(result)
    }

    /** Only proposes a basename. No SAF/MediaStore operations and no auto-sanitizing metadata. */
    fun proposeRename(
        snapshot: MetadataSnapshot, originalName: String, template: String,
    ): Result<ProposedRename> {
        if (!snapshot.tagsVerified) return Result.Rejected(Reason.UNVERIFIED_TAGS)
        if (!validFilename(originalName)) return Result.Rejected(Reason.INVALID_FILENAME)
        val parts = when (val compiled = compile(template)) {
            is Result.Rejected -> return compiled
            is Result.Success -> compiled.value
        }
        val extension = originalName.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isBlank() || extension.length > 8 || !extension.all { it.isLetterOrDigit() })
            return Result.Rejected(Reason.INVALID_FILENAME)
        val stem = buildString {
            for (part in parts) when (part) {
                is Part.Literal -> append(part.value)
                is Part.Tag -> {
                    if (part.field !in snapshot.verifiedFields)
                        return Result.Rejected(Reason.UNVERIFIED_TAGS)
                    val values = snapshot.known[part.field].orEmpty()
                    if (values.size != 1) return Result.Rejected(
                        if (values.isEmpty()) Reason.MISSING_TAG else Reason.MULTI_VALUE_AMBIGUOUS)
                    append(values.single())
                }
            }
        }
        val result = "$stem.$extension"
        if (!validFilename(result) || result.length > 240) return Result.Rejected(Reason.UNSAFE_RESULT)
        return Result.Success(ProposedRename(snapshot.target, originalName, result))
    }

    fun proposeRenameBatch(
        snapshots: List<MetadataSnapshot>, filenames: List<String>, template: String,
    ): Result<List<ProposedRename>> {
        if (snapshots.isEmpty()) return Result.Rejected(Reason.EMPTY_SELECTION)
        if (snapshots.size > 128 || filenames.size != snapshots.size)
            return Result.Rejected(Reason.TOO_MANY_TRACKS)
        if (snapshots.map { it.target.sourceId }.distinct().size != 1)
            return Result.Rejected(Reason.MIXED_SOURCES)
        if (snapshots.map { it.target.trackId }.distinct().size != snapshots.size ||
            snapshots.map { it.target.uri }.distinct().size != snapshots.size)
            return Result.Rejected(Reason.INVALID_FILENAME)
        val renames = mutableListOf<ProposedRename>()
        for (index in snapshots.indices) when (val item = proposeRename(snapshots[index], filenames[index], template)) {
            is Result.Rejected -> return item
            is Result.Success -> renames += item.value
        }
        // Case-insensitive collisions are a necessary but insufficient preflight for SAF.
        if (renames.map { it.after.lowercase(Locale.ROOT) }.distinct().size != renames.size)
            return Result.Rejected(Reason.DUPLICATE_DESTINATION)
        return Result.Success(renames)
    }

    private fun validFilename(name: String): Boolean = name.isNotBlank() && name.length <= 255 &&
        name !in setOf(".", "..") && name.first() != '.' && !name.endsWith('.') &&
        !name.endsWith(' ') && name.none { c -> c.code < 32 || c.code == 127 || c in "<>:\"/\\|?*" }
}
