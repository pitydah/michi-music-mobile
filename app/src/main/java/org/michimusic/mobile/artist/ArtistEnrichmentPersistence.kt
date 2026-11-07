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
import org.michimusic.data.artist.ArtistAlbumCreditBindingRecord
import org.michimusic.data.artist.ArtistAssociationResolutionRecord
import org.michimusic.data.artist.ArtistMembershipEvidenceRecord
import org.michimusic.data.artist.ArtistTrackEvidenceRecord
import org.michimusic.data.artist.DurableArtistAssociationRecord
import java.security.MessageDigest
import java.util.Locale

/**
 * The fingerprint names one exact visible album context for a credit inside one named library.
 * It can seed a new immutable association ID and key an exact-context decision, but it is never
 * membership authority. [RoomArtistEnrichmentSelectionStore.load] resolves identity only through
 * durable album-credit bindings or an exact explicit resolution.
 * It does NOT claim that equal names identify one musician.
 * ALL-libraries scope is intentionally not persisted: it may combine two unrelated homonyms.
 */
fun scopedArtistCreditKey(libraryId: String?, credit: String, albumIds: List<String>): Pair<String, String>? {
    if (libraryId.isNullOrBlank() || libraryId.trim() == "SCOPE_ALL") return null
    val text = credit.trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank) ?: return null
    if (albumIds.isEmpty() || albumIds.any(String::isBlank)) return null
    val fingerprint = albumMembershipFingerprint(albumIds) ?: return null
    return text to fingerprint
}

private fun albumMembershipFingerprint(albumIds: Collection<String>): String? {
    if (albumIds.isEmpty() || albumIds.any(String::isBlank)) return null
    val group = albumIds.toSortedSet().joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(group.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

data class PersistedArtistProfile(
    val profile: ArtistExternalProfile,
    val stale: Boolean,
    /**
     * Legacy API name for the stable durable association ID. It can differ from the current
     * context fingerprint after add/remove/rescan; artwork must use this ID rather than the view.
     */
    val associationGroupKey: String? = null,
    /** True when some visible album-credit rows are not yet bound to the recovered association. */
    val needsConfirmation: Boolean = false,
    /** False only for an identity-first row whose external metadata was never committed. */
    val profilePersisted: Boolean = true,
    /** Album-credit rows without binding, never inferred from a name or an exact resolution. */
    val unboundAlbumIds: List<String> = emptyList(),
)

/** Visible album-credit bindings point to multiple associations; none may win by row order. */
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

enum class ArtistMembershipEvidenceType {
    USER_EXPLICIT_IDENTITY_CONFIRMATION,
    USER_EXPLICIT_BINDING,
    EMBEDDED_ARTIST_MBID,
    EMBEDDED_ALBUM_ARTIST_MBID,
    STABLE_TRACK_IDENTITY,
    USER_EXPLICIT_REPLACEMENT,
    LEGACY_EXPLICIT_CONTEXT,
}

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

    /** Compatibility seam for simple stores. Production profile writes use [ArtistProfileRefreshStore]. */
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

/** Semantic authority for explicitly attaching observed album-credit rows to a known identity. */
interface ArtistMembershipBindingStore {
    suspend fun bindAlbumsToAssociation(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        associationId: String,
        expectedMbid: String,
        evidenceType: ArtistMembershipEvidenceType = ArtistMembershipEvidenceType.USER_EXPLICIT_BINDING,
    ): PersistedArtistProfile

    /** Explicitly claim only unbound rows in a mixed resolved view; never steal other artists. */
    suspend fun bindUnboundAlbumsToAssociation(
        libraryId: String,
        credit: String,
        visibleAlbumIds: List<String>,
        associationId: String,
        expectedMbid: String,
    ): PersistedArtistProfile
}

/** Explicitly replaces a known wrong album-credit binding without replacing either identity. */
interface ArtistMembershipReplacementStore {
    suspend fun replaceAlbumBindings(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        expectedCurrentAssociationId: String,
        replacementAssociationId: String,
        replacementMbid: String,
    ): PersistedArtistProfile
}

/** Semantic authority for changing provider facts without changing identity or membership. */
interface ArtistProfileRefreshStore {
    suspend fun refreshProfile(
        libraryId: String,
        credit: String,
        associationId: String,
        profile: ArtistExternalProfile,
    )
}

suspend fun ArtistEnrichmentSelectionStore.refreshPersistedProfile(
    libraryId: String,
    credit: String,
    albumIds: List<String>,
    associationId: String,
    profile: ArtistExternalProfile,
) {
    if (this is ArtistProfileRefreshStore) {
        refreshProfile(libraryId, credit, associationId, profile)
    } else {
        save(libraryId, credit, albumIds, profile, associationId)
    }
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
) : ArtistEnrichmentSelectionStore,
    ArtistIdentitySelectionStore,
    ArtistMembershipBindingStore,
    ArtistMembershipReplacementStore,
    ArtistProfileRefreshStore,
    ArtistAssociationResolutionStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val dao get() = database.artistEnrichmentDao()

    suspend fun confirmNewIdentity(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        mbid: String,
    ): PersistedArtistProfile = database.withTransaction {
        val (creditKey, associationId) = requireNotNull(
            scopedArtistCreditKey(libraryId, credit, albumIds),
        ) { "No se puede confirmar una identidad sin biblioteca y álbumes concretos" }
        val canonicalMbid = requireNotNull(canonicalArtistMbid(mbid)) { "MBID inválido" }
        require(canonicalMbid == mbid) { "MBID sin normalizar" }
        val visibleAlbums = albumIds.filter(String::isNotBlank).distinct().sorted()
        val existingBindings = dao.getAlbumCreditBindings(libraryId, creditKey, visibleAlbums)
        val conflicting = existingBindings.firstOrNull { binding ->
            val identity = dao.get(libraryId, creditKey, binding.associationId)
            identity == null || identity.confirmedMbid != canonicalMbid || binding.associationId != associationId
        }
        require(conflicting == null) {
            "Un álbum ya pertenece a otra asociación; usa sustitución explícita."
        }
        val existing = dao.get(libraryId, creditKey, associationId)
        require(existing == null || existing.confirmedMbid == canonicalMbid) {
            "La asociación ya pertenece a otro MBID; usa sustitución explícita."
        }
        val now = clock()
        val profile = existing?.let(::decodeIdentityProfile)
            ?: ArtistExternalProfile(mbid = canonicalMbid, name = credit.trim())
        val identity = existing ?: ArtistEnrichmentRecord(
            libraryId = libraryId,
            creditKey = creditKey,
            albumGroupKey = associationId,
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
            albumIdsJson = json.encodeToString(visibleAlbums),
            membershipFingerprint = associationId,
        )
        dao.put(identity)
        dao.putDurableAssociation(
            DurableArtistAssociationRecord(
                libraryId = libraryId,
                creditKey = creditKey,
                associationId = associationId,
                identityAssociationId = associationId,
                createdAtMs = existing?.savedAtMs ?: now,
                updatedAtMs = now,
            ),
        )
        bindAlbumsLocked(
            libraryId = libraryId,
            creditKey = creditKey,
            albumIds = visibleAlbums,
            associationId = associationId,
            expectedMbid = canonicalMbid,
            evidenceType = ArtistMembershipEvidenceType.USER_EXPLICIT_IDENTITY_CONFIRMATION,
            now = now,
        )
        decodePersisted(identity, recovered = false)
            ?: error("La identidad confirmada contiene un perfil inválido")
    }

    override suspend fun bindAlbumsToAssociation(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        associationId: String,
        expectedMbid: String,
        evidenceType: ArtistMembershipEvidenceType,
    ): PersistedArtistProfile = database.withTransaction {
        val (creditKey, _) = requireNotNull(scopedArtistCreditKey(libraryId, credit, albumIds)) {
            "La vinculación requiere una biblioteca y álbumes concretos"
        }
        val canonicalMbid = requireNotNull(canonicalArtistMbid(expectedMbid)) { "MBID inválido" }
        require(canonicalMbid == expectedMbid)
        val identity = dao.get(libraryId, creditKey, associationId)
            ?: error("La asociación solicitada no existe")
        require(identity.confirmedMbid == canonicalMbid) {
            "La asociación cambió de identidad; vuelve a cargarla."
        }
        require(evidenceType != ArtistMembershipEvidenceType.LEGACY_EXPLICIT_CONTEXT) {
            "La evidencia legacy sólo puede crearla una migración."
        }
        bindAlbumsLocked(
            libraryId = libraryId,
            creditKey = creditKey,
            albumIds = albumIds.filter(String::isNotBlank).distinct().sorted(),
            associationId = associationId,
            expectedMbid = canonicalMbid,
            evidenceType = evidenceType,
            now = clock(),
        )
        requireNotNull(decodePersisted(identity, recovered = false))
    }

    override suspend fun bindUnboundAlbumsToAssociation(
        libraryId: String,
        credit: String,
        visibleAlbumIds: List<String>,
        associationId: String,
        expectedMbid: String,
    ): PersistedArtistProfile = database.withTransaction {
        val (creditKey, _) = requireNotNull(scopedArtistCreditKey(libraryId, credit, visibleAlbumIds)) {
            "La vinculación requiere una biblioteca y álbumes concretos"
        }
        val mbid = requireNotNull(canonicalArtistMbid(expectedMbid)) { "MBID inválido" }
        require(mbid == expectedMbid) { "MBID sin normalizar" }
        val association = dao.getDurableAssociation(libraryId, creditKey, associationId)
            ?: error("La asociación durable no existe")
        val identity = dao.get(libraryId, creditKey, association.identityAssociationId)
            ?: error("La asociación perdió su identidad")
        require(identity.confirmedMbid == mbid) { "La asociación pertenece a otro MBID" }
        val visible = visibleAlbumIds.filter(String::isNotBlank).distinct().sorted()
        require(visible.isNotEmpty()) { "No hay álbumes locales válidos para confirmar." }
        val alreadyBound = dao.getAlbumCreditBindings(libraryId, creditKey, visible)
            .map { it.albumKey }.toSet()
        val unbound = visible.filterNot(alreadyBound::contains)
        require(unbound.isNotEmpty()) { "No hay álbumes sin vinculación en esta vista." }
        bindAlbumsLocked(
            libraryId = libraryId,
            creditKey = creditKey,
            albumIds = unbound,
            associationId = associationId,
            expectedMbid = mbid,
            evidenceType = ArtistMembershipEvidenceType.USER_EXPLICIT_BINDING,
            now = clock(),
        )
        requireNotNull(decodePersisted(identity, recovered = false))
    }

    private suspend fun bindAlbumsLocked(
        libraryId: String,
        creditKey: String,
        albumIds: List<String>,
        associationId: String,
        expectedMbid: String,
        evidenceType: ArtistMembershipEvidenceType,
        now: Long,
    ) {
        val association = dao.getDurableAssociation(libraryId, creditKey, associationId)
            ?: error("La asociación durable no existe")
        val identity = dao.get(libraryId, creditKey, association.identityAssociationId)
            ?: error("La asociación perdió su identidad")
        require(identity.confirmedMbid == expectedMbid) { "La asociación pertenece a otro MBID" }
        albumIds.forEach { albumId ->
            val current = dao.getAlbumCreditBinding(libraryId, albumId, creditKey)
            require(current == null || current.associationId == associationId) {
                "El álbum ya pertenece a otra asociación; usa sustitución explícita."
            }
            dao.putAlbumCreditBinding(
                ArtistAlbumCreditBindingRecord(
                    libraryId = libraryId,
                    albumKey = albumId,
                    creditKey = creditKey,
                    associationId = associationId,
                    evidenceType = evidenceType.name,
                    confirmedAtMs = current?.confirmedAtMs ?: now,
                ),
            )
            dao.putMembershipEvidence(
                ArtistMembershipEvidenceRecord(
                    libraryId = libraryId,
                    albumKey = albumId,
                    creditKey = creditKey,
                    associationId = associationId,
                    evidenceType = evidenceType.name,
                    evidenceKey = albumId,
                    status = "CONFIRMED",
                    source = evidenceType.name,
                    observedAtMs = now,
                    confirmedAtMs = now,
                ),
            )
        }
    }

    override suspend fun replaceAlbumBindings(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        expectedCurrentAssociationId: String,
        replacementAssociationId: String,
        replacementMbid: String,
    ): PersistedArtistProfile = database.withTransaction {
        val visibleAlbums = albumIds.filter(String::isNotBlank).distinct().sorted()
        require(visibleAlbums.isNotEmpty()) { "La sustitución requiere álbumes concretos." }
        val creditKey = normalizedCreditKey(credit)
        val canonicalMbid = requireNotNull(canonicalArtistMbid(replacementMbid)) { "MBID inválido" }
        require(canonicalMbid == replacementMbid) { "MBID sin normalizar" }
        val replacement = dao.getDurableAssociation(
            libraryId, creditKey, replacementAssociationId,
        ) ?: error("La asociación de reemplazo no existe.")
        val identity = dao.get(libraryId, creditKey, replacement.identityAssociationId)
            ?: error("La asociación de reemplazo perdió su identidad.")
        require(identity.confirmedMbid == canonicalMbid) {
            "La asociación de reemplazo pertenece a otro MBID."
        }
        visibleAlbums.forEach { albumId ->
            val current = dao.getAlbumCreditBinding(libraryId, albumId, creditKey)
                ?: error("El álbum no tiene una vinculación que sustituir.")
            require(current.associationId == expectedCurrentAssociationId) {
                "La vinculación cambió; vuelve a cargar antes de sustituirla."
            }
        }
        val now = clock()
        visibleAlbums.forEach { albumId ->
            dao.supersedeConfirmedMembershipEvidence(
                libraryId, albumId, creditKey, expectedCurrentAssociationId,
            )
            dao.putAlbumCreditBinding(
                ArtistAlbumCreditBindingRecord(
                    libraryId = libraryId,
                    albumKey = albumId,
                    creditKey = creditKey,
                    associationId = replacementAssociationId,
                    evidenceType = ArtistMembershipEvidenceType.USER_EXPLICIT_REPLACEMENT.name,
                    confirmedAtMs = now,
                ),
            )
            dao.putMembershipEvidence(
                ArtistMembershipEvidenceRecord(
                    libraryId = libraryId,
                    albumKey = albumId,
                    creditKey = creditKey,
                    associationId = replacementAssociationId,
                    evidenceType = ArtistMembershipEvidenceType.USER_EXPLICIT_REPLACEMENT.name,
                    evidenceKey = albumId,
                    status = "CONFIRMED",
                    source = ArtistMembershipEvidenceType.USER_EXPLICIT_REPLACEMENT.name,
                    observedAtMs = now,
                    confirmedAtMs = now,
                ),
            )
        }
        dao.getAssociationResolutionsForCredit(libraryId, creditKey).forEach { resolution ->
            val contextAlbums = decodeAlbumIds(resolution.contextAlbumKeysJson)
            val affected = contextAlbums.any(visibleAlbums::contains) ||
                (resolution.contextAlbumKeysJson == null &&
                    resolution.chosenAlbumGroupKey == expectedCurrentAssociationId)
            if (affected) {
                dao.deleteAssociationResolution(
                    libraryId, creditKey, resolution.membershipFingerprint,
                )
            }
        }
        requireNotNull(decodePersisted(identity, recovered = false))
    }

    override suspend fun refreshProfile(
        libraryId: String,
        credit: String,
        associationId: String,
        profile: ArtistExternalProfile,
    ) = database.withTransaction {
        val creditKey = normalizedCreditKey(credit)
        val existing = dao.get(libraryId, creditKey, associationId)
            ?: error("La asociación solicitada no existe")
        persistProfile(existing, profile)
    }

    override suspend fun saveIdentity(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
        mbid: String,
        associationGroupKey: String?,
    ): PersistedArtistProfile {
        if (associationGroupKey == null) {
            return confirmNewIdentity(libraryId, credit, albumIds, mbid)
        }
        return database.withTransaction {
            require(scopedArtistCreditKey(libraryId, credit, albumIds) != null) {
                "No se puede reutilizar una asociación sin contexto local concreto"
            }
            val creditKey = normalizedCreditKey(credit)
            val canonicalMbid = requireNotNull(canonicalArtistMbid(mbid)) { "MBID inválido" }
            require(canonicalMbid == mbid) { "MBID sin normalizar" }
            val association = dao.getDurableAssociation(libraryId, creditKey, associationGroupKey)
                ?: error("La asociación histórica solicitada no existe.")
            val identity = dao.get(libraryId, creditKey, association.identityAssociationId)
                ?: error("La asociación perdió su identidad.")
            require(identity.confirmedMbid == canonicalMbid) {
                "La asociación ya pertenece a otro MBID; usa sustitución explícita."
            }
            requireNotNull(decodePersisted(identity, recovered = false))
        }
    }

    override suspend fun load(libraryId: String, credit: String, albumIds: List<String>): PersistedArtistProfile? {
        val (creditKey, groupKey) = scopedArtistCreditKey(libraryId, credit, albumIds) ?: return null
        val visibleAlbums = albumIds.filter(String::isNotBlank).distinct()
        val bindings = dao.getAlbumCreditBindings(libraryId, creditKey, visibleAlbums)
        val associationIds = bindings.map { it.associationId }.distinct()
        val boundAlbums = bindings.map { it.albumKey }.toSet()
        val pendingAlbums = visibleAlbums.filterNot(boundAlbums::contains).sorted()
        val explicit = resolvedAssociationForMembership(
            libraryId, creditKey, groupKey, associationIds.toSet(),
        )
        // Choosing a profile for this exact mixed view does not assign every
        // unbound album-credit component to that artist.
        val allVisibleBound = pendingAlbums.isEmpty()
        if (explicit != null) return decodePersisted(
            explicit, recovered = !allVisibleBound, unboundAlbumIds = pendingAlbums,
        )
        if (associationIds.size > 1) throw ArtistAssociationAmbiguousException()
        val associationId = associationIds.singleOrNull() ?: return null
        val association = dao.getDurableAssociation(libraryId, creditKey, associationId)
            ?: throw ArtistAssociationAmbiguousException()
        val record = dao.get(libraryId, creditKey, association.identityAssociationId)
            ?: throw ArtistAssociationAmbiguousException()
        if (canonicalArtistMbid(record.confirmedMbid) != record.confirmedMbid) return null
        return decodePersisted(
            record, recovered = !allVisibleBound, unboundAlbumIds = pendingAlbums,
        )
    }

    private fun normalizedCreditKey(credit: String): String = credit.trim().lowercase(Locale.ROOT)
        .takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Crédito artístico vacío")

    private fun decodeIdentityProfile(record: ArtistEnrichmentRecord): ArtistExternalProfile? = try {
        json.decodeFromString<ArtistExternalProfile>(record.profileJson)
            .takeIf { canonicalArtistMbid(it.mbid) == record.confirmedMbid }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun decodePersisted(
        record: ArtistEnrichmentRecord,
        recovered: Boolean,
        unboundAlbumIds: List<String> = emptyList(),
    ): PersistedArtistProfile? = try {
        val profile = json.decodeFromString<ArtistExternalProfile>(record.profileJson)
        if (canonicalArtistMbid(profile.mbid) != record.confirmedMbid ||
            (profile.portrait != null && !allowedCommonsPortrait(profile.portrait)) ||
            !allowedArtistBiography(profile.biography, profile.biographySourceUrl)) null
        else PersistedArtistProfile(
            profile = profile,
            stale = clock() >= record.staleAfterMs,
            associationGroupKey = record.albumGroupKey,
            needsConfirmation = recovered,
            unboundAlbumIds = unboundAlbumIds,
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
     * An explicit choice belongs only to the exact visible membership where the user made it.
     * It must never be propagated by overlap: that would turn a temporary combined view into
     * canonical ownership of another homonym's albums.
     */
    private suspend fun resolvedAssociationForMembership(
        libraryId: String,
        creditKey: String,
        membershipFingerprint: String,
        allowedAssociationIds: Set<String>,
    ): ArtistEnrichmentRecord? {
        val exact = dao.getAssociationResolution(libraryId, creditKey, membershipFingerprint)
            ?: return null
        if (exact.chosenAlbumGroupKey !in allowedAssociationIds) {
            throw ArtistAssociationAmbiguousException()
        }
        return dao.get(libraryId, creditKey, exact.chosenAlbumGroupKey)
            ?.takeIf { it.confirmedMbid == exact.chosenMbid }
            ?: throw ArtistAssociationAmbiguousException()
    }

    override suspend fun ambiguousAssociations(
        libraryId: String,
        credit: String,
        albumIds: List<String>,
    ): List<ArtistAssociationCandidate> {
        val (creditKey, groupKey) = scopedArtistCreditKey(libraryId, credit, albumIds) ?: return emptyList()
        val associationIds = dao.getAlbumCreditBindings(
            libraryId, creditKey, albumIds.filter(String::isNotBlank).distinct(),
        ).map { it.associationId }.distinct()
        return associationIds.mapNotNull { associationId ->
            dao.get(libraryId, creditKey, associationId)
        }
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
        val candidates = dao.getAlbumCreditBindings(
            libraryId, creditKey, albumIds.filter(String::isNotBlank).distinct(),
        ).map { it.associationId }.distinct().mapNotNull { associationId ->
            dao.get(libraryId, creditKey, associationId)
        }
        require(candidates.size > 1) { "La ambigüedad ya no existe; vuelve a cargar las alternativas." }
        val selected = candidates.singleOrNull {
            it.albumGroupKey == chosenAssociationGroupKey && it.confirmedMbid == canonical
        } ?: throw IllegalStateException("La asociación elegida ya no existe o cambió de identidad.")
        dao.putAssociationResolution(
            ArtistAssociationResolutionRecord(
                libraryId = libraryId,
                creditKey = creditKey,
                membershipFingerprint = groupKey,
                chosenAlbumGroupKey = selected.albumGroupKey,
                chosenMbid = selected.confirmedMbid,
                evidence = "USER_EXPLICIT_CHOICE",
                confirmedAtMs = clock(),
                contextAlbumKeysJson = json.encodeToString(
                    albumIds.filter(String::isNotBlank).distinct().sorted(),
                ),
            ),
        )
        requireNotNull(decodePersisted(selected, recovered = false)) {
            "La asociación elegida contiene un perfil inválido"
        }
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
        if (associationGroupKey == null) {
            val confirmed = confirmNewIdentity(libraryId, credit, albumIds, profile.mbid)
            refreshProfile(
                libraryId, credit, requireNotNull(confirmed.associationGroupKey), profile,
            )
            return
        }
        refreshProfile(libraryId, credit, associationGroupKey, profile)
    }

    private suspend fun persistProfile(
        existing: ArtistEnrichmentRecord,
        profile: ArtistExternalProfile,
    ) {
        val id = requireNotNull(canonicalArtistMbid(profile.mbid)) { "MBID inválido" }
        require(id == profile.mbid) { "MBID sin normalizar" }
        require(existing.confirmedMbid == id) {
            "La asociación ya pertenece a otro MBID; usa sustitución explícita."
        }
        require(profile.portrait == null || allowedCommonsPortrait(profile.portrait)) { "Retrato no autorizado" }
        require(allowedArtistBiography(profile.biography, profile.biographySourceUrl)) { "Biografía sin fuente verificable" }
        val now = clock()
        dao.put(
            existing.copy(
                profileJson = json.encodeToString(profile),
                savedAtMs = now,
                staleAfterMs = now + 7L * 24 * 60 * 60 * 1000,
                source = "musicbrainz-manual-selection",
                biographySourceUrl = profile.biographySourceUrl,
                portraitSourceUrl = profile.portrait?.commonsPageUrl,
                portraitLicense = profile.portrait?.license,
                portraitLicenseUrl = profile.portrait?.licenseUrl,
                portraitAttribution = profile.portrait?.attribution,
            ),
        )
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
                val associationIds = dao.getAlbumCreditBindings(
                    libraryId,
                    creditKey,
                    albumIds.filter(String::isNotBlank).distinct(),
                ).map { it.associationId }.distinct()
                val resolution = resolvedAssociationForMembership(
                    libraryId, creditKey, currentGroupKey, associationIds.toSet(),
                )
                if (resolution != null) {
                    resolution.albumGroupKey
                } else {
                    if (associationIds.size > 1) throw ArtistAssociationAmbiguousException()
                    associationIds.singleOrNull() ?: return@withTransaction
                }
            }
        }
        dao.delete(libraryId, creditKey, target)
        // Invalidate only exact-context choices that depended on the deleted canonical
        // group. Independent choices for the same normalized credit remain authoritative.
        dao.deleteAssociationResolutionsForGroup(libraryId, creditKey, target)
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
                    ArtistEnrichmentCacheDatabase.MIGRATION_8_9,
                    ArtistEnrichmentCacheDatabase.MIGRATION_9_10,
                )
                .build().also { databaseInstance = it }
        }

    fun from(context: Context): ArtistEnrichmentSelectionStore = instance ?: synchronized(this) {
        instance ?: RoomArtistEnrichmentSelectionStore(database(context)).also { instance = it }
    }
}
