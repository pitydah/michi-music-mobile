package org.michimusic.core.metadata

/**
 * ME-11G: a full verified tag list is not a CachedTrack scalar. A reconciler
 * must never flatten Artist/Genre/AlbumArtist or replace a date with a guessed
 * year. Instead it returns a precise on-demand reindex request for the UI.
 * No background work, Room writes or source commits occur here.
 */
object MetadataScalarIndexRefreshPolicy {
    private val scalarText = setOf(MetadataField.TITLE, MetadataField.ARTIST,
        MetadataField.ALBUM, MetadataField.ALBUM_ARTIST, MetadataField.GENRE)
    private val scalarNumber = setOf(MetadataField.TRACK_NUMBER)
    private val indexed = scalarText + scalarNumber + MetadataField.RELEASE_DATE
    enum class Reason { INVALID_SOURCE, AMBIGUOUS_MULTIVALUE, INVALID_SCALAR_NUMBER,
        PARTIAL_OR_UNSUPPORTED_DATE, UNVERIFIABLE_INDEX_FIELD }
    sealed interface Verdict {
        data object MayTryRoomCas : Verdict
        data class SelectiveRefreshRequired(
            val sourceId: String, val trackId: String, val uri: String,
            val fields: Set<MetadataField>, val reason: Reason,
            /** Never a certification that a writer changed the source. */
            val canPublishSaved: Boolean = false,
        ): Verdict
    }

    fun evaluate(delta: MetadataSourceIndexDelta.Delta): Verdict {
        val value=delta.observedValues
        val problems=linkedSetOf<MetadataField>()
        var reason:Reason?=null
        if(delta.target.trackId.isBlank() || !delta.target.uri.startsWith("content://") ||
            delta.sourceSha256.length!=64 || delta.candidateBytes<=0L)
            return Verdict.SelectiveRefreshRequired(delta.target.sourceId,delta.target.trackId,
                delta.target.uri,indexed,Reason.INVALID_SOURCE)
        val unknown=delta.untouchedUnknown.intersect(indexed)
        if(unknown.isNotEmpty()) {problems+=unknown;reason=Reason.UNVERIFIABLE_INDEX_FIELD}
        for(field in scalarText){
            if(value[field].orEmpty().size>1) {
                problems+=field
                if(reason==null)reason=Reason.AMBIGUOUS_MULTIVALUE
            }
        }
        for(field in scalarNumber){
            val values=value[field].orEmpty()
            if(values.size>1 || values.singleOrNull()?.let { number ->
                number.length !in 1..4 || !number.all(Char::isDigit) ||
                    (number.toIntOrNull() ?: 0) !in 1..9999
            }==true){
                problems+=field
                if(reason==null)reason=Reason.INVALID_SCALAR_NUMBER
            }
        }
        val date=value[MetadataField.RELEASE_DATE].orEmpty()
        if(date.size>1 || (date.size==1 && !date[0].matches(
                Regex("[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?")))) {
            problems+=MetadataField.RELEASE_DATE
            if(reason==null)reason=Reason.PARTIAL_OR_UNSUPPORTED_DATE
        }
        return if(problems.isEmpty())Verdict.MayTryRoomCas else Verdict.SelectiveRefreshRequired(
            delta.target.sourceId,delta.target.trackId,delta.target.uri,problems,reason!!)
    }
}
