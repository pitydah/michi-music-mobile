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
 * The key represents one observed credit within one named library and its currently visible
 * album grouping. It does NOT claim that equal names identify one musician. If albums change,
 * the cached association fails closed and user selection is required again.
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

data class PersistedArtistProfile(val profile: ArtistExternalProfile, val stale: Boolean)

/** Small seam for independently testable UI, with no lifecycle-independent coroutine owner. */
interface ArtistEnrichmentSelectionStore {
    suspend fun load(libraryId: String, credit: String, albumIds: List<String>): PersistedArtistProfile?
    suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile)
    suspend fun clear(libraryId: String, credit: String, albumIds: List<String>)
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
        val record = dao.get(libraryId, creditKey, groupKey) ?: return null
        if (canonicalArtistMbid(record.confirmedMbid) != record.confirmedMbid) return null
        return try {
            val profile = json.decodeFromString<ArtistExternalProfile>(record.profileJson)
            if (canonicalArtistMbid(profile.mbid) != record.confirmedMbid ||
                (profile.portrait != null && !allowedCommonsPortrait(profile.portrait)) ||
                !allowedArtistBiography(profile.biography, profile.biographySourceUrl)) null
            else PersistedArtistProfile(profile, stale = clock() >= record.staleAfterMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // Corrupt or legacy profile data must never be displayed as verified,
            // but it is a cache failure rather than "this artist has no profile".
            throw IllegalStateException("Perfil de artista corrupto o ilegible", e)
        }
    }

    override suspend fun save(libraryId: String, credit: String, albumIds: List<String>, profile: ArtistExternalProfile) {
        val (creditKey, groupKey) = requireNotNull(scopedArtistCreditKey(libraryId, credit, albumIds)) {
            "No se puede asociar un artista sin biblioteca y grupo local concretos"
        }
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

    override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) {
        val (creditKey, groupKey) = scopedArtistCreditKey(libraryId, credit, albumIds) ?: return
        dao.delete(libraryId, creditKey, groupKey)
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
                )
                .build().also { databaseInstance = it }
        }

    fun from(context: Context): ArtistEnrichmentSelectionStore = instance ?: synchronized(this) {
        instance ?: RoomArtistEnrichmentSelectionStore(database(context)).also { instance = it }
    }
}
