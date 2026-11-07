package org.michimusic.mobile.artist

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.michimusic.data.artist.ArtistEnrichmentRecord
import org.michimusic.data.artist.ArtistAssociationResolutionRecord
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
    /** False only for an identity-first row whose external metadata was never committed. */
    val profilePersisted: Boolean = true,
)

/** Multiple durable associations fit the same visible membership; none may win by row order. */
class ArtistAssociationAmbiguousException : IllegalStateException(
    "Asociación artística ambigua; se requiere resolución explícita.",
)

data class ArtistAssociationCandidate(
    val associationGroupKey: String,
    val confirmedMbid: String,
    val displayName: String,
    val albumIds: List<String>,
    val source: String,
    val savedAtMs: Long,
)

/** Explicit ambiguity resolution is optional for simple fakes and mandatory for Room. */
interface ArtistAssociationResolutionStore {
    suspend fun ambiguousAssociations(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
    ): List<ArtistAssociationCandidate>

    suspend fun resolveAssociation(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        chosenAssociationGroupKey: String,
        chosenMbid: String,
    ): PersistedArtistProfile
}

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

/** Optional production seam for an atomic identity-first Room commit. */
interface ArtistIdentitySelectionStore {
    suspend fun saveIdentity(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        mbid: String,
        associationGroupKey: String? = null,
    ): PersistedArtistProfile
}

/**
 * Commits an explicit MBID before optional provider work. Simple test stores keep using
 * their existing save contract; Room implements [ArtistIdentitySelectionStore] atomically.
 */
suspend fun ArtistEnrichmentSelectionStore.saveIdentityFirst(
    libraryId: String,
    credit: String,
    albumIds: List<String>,
    mbid: String,
    associationGroupKey: String? = null,
): PersistedArtistProfile {
    if (this is ArtistIdentitySelectionStore) {
        return saveIdentity(libraryId, credit, albumIds, mbid, associationGroupKey)
    }
    val profile = ArtistExternalProfile(mbid = mbid, name = credit.trim())
    save(libraryId, credit, albumIds, profile, associationGroupKey)
    return PersistedArtistProfile(
        profile = profile,
        stale = false,
        associationGroupKey = associationGroupKey
            ?: scopedArtistCreditKey(libraryId, credit, albumIds)?.second,
    )
}

class RoomArtistEnrichmentSelectionStore(
    private val database: ArtistEnrichmentCacheDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ArtistEnrichmentSelectionStore, ArtistIdentitySelectionStore, ArtistAssociationResolutionStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val dao get() = database.artistEnrichmentDao()

    override suspend fun saveIdentity(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        mbid: String,
        associationGroupKey: String?,
    ): PersistedArtistProfile = database.withTransaction {
        val (creditKey, currentGroupKey) = requireNotNull(
            scopedArtistCreditKey(libraryId, credit, albumIds),
        ) { "No se puede asociar un artista sin biblioteca y grupo local concretos" }
        val canonicalMbid = requireNotNull(canonicalArtistMbid(mbid)) { "MBID inválido" }
        require(canonicalMbid == mbid) { "MBID sin normalizar" }
        val groupKey = associationGroupKey?.takeIf(String::isNotBlank) ?: currentGroupKey
        val existing = existingAssociationForWrite(
            libraryId, creditKey, currentGroupKey, groupKey, canonicalMbid,
        )

        val now = clock()
        val profile = existing?.let { record ->
            try {
                json.decodeFromString<ArtistExternalProfile>(record.profileJson)
                    .takeIf { canonicalArtistMbid(it.mbid) == canonicalMbid }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } ?: ArtistExternalProfile(mbid = canonicalMbid, name = credit.trim())
        val record = existing?.copy(
            membershipFingerprint = currentGroupKey,
            albumIdsJson = json.encodeToString(
                albumIds.filter(String::isNotBlank).toSortedSet().toList(),
            ),
        ) ?: ArtistEnrichmentRecord(
            libraryId = libraryId,
            creditKey = creditKey,
            albumGroupKey = groupKey,
            confirmedMbid = canonicalMbid,
            profileJson = json.encodeToString(profile),
            savedAtMs = now,
            staleAfterMs = Long.MAX_VALUE,
            source = "musicbrainz-manual-selection-pending",
            biographySourceUrl = null,
            portraitSourceUrl = null,
            portraitLicense = null,
            portraitLicenseUrl = null,
            portraitAttribution = null,
            albumIdsJson = json.encodeToString(
                albumIds.filter(String::isNotBlank).toSortedSet().toList(),
            ),
            membershipFingerprint = currentGroupKey,
        )
        dao.put(record)
        PersistedArtistProfile(
            profile = profile,
            stale = existing?.let { now >= it.staleAfterMs } ?: false,
            associationGroupKey = record.albumGroupKey,
            profilePersisted = record.source != "musicbrainz-manual-selection-pending",
        )
    }

    override suspend fun load(libraryId: String, credit: String, albumIds: List<String>): PersistedArtistProfile? {
        val (creditKey, groupKey) = scopedArtistCreditKey(libraryId, credit, albumIds) ?: return null
        val explicit = resolvedAssociationForMembership(libraryId, creditKey, groupKey, albumIds)
        if (explicit != null) return decodePersisted(explicit, recovered = false)
        // canonical row key (albumGroupKey) is immutable; membershipFingerprint is the
        // mutable evidence that maps today's visible albums back to that association.
        val byMembership = dao.getAllByMembership(libraryId, creditKey, groupKey)
        val exact = dao.get(libraryId, creditKey, groupKey)
        val directMatches = (byMembership + listOfNotNull(exact))
            .distinctBy { it.albumGroupKey }
        if (directMatches.size > 1) throw ArtistAssociationAmbiguousException()
        val recovered = if (directMatches.isEmpty()) {
            recoverAssociation(libraryId, creditKey, albumIds)
        } else {
            null
        }
        val record = directMatches.singleOrNull() ?: recovered ?: return null
        if (canonicalArtistMbid(record.confirmedMbid) != record.confirmedMbid) return null
        return decodePersisted(record, recovered = recovered != null)
    }

    private fun decodePersisted(record: ArtistEnrichmentRecord, recovered: Boolean): PersistedArtistProfile? = try {
        val profile = json.decodeFromString<ArtistExternalProfile>(record.profileJson)
        if (canonicalArtistMbid(profile.mbid) != record.confirmedMbid ||
            (profile.portrait != null && !allowedCommonsPortrait(profile.portrait)) ||
            !allowedArtistBiography(profile.biography, profile.biographySourceUrl)) null
        else PersistedArtistProfile(
            profile = profile,
            stale = clock() >= record.staleAfterMs,
            associationGroupKey = record.albumGroupKey,
            needsConfirmation = recovered,
            profilePersisted = record.source != "musicbrainz-manual-selection-pending",
        )
    } catch (cancelled: CancellationException) {
            throw cancelled
    } catch (e: Exception) {
        // Corrupt or legacy profile data must never be displayed as verified,
        // but it is a cache failure rather than "this artist has no profile".
        throw IllegalStateException("Perfil de artista corrupto o ilegible", e)
    }

    /**
     * Exact membership decisions win first. When albums are subsequently added or removed,
     * a unique prior decision may carry forward only while its chosen historical row still
     * overlaps the visible membership. Disjoint homonyms therefore never inherit a choice.
     */
    private suspend fun resolvedAssociationForMembership(
        libraryId: String,
        creditKey: String,
        membershipFingerprint: String,
        albumIds: List<String>,
    ): ArtistEnrichmentRecord? {
        val exact = dao.getAssociationResolution(libraryId, creditKey, membershipFingerprint)
        if (exact != null) {
            return dao.get(libraryId, creditKey, exact.chosenAlbumGroupKey)
                ?.takeIf { it.confirmedMbid == exact.chosenMbid }
        }
        val visible = albumIds.filter(String::isNotBlank).toSet()
        if (visible.isEmpty()) return null
        return dao.getAssociationResolutionsForCredit(libraryId, creditKey)
            .mapNotNull { resolution ->
                dao.get(libraryId, creditKey, resolution.chosenAlbumGroupKey)
                    ?.takeIf { row ->
                        row.confirmedMbid == resolution.chosenMbid &&
                            decodeAlbumIds(row.albumIdsJson).any(visible::contains)
                    }
            }
            .distinctBy { it.albumGroupKey to it.confirmedMbid }
            .singleOrNull()
    }

    override suspend fun ambiguousAssociations(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
    ): List<ArtistAssociationCandidate> {
        val (creditKey, groupKey) = scopedArtistCreditKey(libraryId, credit, albumIds) ?: return emptyList()
        return relevantAssociations(libraryId, creditKey, groupKey, albumIds)
            .takeIf { it.size > 1 }
            .orEmpty()
            .mapNotNull { record ->
                val profile = decodePersisted(record, recovered = false)?.profile ?: return@mapNotNull null
                ArtistAssociationCandidate(
                    associationGroupKey = record.albumGroupKey,
                    confirmedMbid = record.confirmedMbid,
                    displayName = profile.name,
                    albumIds = decodeAlbumIds(record.albumIdsJson),
                    source = record.source,
                    savedAtMs = record.savedAtMs,
                )
            }
    }

    override suspend fun resolveAssociation(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        chosenAssociationGroupKey: String,
        chosenMbid: String,
    ): PersistedArtistProfile = database.withTransaction {
        val (creditKey, groupKey) = requireNotNull(scopedArtistCreditKey(libraryId, credit, albumIds)) {
            "La resolución requiere una biblioteca y crédito locales concretos"
        }
        val canonical = requireNotNull(canonicalArtistMbid(chosenMbid)) { "MBID inválido" }
        require(canonical == chosenMbid) { "MBID sin normalizar" }
        val candidates = relevantAssociations(libraryId, creditKey, groupKey, albumIds)
        require(candidates.size > 1) { "La ambigüedad ya no existe; vuelve a cargar las alternativas." }
        val selected = candidates.singleOrNull {
            it.albumGroupKey == chosenAssociationGroupKey && it.confirmedMbid == canonical
        } ?: throw IllegalStateException("La asociación elegida ya no existe o cambió de identidad.")
        val selectedForCurrentMembership = selected.copy(
            membershipFingerprint = groupKey,
            albumIdsJson = json.encodeToString(
                albumIds.filter(String::isNotBlank).toSortedSet().toList(),
            ),
        )
        dao.put(selectedForCurrentMembership)
        dao.putAssociationResolution(
            ArtistAssociationResolutionRecord(
                libraryId = libraryId,
                creditKey = creditKey,
                membershipFingerprint = groupKey,
                chosenAlbumGroupKey = selected.albumGroupKey,
                chosenMbid = selected.confirmedMbid,
                evidence = "USER_EXPLICIT_CHOICE",
                confirmedAtMs = clock(),
            ),
        )
        requireNotNull(decodePersisted(selectedForCurrentMembership, recovered = false)) {
            "La asociación elegida contiene un perfil inválido"
        }
    }

    private suspend fun relevantAssociations(
        libraryId: String,
        creditKey: String,
        currentGroupKey: String,
        albumIds: List<String>,
    ): List<ArtistEnrichmentRecord> {
        val direct = (
            dao.getAllByMembership(libraryId, creditKey, currentGroupKey) +
                listOfNotNull(dao.get(libraryId, creditKey, currentGroupKey))
            ).distinctBy { it.albumGroupKey }
        if (direct.isNotEmpty()) return direct
        val visible = albumIds.filter(String::isNotBlank).toSet()
        return dao.getAllForCredit(libraryId, creditKey).filter { row ->
            decodeAlbumIds(row.albumIdsJson).any(visible::contains)
        }
    }

    /**
     * A legitimate rescan can change the album set without changing the artist. Recover
     * only when exactly one stored profile for this library+credit shares at least one
     * album with the visible set. Multiple candidates are ambiguous and stay untouched.
     */
    private suspend fun recoverAssociation(
        libraryId: String,
        creditKey: String,
        albumIds: List<String>,
    ): ArtistEnrichmentRecord? {
        val visible = albumIds.filter(String::isNotBlank).toSet()
        if (visible.isEmpty()) return null
        val matches = dao.getAllForCredit(libraryId, creditKey).filter { row ->
            decodeAlbumIds(row.albumIdsJson).any(visible::contains)
        }
        if (matches.size > 1) throw ArtistAssociationAmbiguousException()
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

    /**
     * Resolves the one row that may be mutated while the caller holds Room's transaction.
     * A durable user choice narrows otherwise ambiguous membership rows; without one,
     * every conflict still fails closed.
     */
    private suspend fun existingAssociationForWrite(
        libraryId: String,
        creditKey: String,
        currentGroupKey: String,
        requestedGroupKey: String,
        requestedMbid: String,
    ): ArtistEnrichmentRecord? {
        val resolution = dao.getAssociationResolution(libraryId, creditKey, currentGroupKey)
        if (resolution != null) {
            if (resolution.chosenAlbumGroupKey != requestedGroupKey ||
                resolution.chosenMbid != requestedMbid
            ) {
                throw IllegalStateException(
                    "La actualización no coincide con la asociación elegida explícitamente.",
                )
            }
            return dao.get(libraryId, creditKey, requestedGroupKey)
                ?.takeIf { it.confirmedMbid == requestedMbid }
                ?: throw IllegalStateException("La asociación elegida ya no existe o cambió de identidad.")
        }
        val membershipMatches = dao.getAllByMembership(libraryId, creditKey, currentGroupKey)
        val exact = dao.get(libraryId, creditKey, requestedGroupKey)
        val directMatches = (membershipMatches + listOfNotNull(exact))
            .distinctBy { it.albumGroupKey }
        if (directMatches.size > 1) throw ArtistAssociationAmbiguousException()
        val existing = directMatches.singleOrNull()
        if (existing != null && existing.albumGroupKey != requestedGroupKey) {
            throw IllegalStateException("La pertenencia actual ya apunta a otra asociación histórica.")
        }
        if (existing != null && existing.confirmedMbid != requestedMbid) {
            throw IllegalStateException(
                "La asociación ya pertenece a otro MBID; desvincúlala antes de sustituirla.",
            )
        }
        return existing
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
    ) = database.withTransaction {
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
        existingAssociationForWrite(
            libraryId, creditKey, currentGroupKey, groupKey, id,
        )
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
    ) = database.withTransaction {
        val (creditKey, currentGroupKey) = scopedArtistCreditKey(libraryId, credit, albumIds)
            ?: return@withTransaction
        val preferredGroup = associationGroupKey?.takeIf(String::isNotBlank)
        val target = when {
            preferredGroup != null -> preferredGroup
            else -> {
                val exact = dao.get(libraryId, creditKey, currentGroupKey)
                val recovered = exact ?: recoverAssociation(libraryId, creditKey, albumIds)
                recovered?.albumGroupKey ?: return@withTransaction
            }
        }
        dao.delete(libraryId, creditKey, target)
        // No stale decision may resurrect a group after an explicit unlink. Historical
        // association rows for other groups remain untouched.
        dao.deleteAssociationResolutionsForCredit(libraryId, creditKey)
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
                    ArtistEnrichmentCacheDatabase.MIGRATION_7_8,
                )
                .build().also { databaseInstance = it }
        }

    fun from(context: Context): ArtistEnrichmentSelectionStore = instance ?: synchronized(this) {
        instance ?: RoomArtistEnrichmentSelectionStore(database(context)).also { instance = it }
    }
}
