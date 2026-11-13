package org.michimusic.data.repository

/** Original evidence is preferred over platform metadata; never infer values from filenames. */
internal object MetadataFieldMerge {
    fun choose(embedded: String?, platform: String?, fallback: String = ""): String =
        sequenceOf(embedded, platform).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull { value ->
                !value.equals("unknown", true) &&
                !value.equals("unknown artist", true) &&
                !value.equals("unknown album", true) &&
                !value.equals("<unknown>", true)
            } ?: fallback
}
