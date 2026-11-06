package org.michimusic.mobile.artist.artwork

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.michimusic.data.artist.ArtistArtworkAssetRecord
import org.michimusic.data.artist.ArtistArtworkLinkRecord
import org.michimusic.data.artist.ArtistArtworkSelectionRecord
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence
import java.io.File

private const val PRUNE_GRACE_MS = 15 * 60 * 1000L

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

    /** Emits whenever a selection in this library changes, so a composed mosaic can reload. */
    fun observeLibraryRevision(libraryId: String): Flow<Long> = flowOf(0L)

    /** Removes orphan files only; referenced artwork is never deleted by quota enforcement. */
    suspend fun pruneCache(): Long = 0L
}

/** Absolute path is derived at read time; the database only stores a private relative path. */
class RoomArtistArtworkStore(
    private val database: ArtistEnrichmentCacheDatabase,
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : ArtistArtworkStore {
    private val dao get() = database.artistEnrichmentDao()

    override suspend fun load(anchor: ArtistIdentityAnchor): List<ArtistArtworkSelection> {
        val rows = dao.getArtworkSelections(
            anchor.libraryId, anchor.creditKey, anchor.albumGroupKey, anchor.confirmedMbid,
        )
        return rows.mapNotNull { row ->
            row.toSelection(
                asset = dao.getArtworkAsset(row.assetId),
                link = dao.getArtworkLink(
                    anchor.libraryId, anchor.creditKey, anchor.albumGroupKey, row.slot,
                    anchor.confirmedMbid,
                ),
            )
        }
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
        val link = ArtistArtworkLinkRecord(
            libraryId = anchor.libraryId,
            creditKey = anchor.creditKey,
            albumGroupKey = anchor.albumGroupKey,
            slot = slot.name,
            confirmedMbid = anchor.confirmedMbid,
            assetId = assetId,
            verifiedQid = candidate.verifiedQid,
            source = candidate.source.name,
            sourcePageUrl = candidate.canonicalSourcePage,
            imageUrl = candidate.imageUrl,
            licenseId = candidate.licenseId.orEmpty(),
            licenseUrl = candidate.licenseUrl.orEmpty(),
            attribution = candidate.attribution.orEmpty(),
            evidence = candidate.evidence.name,
            linkedAtMs = now,
        )
        dao.persistArtwork(asset, link, selection)
        return requireNotNull(selection.toSelection(asset, link)) { "Selección de artwork no legible" }
    }

    override suspend fun updateFocus(anchor: ArtistIdentityAnchor, slot: ArtworkSlot, focus: ArtworkFocus): Boolean {
        val normalized = focus.normalized()
        return dao.updateArtworkFocus(
            libraryId = anchor.libraryId,
            creditKey = anchor.creditKey,
            albumGroupKey = anchor.albumGroupKey,
            slot = slot.name,
            confirmedMbid = anchor.confirmedMbid,
            focusX = normalized.x,
            focusY = normalized.y,
            nowMs = clock(),
        ) > 0
    }

    override suspend fun clear(anchor: ArtistIdentityAnchor) {
        dao.clearArtworkForMbid(
            libraryId = anchor.libraryId,
            creditKey = anchor.creditKey,
            albumGroupKey = anchor.albumGroupKey,
            confirmedMbid = anchor.confirmedMbid,
            nowMs = clock(),
        )
    }

    override fun observeLibraryRevision(libraryId: String): Flow<Long> =
        dao.observeLibraryArtworkRevision(libraryId).map { it ?: 0L }

    override suspend fun counts(): Pair<Int, Int> =
        dao.countArtworkSelections() to dao.countArtworkAssets()

    override suspend fun pruneCache(): Long = withContext(Dispatchers.IO) {
        // Orphan asset rows are removed first; files referenced by any selection are kept.
        dao.deleteUnreferencedArtworkAssets()
        val referenced = dao.getAllArtworkAssets().map { it.localRelativePath }.toSet()
        val root = File(context.filesDir, ArtworkDownloader.DIRECTORY)
        if (!root.isDirectory) return@withContext 0L
        var freed = 0L
        root.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            // Asset rows store the path relative to filesDir ("artist-artwork/<sha>.jpg").
            // Comparing against relativeTo(root) would treat every referenced file as an
            // orphan and delete the very bytes the selection points at.
            val relative = file.relativeTo(context.filesDir).invariantSeparatorsPath
            // Downloads publish through a short-lived .tmp file. Only old temporaries are
            // orphans; deleting a fresh one would race an active download.
            val isTemp = file.name.endsWith(".tmp")
            val ageMs = System.currentTimeMillis() - file.lastModified()
            val staleTemp = isTemp && ageMs > PRUNE_GRACE_MS
            // A download publishes its final file before the asset row is committed.
            // Keep fresh final files during that window so a concurrent prune belonging
            // to another artist cannot delete them. Referenced files are never orphans.
            val freshFinal = !isTemp && ageMs < PRUNE_GRACE_MS
            val orphan = if (isTemp) staleTemp else relative !in referenced && !freshFinal
            if (orphan) {
                val length = file.length()
                if (file.delete()) freed += length
            }
        }
        freed
    }

    private fun ArtistArtworkSelectionRecord.toSelection(
        asset: ArtistArtworkAssetRecord?,
        link: ArtistArtworkLinkRecord?,
    ): ArtistArtworkSelection? {
        val slotValue = runCatching { ArtworkSlot.valueOf(slot) }.getOrNull() ?: return null
        if (asset == null || link == null || link.assetId != asset.assetId ||
            link.confirmedMbid != confirmedMbid
        ) {
            return ArtistArtworkSelection(
                slot = slotValue,
                assetId = assetId,
                confirmedMbid = confirmedMbid,
                localImagePath = null,
                focus = ArtworkFocus(focusX, focusY),
                manuallyChosen = manuallyChosen,
                revision = revision,
                status = ArtworkLoadStatus.IDENTITY_MISMATCH,
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
            sourcePageUrl = link.sourcePageUrl,
            licenseId = link.licenseId,
            licenseUrl = link.licenseUrl,
            attribution = link.attribution,
            source = runCatching { ArtworkSource.valueOf(link.source) }.getOrNull(),
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
