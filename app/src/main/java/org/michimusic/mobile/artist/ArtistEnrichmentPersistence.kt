package org.michimusic.mobile.artist

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.michimusic.data.artist.ArtistEnrichmentRecord
import org.michimusic.data.artist.ArtistTrackEvidenceRecord
import java.security.MessageDigest
import java.util.Locale

/**
 * The fingerprint represents the currently visible album membership of one credit inside one
 * named library. It is membership evidence, not the durable association identity: a rescan may
 * legitimately add or remove albums. Stored rows keep the association group that was confirmed,
 * and [RoomArtistEnrichmentSelectionStore.load] may recover it only when the overlap is unique.
 * It does NOT claim that equal names identify one musician.
 * ALL-libraries scope is intentionally not persisted: it may combine two unrelated homonyms.
 */
fun scopedArtistCreditKey(libraryId: String?, credit: String, albumIds: List<String>): Pair<String, String>? {
    if (libraryId.isNullOrBlank() || libraryId.trim() == "SCOPE_ALL") return null
    val text = credit.trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank) ?: return null
    if (albumIds.isEmpty() || albumIds.any(String::isBlank)) return null
    val group = albumIds.toSortedSet().joinToString("\u0000")
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(group.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    return text to fingerprint
}

data class PersistedArtistProfile(
    val profile: ArtistExternalProfile,
    val stale: Boolean,
    /**
     * The album group that owns the stored rows. It can differ from the currently visible
     * album fingerprint after a legitimate add/remove/rescan; artwork must use this key
     * instead of treating the changed collection as a new artist.
     */
    val associationGroupKey: String? = null,
    /** True when the row was recovered by unique album overlap and should be confirmed. */
    val needsConfirmation: Boolean = false,
)

/** Small seam for independently testable UI, with no lifecycle-independent coroutine owner. */
interface ArtistEnrichmentSelectionStore {
    suspend fun load(libraryId: String, credit: String, albumIds: List<String>): PersistedArtistProfile?
    suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile)

    /**
     * Persists the profile under the association that already owns it. [associationGroupKey]
     * comes from a unique recovery after an album-set change, so the profile and its artwork
     * keep pointing at the same association instead of splitting across two fingerprints.
     * The default preserves simple test stores; the Room store uses the historical key.
     */
    suspend fun save(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        profile: ArtistExternalProfile,
        associationGroupKey: String?,
    ) = save(libraryId, credit, albumIds, profile)

    suspend fun clear(libraryId: String, credit: String, albumIds: List<String>)

    /** Clears the exact association, including when the current album fingerprint changed. */
    suspend fun clear(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        associationGroupKey: String?,
    ) = clear(libraryId, credit, albumIds)

    /** A fake store may omit evidence recording; the production Room store overrides this. */
    suspend fun saveLocalTagEvidence(libraryId: String, trackId: String, tags: EmbeddedArtistTags) {}
}

class RoomArtistEnrichmentSelectionStore(
    private val database: ArtistEnrichmentCacheDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ArtistEnrichmentSelectionStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val dao get() = database.artistEnrichmentDao()

    override suspend fun load(libraryId: String, credit: String, albumIds: List<String>): PersistedArtistProfile? {
        val (creditKey, groupKey) = scopedArtistCreditKey(libraryId, credit, albumIds) ?: return null
        // canonical row key (albumGroupKey) is immutable; membershipFingerprint is the
        // mutable evidence that maps today's visible albums back to that association.
        val byMembership = dao.getByMembership(libraryId, creditKey, groupKey)
        val exact = dao.get(libraryId, creditKey, groupKey)
        val recovered = if (byMembership == null && exact == null) {
            recoverUniqueAssociation(libraryId, creditKey, albumIds)
        } else {
            null
        }
        val record = byMembership ?: exact ?: recovered ?: return null
        if (canonicalArtistMbid(record.confirmedMbid) != record.confirmedMbid) return null
        val ambiguous = byMembership != null && exact != null &&
            byMembership.albumGroupKey != exact.albumGroupKey
        return try {
            val profile = json.decodeFromString<ArtistExternalProfile>(record.profileJson)
            if (canonicalArtistMbid(profile.mbid) != record.confirmedMbid ||
                (profile.portrait != null && !allowedCommonsPortrait(profile.portrait)) ||
                !allowedArtistBiography(profile.biography, profile.biographySourceUrl)) null
            else PersistedArtistProfile(
                profile = profile,
                stale = clock() >= record.staleAfterMs,
                associationGroupKey = record.albumGroupKey,
                needsConfirmation = recovered != null || ambiguous,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // Corrupt or legacy profile data must never be displayed as verified,
            // but it is a cache failure rather than "this artist has no profile".
            throw IllegalStateException("Perfil de artista corrupto o ilegible", e)
        }
    }

    /**
     * A legitimate rescan can change the album set without changing the artist. Recover
     * only when exactly one stored profile for this library+credit shares at least one
     * album with the visible set. Multiple candidates are ambiguous and stay untouched.
     */
    private suspend fun recoverUniqueAssociation(
        libraryId: String,
        creditKey: String,
        albumIds: List<String>,
    ): ArtistEnrichmentRecord? {
        val visible = albumIds.filter(String::isNotBlank).toSet()
        if (visible.isEmpty()) return null
        val matches = dao.getAllForCredit(libraryId, creditKey).filter { row ->
            decodeAlbumIds(row.albumIdsJson).any(visible::contains)
        }
        return matches.singleOrNull()
    }

    private fun decodeAlbumIds(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun save(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        profile: ArtistExternalProfile,
    ) = save(libraryId, credit, albumIds, profile, associationGroupKey = null)

    override suspend fun save(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        profile: ArtistExternalProfile,
        associationGroupKey: String?,
    ) {
        val (creditKey, currentGroupKey) = requireNotNull(
            scopedArtistCreditKey(libraryId, credit, albumIds),
        ) {
            "No se puede asociar un artista sin biblioteca y grupo local concretos"
        }
        // A unique recovery already proved which stored association owns this artist.
        // Updating the membership evidence must not create a second profile row under
        // the new fingerprint, because artwork remains attached to the historical group.
        val groupKey = associationGroupKey?.takeIf(String::isNotBlank) ?: currentGroupKey
        val id = requireNotNull(canonicalArtistMbid(profile.mbid)) { "MBID inválido" }
        require(id == profile.mbid) { "MBID sin normalizar" }
        require(profile.portrait == null || allowedCommonsPortrait(profile.portrait)) { "Retrato no autorizado" }
        require(allowedArtistBiography(profile.biography, profile.biographySourceUrl)) { "Biografía sin fuente verificable" }
        val now = clock()
        dao.put(ArtistEnrichmentRecord(
            libraryId = libraryId, creditKey = creditKey, albumGroupKey = groupKey,
            confirmedMbid = id, profileJson = json.encodeToString(profile),
            savedAtMs = now, staleAfterMs = now + 7L * 24 * 60 * 60 * 1000,
            source = "musicbrainz-manual-selection", biographySourceUrl = profile.biographySourceUrl,
            portraitSourceUrl = profile.portrait?.commonsPageUrl,
            portraitLicense = profile.portrait?.license,
            portraitLicenseUrl = profile.portrait?.licenseUrl,
            portraitAttribution = profile.portrait?.attribution,
            albumIdsJson = json.encodeToString(albumIds.filter(String::isNotBlank).toSortedSet().toList()),
            membershipFingerprint = currentGroupKey,
        ))
    }

    override suspend fun saveLocalTagEvidence(libraryId: String, trackId: String, tags: EmbeddedArtistTags) {
        require(libraryId.isNotBlank() && libraryId != "SCOPE_ALL")
        require(trackId.startsWith("local_") && trackId.length <= 100) { "Identidad de pista local requerida" }
        val now = clock()
        val observations = listOf(
            "ARTIST_MBID" to tags.artistMbids.sorted(),
            "ALBUM_ARTIST_MBID" to tags.albumArtistMbids.sorted(),
            "ARTISTS" to tags.creditedNames.distinct(),
            "ALBUM_ARTIST" to tags.albumArtists.distinct(),
        ).flatMap { (field, values) ->
            values.take(32).mapIndexed { ordinal, value ->
                ArtistTrackEvidenceRecord(libraryId, trackId, field, ordinal, value.take(180), now)
            }
        }
        // Atomic replacement. Empty scan removes stale evidence for this track ONLY.
        dao.replaceTrackEvidence(libraryId, trackId, observations)
    }

    override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) =
        clear(libraryId, credit, albumIds, associationGroupKey = null)

    override suspend fun clear(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        associationGroupKey: String?,
    ) {
        val (creditKey, currentGroupKey) = scopedArtistCreditKey(libraryId, credit, albumIds) ?: return
        val preferredGroup = associationGroupKey?.takeIf(String::isNotBlank)
        val target = when {
            preferredGroup != null -> preferredGroup
            else -> {
                val exact = dao.get(libraryId, creditKey, currentGroupKey)
                val recovered = exact ?: recoverUniqueAssociation(libraryId, creditKey, albumIds)
                recovered?.albumGroupKey ?: return
            }
        }
        dao.delete(libraryId, creditKey, target)
    }
}

/** Application-context Room owner, intentionally separate from the playback/playlist database. */
object ArtistEnrichmentPersistence {
    @Volatile private var instance: ArtistEnrichmentSelectionStore? = null
    @Volatile private var databaseInstance: ArtistEnrichmentCacheDatabase? = null

    fun database(context: Context): ArtistEnrichmentCacheDatabase =
        databaseInstance ?: synchronized(this) {
            databaseInstance ?: Room.databaseBuilder(
                context.applicationContext,
                ArtistEnrichmentCacheDatabase::class.java,
                "michi-artist-enrichment.db",
            )
                .addMigrations(
                    ArtistEnrichmentCacheDatabase.MIGRATION_1_2,
                    ArtistEnrichmentCacheDatabase.MIGRATION_2_3,
                    ArtistEnrichmentCacheDatabase.MIGRATION_3_4,
                    ArtistEnrichmentCacheDatabase.MIGRATION_4_5,
                    ArtistEnrichmentCacheDatabase.MIGRATION_5_6,
                    ArtistEnrichmentCacheDatabase.MIGRATION_6_7,
                )
                .build().also { databaseInstance = it }
        }

    fun from(context: Context): ArtistEnrichmentSelectionStore = instance ?: synchronized(this) {
        instance ?: RoomArtistEnrichmentSelectionStore(database(context)).also { instance = it }
    }
}
