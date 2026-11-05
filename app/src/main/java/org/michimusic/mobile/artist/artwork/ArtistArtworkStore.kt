package org.michimusic.mobile.artist.artwork

import android.content.Context
import org.michimusic.data.artist.ArtistArtworkAssetRecord
import org.michimusic.data.artist.ArtistArtworkSelectionRecord
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence

interface ArtistArtworkStore {
    suspend fun load(anchor: ArtistIdentityAnchor): List<ArtistArtworkSelection>
    suspend fun loadAsset(assetId: String): ArtistArtworkSelection?
    suspend fun save(
        anchor: ArtistIdentityAnchor,
        candidate: ArtistArtworkCandidate,
        slot: ArtworkSlot,
        downloaded: DownloadedArtwork,
        focus: ArtworkFocus,
        manuallyChosen: Boolean,
    ): ArtistArtworkSelection
    suspend fun updateFocus(anchor: ArtistIdentityAnchor, slot: ArtworkSlot, focus: ArtworkFocus): Boolean
    suspend fun clear(anchor: ArtistIdentityAnchor)
    suspend fun counts(): Pair<Int, Int>
}

/** Absolute path is derived at read time; the database only stores a private relative path. */
class RoomArtistArtworkStore(
    private val database: ArtistEnrichmentCacheDatabase,
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : ArtistArtworkStore {
    private val dao get() = database.artistEnrichmentDao()

    override suspend fun load(anchor: ArtistIdentityAnchor): List<ArtistArtworkSelection> {
        val rows = dao.getArtworkSelections(anchor.libraryId, anchor.creditKey, anchor.albumGroupKey)
        return rows.mapNotNull { row -> row.toSelection(dao.getArtworkAsset(row.assetId)) }
    }

    override suspend fun loadAsset(assetId: String): ArtistArtworkSelection? {
        val asset = dao.getArtworkAsset(assetId) ?: return null
        return asset.toSelection(null)
    }

    override suspend fun save(
        anchor: ArtistIdentityAnchor,
        candidate: ArtistArtworkCandidate,
        slot: ArtworkSlot,
        downloaded: DownloadedArtwork,
        focus: ArtworkFocus,
        manuallyChosen: Boolean,
    ): ArtistArtworkSelection {
        val hash = downloaded.sha256.lowercase()
        require(hash.matches(Regex("^[0-9a-f]{64}$"))) { "SHA-256 de artwork no válido" }
        require(downloaded.relativePath.startsWith("artist-artwork/")) { "Ruta de artwork no privada" }
        val now = clock()
        val assetId = "sha256:$hash"
        val asset = ArtistArtworkAssetRecord(
            assetId = assetId,
            confirmedMbid = anchor.confirmedMbid,
            verifiedQid = anchor.verifiedQid,
            source = candidate.source.name,
            sourcePageUrl = candidate.canonicalSourcePage,
            imageUrl = candidate.imageUrl,
            localRelativePath = downloaded.relativePath,
            sha256 = hash,
            mimeType = downloaded.mimeType,
            width = downloaded.width,
            height = downloaded.height,
            licenseId = candidate.licenseId.orEmpty(),
            licenseUrl = candidate.licenseUrl.orEmpty(),
            attribution = candidate.attribution.orEmpty(),
            evidence = candidate.evidence.name,
            downloadedAtMs = now,
        )
        val selection = ArtistArtworkSelectionRecord(
            libraryId = anchor.libraryId,
            creditKey = anchor.creditKey,
            albumGroupKey = anchor.albumGroupKey,
            slot = slot.name,
            assetId = assetId,
            confirmedMbid = anchor.confirmedMbid,
            focusX = focus.normalized().x,
            focusY = focus.normalized().y,
            manuallyChosen = manuallyChosen,
            revision = now,
            updatedAtMs = now,
        )
        dao.persistArtwork(asset, selection)
        return requireNotNull(asset.toSelection(selection)) { "Selección de artwork no legible" }
    }

    override suspend fun updateFocus(anchor: ArtistIdentityAnchor, slot: ArtworkSlot, focus: ArtworkFocus): Boolean {
        val rows = dao.getArtworkSelections(anchor.libraryId, anchor.creditKey, anchor.albumGroupKey)
        val row = rows.firstOrNull { it.slot == slot.name } ?: return false
        val normalized = focus.normalized()
        dao.putArtworkSelection(row.copy(
            focusX = normalized.x,
            focusY = normalized.y,
            revision = clock(),
            updatedAtMs = clock(),
        ))
        return true
    }

    override suspend fun clear(anchor: ArtistIdentityAnchor) {
        dao.clearArtworkSelections(anchor.libraryId, anchor.creditKey, anchor.albumGroupKey)
    }

    override suspend fun counts(): Pair<Int, Int> =
        dao.countArtworkSelections() to dao.countArtworkAssets()

    private fun ArtistArtworkSelectionRecord.toSelection(asset: ArtistArtworkAssetRecord?): ArtistArtworkSelection? {
        val slotValue = runCatching { ArtworkSlot.valueOf(slot) }.getOrNull() ?: return null
        if (asset == null) {
            return ArtistArtworkSelection(
                slot = slotValue,
                assetId = assetId,
                confirmedMbid = confirmedMbid,
                localImagePath = null,
                focus = ArtworkFocus(focusX, focusY),
                manuallyChosen = manuallyChosen,
                revision = revision,
                status = ArtworkLoadStatus.CACHE_ERROR,
            )
        }
        val path = java.io.File(context.filesDir, asset.localRelativePath)
        val exists = path.isFile && path.length() > 0
        return ArtistArtworkSelection(
            slot = slotValue,
            assetId = asset.assetId,
            confirmedMbid = confirmedMbid,
            localImagePath = path.absolutePath.takeIf { exists },
            focus = ArtworkFocus(focusX, focusY),
            manuallyChosen = manuallyChosen,
            revision = revision,
            sourcePageUrl = asset.sourcePageUrl,
            licenseId = asset.licenseId,
            licenseUrl = asset.licenseUrl,
            attribution = asset.attribution,
            source = runCatching { ArtworkSource.valueOf(asset.source) }.getOrNull(),
            status = if (exists) ArtworkLoadStatus.READY else ArtworkLoadStatus.CACHE_ERROR,
        )
    }

    private fun ArtistArtworkAssetRecord.toSelection(selection: ArtistArtworkSelectionRecord?): ArtistArtworkSelection? {
        val path = java.io.File(context.filesDir, localRelativePath)
        val exists = path.isFile && path.length() > 0
        return ArtistArtworkSelection(
            slot = selection?.let { runCatching { ArtworkSlot.valueOf(it.slot) }.getOrNull() }
                ?: ArtworkSlot.ARTIST_THUMB,
            assetId = assetId,
            confirmedMbid = confirmedMbid,
            localImagePath = path.absolutePath.takeIf { exists },
            focus = selection?.let { ArtworkFocus(it.focusX, it.focusY) } ?: ArtworkFocus(),
            manuallyChosen = selection?.manuallyChosen ?: false,
            revision = selection?.revision ?: downloadedAtMs,
            sourcePageUrl = sourcePageUrl,
            licenseId = licenseId,
            licenseUrl = licenseUrl,
            attribution = attribution,
            source = runCatching { ArtworkSource.valueOf(source) }.getOrNull(),
            status = if (exists) ArtworkLoadStatus.READY else ArtworkLoadStatus.CACHE_ERROR,
        )
    }
}

object ArtistArtworkPersistence {
    @Volatile private var instance: ArtistArtworkStore? = null

    fun from(context: Context): ArtistArtworkStore = instance ?: synchronized(this) {
        instance ?: RoomArtistArtworkStore(
            database = ArtistEnrichmentPersistence.database(context),
            context = context.applicationContext,
        ).also { instance = it }
    }
}
