package org.michimusic.mobile.artist

/** D38: display-only evidence derived from an already-authorized exact membership view.
 * Track keys may contain private URI components; do not serialize, log, or persist them.
 * This policy NEVER confirms identity, releases photos, or grants edit/playback authority.
 */
internal data class ArtistDetailAlbumCreditRow(
    val albumId: String,
    val albumCredit: String,
    val visibleTrackKeys: List<String>,
    val sourceTrackKeys: List<String>?,
)

internal enum class ArtistDetailAlbumCreditKind {
    ALBUM_CREDIT, PARTIAL_ALBUM_CREDIT, GUEST_PARTICIPATION, EMPTY_ALBUM_CREDIT,
}

internal data class ArtistDetailAlbumCreditLabel(
    val kind: ArtistDetailAlbumCreditKind,
    val visibleTracks: Int,
    val albumTracks: Int,
) {
    val displayLabel: String get() = when (kind) {
        ArtistDetailAlbumCreditKind.ALBUM_CREDIT -> "Crédito del álbum"
        ArtistDetailAlbumCreditKind.PARTIAL_ALBUM_CREDIT ->
            "Crédito del álbum · $visibleTracks de $albumTracks canciones"
        ArtistDetailAlbumCreditKind.GUEST_PARTICIPATION ->
            "Participación en canciones · $visibleTracks de $albumTracks"
        ArtistDetailAlbumCreditKind.EMPTY_ALBUM_CREDIT -> "Crédito del álbum · sin pistas registradas"
    }
}

internal data class ArtistDetailAttributionSummary(
    val albumLabels: Map<String, ArtistDetailAlbumCreditLabel>,
    val albumCreditCount: Int,
    val guestParticipationCount: Int,
) {
    val displaySummary: String get() =
        "$albumCreditCount álbumes con crédito principal · " +
            "$guestParticipationCount participaciones en álbumes de otros intérpretes"
}

/** Never infer ownership from album title, similarity, MusicBrainz, or external catalogs. */
internal object ArtistDetailAttributionPolicy {
    private const val MAX_ALBUMS = 512
    private const val MAX_TRACKS = 4096

    fun summarize(
        creditedNames: List<String>,
        albums: List<ArtistDetailAlbumCreditRow>,
    ): ArtistDetailAttributionSummary? {
        if (creditedNames.isEmpty() || creditedNames.size > 64 ||
            creditedNames.any { it.isBlank() || it.trim() != it || it.length > 160 } ||
            creditedNames.distinct().size != creditedNames.size ||
            albums.isEmpty() || albums.size > MAX_ALBUMS ||
            albums.map { it.albumId }.distinct().size != albums.size
        ) return null
        val allowed = creditedNames.toSet()
        val labels = linkedMapOf<String, ArtistDetailAlbumCreditLabel>()
        var totalKeys = 0
        for (row in albums) {
            val source = row.sourceTrackKeys ?: return null
            if (row.albumId.isBlank() || row.albumCredit.isBlank() ||
                row.visibleTrackKeys.size > MAX_TRACKS || source.size > MAX_TRACKS ||
                row.visibleTrackKeys.any(String::isBlank) || source.any(String::isBlank) ||
                row.visibleTrackKeys.size != row.visibleTrackKeys.distinct().size ||
                source.size != source.distinct().size ||
                !source.toSet().containsAll(row.visibleTrackKeys)
            ) return null
            totalKeys += source.size
            if (totalKeys > MAX_TRACKS) return null
            val albumCreditMatch = row.albumCredit.trim() in allowed
            val kind = when {
                source.isEmpty() && row.visibleTrackKeys.isEmpty() && albumCreditMatch ->
                    ArtistDetailAlbumCreditKind.EMPTY_ALBUM_CREDIT
                row.visibleTrackKeys.isEmpty() -> return null
                albumCreditMatch && row.visibleTrackKeys.size == source.size ->
                    ArtistDetailAlbumCreditKind.ALBUM_CREDIT
                albumCreditMatch -> ArtistDetailAlbumCreditKind.PARTIAL_ALBUM_CREDIT
                else -> ArtistDetailAlbumCreditKind.GUEST_PARTICIPATION
            }
            labels[row.albumId] = ArtistDetailAlbumCreditLabel(
                kind, row.visibleTrackKeys.size, source.size,
            )
        }
        return ArtistDetailAttributionSummary(
            labels,
            albumCreditCount = labels.values.count { it.kind != ArtistDetailAlbumCreditKind.GUEST_PARTICIPATION },
            guestParticipationCount = labels.values.count { it.kind == ArtistDetailAlbumCreditKind.GUEST_PARTICIPATION },
        )
    }
}
