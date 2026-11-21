package org.michimusic.core.metadata

/**
 * ME-00: read-only, format-independent metadata edit planning.
 *
 * This file NEVER opens an audio file, changes Room, or grants writing permission.
 * A future writer must re-read and revalidate owner, URI, evidence and format
 * capabilities before touching the original bytes.
 */
enum class MetadataField(val multiple: Boolean = false, val maxChars: Int = 2048) {
    TITLE,
    SUBTITLE,
    ARTIST(multiple = true),
    ALBUM,
    ALBUM_ARTIST(multiple = true),
    GENRE(multiple = true),
    COMPOSER(multiple = true),
    ARRANGER(multiple = true),
    PRODUCER(multiple = true),
    ENGINEER(multiple = true),
    MIXER(multiple = true),
    REMIXER(multiple = true),
    LYRICIST(multiple = true),
    CONDUCTOR(multiple = true),
    PERFORMER(multiple = true),
    WORK,
    MOVEMENT,
    RELEASE_DATE,
    ORIGINAL_RELEASE_DATE,
    TRACK_NUMBER,
    TRACK_TOTAL,
    DISC_NUMBER,
    DISC_TOTAL,
    LABEL,
    CATALOG_NUMBER,
    ISRC,
    COMMENT(maxChars = 8192),
    LYRICS(maxChars = 65536),
    SYNCED_LYRICS(maxChars = 65536),
    GROUPING,
    MOOD,
    LANGUAGE,
    RELEASE_COUNTRY,
    PUBLISHER,
    COPYRIGHT,
    BARCODE,
    WEBSITE,
    BPM,
    MUSICAL_KEY,
    MUSICBRAINZ_RECORDING_ID,
    MUSICBRAINZ_RELEASE_TRACK_ID,
    MUSICBRAINZ_WORK_ID,
    DISCOGS_RELEASE_ID,
    MUSICBRAINZ_RELEASE_ID,
    MUSICBRAINZ_RELEASE_GROUP_ID,
    MUSICBRAINZ_ARTIST_ID(multiple = true),
    ALBUM_SUBTITLE,
    ORIGINAL_ARTIST(multiple = true),
    ORCHESTRA(multiple = true),
    ENSEMBLE(multiple = true),
    SOLOIST(multiple = true),
    INSTRUMENT(multiple = true),
    MASTERING_ENGINEER(multiple = true),
    EXECUTIVE_PRODUCER(multiple = true),
    WORK_NUMBER,
    OPUS_NUMBER,
    MOVEMENT_NUMBER,
    MOVEMENT_TOTAL,
    RECORDING_DATE,
    EDITION,
    RELEASE_TYPE,
    RELEASE_STATUS,
    MEDIA_FORMAT,
    COMPILATION,
    SCRIPT,
    MUSICBRAINZ_LABEL_ID(multiple = true),
}

/** Evidence token is opaque and must not be interpreted as an authorization. */
data class MetadataTarget(
    val sourceId: String,
    val trackId: String,
    val uri: String,
    val evidenceToken: String,
) {
    init {
        require(trackId.isNotBlank()) // sourceId == "" identifies the local library.
        require(uri.isNotBlank() && evidenceToken.isNotBlank())
    }
}

/**
 * Embedded values remain structured. A literal 'Jazz; Fusion' is one value,
 * not the same as the separate values ['Jazz', 'Fusion'].
 * Unsupported/unknown tags are retained separately, not coerced into known tags.
 */
data class MetadataSnapshot(
    val target: MetadataTarget,
    val tagsVerified: Boolean,
    val known: Map<MetadataField, List<String>>,
    val opaque: Map<String, List<String>> = emptyMap(),
    /**
     * Per-field proof. An empty value map is NOT proof that a parser supports the
     * field or that the tag was physically absent. Legacy test fixtures and
     * fully-observed synthetic snapshots may use known.keys as the conservative default.
     */
    val verifiedFields: Set<MetadataField> = known.keys,
    /** A typed physical observation is optional for old/synthetic snapshots. */
    val fieldObservations: Map<MetadataField, MetadataFieldObservation> = emptyMap(),
)

sealed interface MetadataChange {
    val field: MetadataField

    /** KEEP is expressed by not including an operation for the field. */
    data class Replace(override val field: MetadataField, val values: List<String>) : MetadataChange
    data class Clear(override val field: MetadataField) : MetadataChange
}

data class MetadataFieldDiff(
    val field: MetadataField,
    val before: List<String>,
    val after: List<String>,
)

enum class MetadataBlockReason {
    UNVERIFIED_EMBEDDED_TAGS,
    FIELD_NOT_VERIFIED,
    DUPLICATE_FIELD_OPERATION,
    EMPTY_REPLACEMENT,
    TOO_MANY_VALUES,
    MULTIPLE_VALUES_UNSUPPORTED,
    EMPTY_VALUE,
    NUL_CHARACTER,
    FIELD_TOO_LONG,
    INVALID_FORMAT_SPECIFIC_VALUE,
    TOO_MANY_OPERATIONS,
    EMPTY_BATCH,
    BATCH_TOO_LARGE,
    MIXED_SOURCES,
    DUPLICATE_TARGET,
}

sealed interface MetadataReview {
    data class Blocked(val reason: MetadataBlockReason) : MetadataReview
    data object Unchanged : MetadataReview
    data class Ready(
        val original: MetadataSnapshot,
        val proposed: MetadataSnapshot,
        val diffs: List<MetadataFieldDiff>,
    ) : MetadataReview
}

/** No storage side effects; a valid review is NOT permission to write. */
object MetadataEditPreview {
    private const val MAX_OPERATIONS = 64
    private const val MAX_VALUES = 32
    private const val MAX_BATCH = 128

    fun review(original: MetadataSnapshot, operations: List<MetadataChange>): MetadataReview {
        if (!original.tagsVerified) return MetadataReview.Blocked(MetadataBlockReason.UNVERIFIED_EMBEDDED_TAGS)
        if (operations.size > MAX_OPERATIONS) return MetadataReview.Blocked(MetadataBlockReason.TOO_MANY_OPERATIONS)

        val fields = original.known.mapValues { (_, values) -> values.toList() }.toMutableMap()
        val seen = mutableSetOf<MetadataField>()
        val diffs = mutableListOf<MetadataFieldDiff>()
        for (op in operations) {
            if (!seen.add(op.field)) return MetadataReview.Blocked(MetadataBlockReason.DUPLICATE_FIELD_OPERATION)
            if (op.field !in original.verifiedFields)
                return MetadataReview.Blocked(MetadataBlockReason.FIELD_NOT_VERIFIED)
            val replacement = when (op) {
                is MetadataChange.Clear -> emptyList()
                is MetadataChange.Replace -> {
                    val values = op.values
                    if (values.isEmpty()) return MetadataReview.Blocked(MetadataBlockReason.EMPTY_REPLACEMENT)
                    if (values.size > MAX_VALUES) return MetadataReview.Blocked(MetadataBlockReason.TOO_MANY_VALUES)
                    if (!op.field.multiple && values.size > 1)
                        return MetadataReview.Blocked(MetadataBlockReason.MULTIPLE_VALUES_UNSUPPORTED)
                    if (values.any(String::isBlank)) return MetadataReview.Blocked(MetadataBlockReason.EMPTY_VALUE)
                    if (values.any { '\u0000' in it }) return MetadataReview.Blocked(MetadataBlockReason.NUL_CHARACTER)
                    if (values.any { it.length > op.field.maxChars })
                        return MetadataReview.Blocked(MetadataBlockReason.FIELD_TOO_LONG)
                    if (MetadataFieldValuePolicy.inspect(op.field, values) != null)
                        return MetadataReview.Blocked(MetadataBlockReason.INVALID_FORMAT_SPECIFIC_VALUE)
                    values.toList() // No splitting, normalization, trimming or silent deduplication.
                }
            }
            val before = fields[op.field].orEmpty()
            if (before != replacement) {
                diffs += MetadataFieldDiff(op.field, before.toList(), replacement)
                if (replacement.isEmpty()) fields.remove(op.field) else fields[op.field] = replacement
            }
        }
        if (diffs.isEmpty()) return MetadataReview.Unchanged
        return MetadataReview.Ready(
            original = original,
            proposed = original.copy(
                known = fields.toMap(),
                opaque = original.opaque.mapValues { (_, values) -> values.toList() },
            ),
            diffs = diffs.toList(),
        )
    }

    /** Mixed means the field differs (including present vs absent), not a string to overwrite. */
    sealed interface SelectionValue {
        data object Unverified : SelectionValue
        data object Mixed : SelectionValue
        data class Same(val values: List<String>) : SelectionValue
    }

    fun selectionValue(snapshots: List<MetadataSnapshot>, field: MetadataField): SelectionValue {
        if (snapshots.isEmpty() || snapshots.any { !it.tagsVerified || field !in it.verifiedFields })
            return SelectionValue.Unverified
        val first = snapshots.first().known[field].orEmpty()
        return if (snapshots.all { it.known[field].orEmpty() == first })
            SelectionValue.Same(first.toList()) else SelectionValue.Mixed
    }

    sealed interface BatchReview {
        data class Blocked(val reason: MetadataBlockReason) : BatchReview
        data class Ready(val previews: List<MetadataReview>) : BatchReview
    }

    /** Batch preflight is fail-closed; it never skips a target without telling the caller. */
    fun reviewBatch(snapshots: List<MetadataSnapshot>, changes: List<MetadataChange>): BatchReview {
        if (snapshots.isEmpty()) return BatchReview.Blocked(MetadataBlockReason.EMPTY_BATCH)
        if (snapshots.size > MAX_BATCH) return BatchReview.Blocked(MetadataBlockReason.BATCH_TOO_LARGE)
        if (snapshots.map { it.target.sourceId }.distinct().size != 1)
            return BatchReview.Blocked(MetadataBlockReason.MIXED_SOURCES)
        if (snapshots.map { it.target.trackId }.distinct().size != snapshots.size ||
            snapshots.map { it.target.uri }.distinct().size != snapshots.size)
            return BatchReview.Blocked(MetadataBlockReason.DUPLICATE_TARGET)
        val previews = snapshots.map { review(it, changes) }
        val firstBlock = previews.firstOrNull { it is MetadataReview.Blocked } as? MetadataReview.Blocked
        if (firstBlock != null) return BatchReview.Blocked(firstBlock.reason)
        return BatchReview.Ready(previews)
    }
}
