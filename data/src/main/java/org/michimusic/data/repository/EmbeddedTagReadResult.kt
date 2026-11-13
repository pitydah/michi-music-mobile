package org.michimusic.data.repository

/** Bounded, explicit local tag-read status. Never store source URIs or exception text. */
internal sealed interface EmbeddedTagReadResult {
    data class Parsed(val fields: EmbeddedTagFields) : EmbeddedTagReadResult
    /** A readable parser result contains other fields but no genre tag. */
    data class NoGenreTag(val fields: EmbeddedTagFields) : EmbeddedTagReadResult
    /** Android explicitly denied reading the requested content URI. */
    data object PermissionDenied : EmbeddedTagReadResult
    /** Both descriptor and stream reads failed at the I/O/provider boundary. */
    data object ReadFailed : EmbeddedTagReadResult
    /** Provider returned data but parser could not prove tags (unsupported/truncated). */
    data object Unverifiable : EmbeddedTagReadResult
}

internal object EmbeddedTagReadPolicy {
    fun fromFields(fields: EmbeddedTagFields?): EmbeddedTagReadResult = when {
        fields == null -> EmbeddedTagReadResult.Unverifiable
        fields.genres.isEmpty() -> EmbeddedTagReadResult.NoGenreTag(fields)
        else -> EmbeddedTagReadResult.Parsed(fields)
    }

    fun fieldsOrNull(result: EmbeddedTagReadResult): EmbeddedTagFields? = when (result) {
        is EmbeddedTagReadResult.Parsed -> result.fields
        is EmbeddedTagReadResult.NoGenreTag -> result.fields
        else -> null
    }
}
