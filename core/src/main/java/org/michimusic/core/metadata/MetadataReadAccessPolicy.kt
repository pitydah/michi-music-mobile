package org.michimusic.core.metadata

/** ME-05: fail-closed local-provider allowlist; a cloud SAF URI is NOT local audio. */
object MetadataReadAccessPolicy {
    enum class Kind { MEDIASTORE, SAF_LOCAL, REJECT }
    private const val MEDIA = "media"
    private val localSaf = setOf(
        "com.android.externalstorage.documents",
        "com.android.providers.media.documents",
        "com.android.providers.downloads.documents",
    )
    fun classify(sourceId: String, scheme: String?, authority: String?): Kind = when {
        scheme != "content" -> Kind.REJECT
        sourceId.isEmpty() && authority == MEDIA -> Kind.MEDIASTORE
        sourceId.isNotEmpty() && authority in localSaf -> Kind.SAF_LOCAL
        else -> Kind.REJECT
    }
}
