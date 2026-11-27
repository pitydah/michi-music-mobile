package org.michimusic.mobile.artist.artwork

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.data.artist.ArtistArtworkCleanupRecord
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Local-only, crash-restartable deletion of EXACT content-addressed assets.
 * WorkManager scheduling is not the authority: the SQLite journal is.
 */
class ArtistArtworkCleanupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // A watchdog must not create a fresh database merely because a previous
            // installation had used artist artwork. Without a database there is no journal.
            if (!applicationContext.getDatabasePath("michi-artist-enrichment.db").isFile)
                return@withContext Result.success()
            var retry = false
            ArtistArtworkWriteGate.guarding {
                val dao = ArtistEnrichmentPersistence.database(applicationContext).artistEnrichmentDao()
                val prefs = applicationContext.getSharedPreferences(CURSOR_PREFS, Context.MODE_PRIVATE)
                // Cursor is ONLY a fairness hint; the durable journal still owns every row.
                // All fields are read together under the same process-wide write gate.
                val cursor = try {
                    val ms = prefs.getLong("time", -1L)
                    val id = prefs.getString("cleanup", null)
                    val asset = prefs.getString("asset", null)
                    if (ms >= 0 && !id.isNullOrBlank() && id.length <= 128 &&
                        !asset.isNullOrBlank() && asset.length <= 128) Triple(ms, id, asset) else null
                } catch (_: ClassCastException) { null }
                val rows = cursor?.let {
                    dao.pendingArtworkCleanupAfter(it.first, it.second, it.third, MAX_BATCH)
                }.orEmpty().ifEmpty { dao.pendingArtworkCleanup(MAX_BATCH) }
                val now = System.currentTimeMillis()
                var last: ArtistArtworkCleanupRecord? = null
                for (row in rows) {
                    if (isStopped) break
                    // One failing row cannot starve all later assets; cancellation still propagates.
                    try {
                        if (cleanOne(row, now)) retry = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { retry = true }
                    last = row
                }
                last?.let {
                    prefs.edit().putLong("time", it.createdAtMs).putString("cleanup", it.cleanupId)
                        .putString("asset", it.assetId).commit()
                }
                if (dao.pendingArtworkCleanup(1).isNotEmpty()) retry = true
                else prefs.edit().clear().apply()
            }
            // Durable journal state, not the attempt counter, decides completion.
            // WorkManager backs off retries; claiming SUCCESS would strand pending rows.
            when (artistArtworkCleanupDisposition(stopped = isStopped, pending = retry, failed = false)) {
                ArtistArtworkCleanupDisposition.RETRY -> Result.retry()
                ArtistArtworkCleanupDisposition.SUCCESS -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A transient Room or file-system failure cannot be reported as success
            // merely because this Worker has already retried several times.
            Result.retry()
        }
    }

    /** True means a recoverable item is still pending. Never follow symlinks. */
    private suspend fun cleanOne(row: ArtistArtworkCleanupRecord, now: Long): Boolean {
        val dao = ArtistEnrichmentPersistence.database(applicationContext).artistEnrichmentDao()
        val hash = row.sha256.lowercase()
        val match = Regex("^artist-artwork/([a-f0-9]{64})\\.(jpg|png|webp)$")
            .matchEntire(row.localRelativePath)
        if (row.assetId != "sha256:$hash" || hash.length != 64 ||
            match?.groupValues?.get(1) != hash || row.sha256 != hash
        ) {
            dao.finishArtworkCleanup(row.cleanupId, row.assetId, "REVIEW_REQUIRED")
            return false
        }
        val current = dao.getArtworkAsset(row.assetId)
        if (current != null && (current.sha256 != hash ||
                    current.localRelativePath != row.localRelativePath)) {
            dao.finishArtworkCleanup(row.cleanupId, row.assetId, "RETAINED_CHANGED")
            return false
        }
        val root = File(applicationContext.filesDir, ArtworkDownloader.DIRECTORY)
        val file = File(applicationContext.filesDir, row.localRelativePath)
        if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(file.toPath()) ||
            file.canonicalFile.parentFile != root.canonicalFile
        ) {
            dao.finishArtworkCleanup(row.cleanupId, row.assetId, "REVIEW_REQUIRED")
            return false
        }
        if (dao.countSelectionReferencesToAsset(row.assetId) > 0 ||
            dao.countLinkReferencesToAsset(row.assetId) > 0 ||
            dao.countOtherArtworkAssetsForPath(row.assetId, row.localRelativePath) > 0
        ) {
            // D52: a process may have died after staging and before the DB ack.
            // Do not mark a shared asset retained until its original bytes are available.
            val retained = ArtistArtworkQuarantine.recoverReferenced(
                root, file, row.cleanupId, row.assetId, hash,
            )
            dao.finishArtworkCleanup(row.cleanupId, row.assetId,
                if (retained) "RETAINED_SHARED" else "REVIEW_REQUIRED")
            return false
        }
        if (now < row.createdAtMs || now - row.createdAtMs < GRACE_MS) return true
        // If a source was recently replaced, keep the original grace window.
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            now - file.lastModified() < GRACE_MS) return true
        // D45: never unlink the shared/content-addressed original pathname.
        // An atomic same-directory move isolates the inode in a private quarantine.
        // If anything changes before or after the move, preserve those bytes.
        when (val stage = ArtistArtworkQuarantine.stage(
            root, file, row.cleanupId, row.assetId, hash,
        )) {
            ArtistArtworkQuarantineStage.ReviewRequired -> {
                dao.finishArtworkCleanup(row.cleanupId, row.assetId, "REVIEW_REQUIRED")
                return false
            }
            ArtistArtworkQuarantineStage.Retry -> return true
            ArtistArtworkQuarantineStage.SourceAbsent -> Unit
            is ArtistArtworkQuarantineStage.Staged -> {
                // Guarding protects in-app artwork writes; no reference may reappear
                // between staging and removal without being checked again.
                val referenced = dao.countSelectionReferencesToAsset(row.assetId) > 0 ||
                    dao.countLinkReferencesToAsset(row.assetId) > 0 ||
                    dao.countOtherArtworkAssetsForPath(row.assetId, row.localRelativePath) > 0
                if (referenced) {
                    val restored = ArtistArtworkQuarantine.restore(
                        stage, root, file, row.cleanupId, row.assetId, hash,
                    )
                    dao.finishArtworkCleanup(row.cleanupId, row.assetId,
                        if (restored) "RETAINED_SHARED" else "REVIEW_REQUIRED")
                    return false
                }
                when (ArtistArtworkQuarantine.destroy(
                    stage, root, row.cleanupId, row.assetId, hash,
                )) {
                    ArtistArtworkQuarantineDelete.DELETED -> Unit
                    ArtistArtworkQuarantineDelete.REVIEW_REQUIRED -> {
                        dao.finishArtworkCleanup(row.cleanupId, row.assetId, "REVIEW_REQUIRED")
                        return false
                    }
                    ArtistArtworkQuarantineDelete.RETRY -> return true
                }
            }
        }
        // If power failed after unlink but before DB ack, the next run sees no file.
        dao.deleteArtworkAssetIfUnreferenced(row.assetId)
        dao.finishArtworkCleanup(row.cleanupId, row.assetId, "REMOVED_OR_ABSENT")
        return false
    }

    companion object {
        private const val UNIQUE_NAME = "michi.artist.artwork.cleanup.v12"
        private const val RECOVERY_NAME = "michi.artist.artwork.cleanup.recovery.v1"
        private const val MAX_BATCH = 64
        private const val CURSOR_PREFS = "michi.artist.artwork.cleanup.cursor.v1"
        private const val GRACE_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val job = OneTimeWorkRequestBuilder<ArtistArtworkCleanupWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            val manager = WorkManager.getInstance(context.applicationContext)
            // Two bounded unique lanes: KEEP never grows a chain, the independent
            // periodic lane recovers a missed final-read wake-up or cancelled fast job.
            // It runs only for installations that have requested journal cleanup.
            // OS scheduling is opportunistic; this is not an exact deletion deadline.
            val recovery = PeriodicWorkRequestBuilder<ArtistArtworkCleanupWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(RECOVERY_NAME, ExistingPeriodicWorkPolicy.KEEP, recovery)
            manager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, job)
        }
    }
}

/** Pure bounded digest check; called only after the caller rejects symlinks. */
internal fun artworkCleanupDigestMatches(file: File, expectedHash: String): Boolean {
    val path = file.toPath()
    return try {
        val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.fileKey() == null ||
            before.size() !in 1L..ArtworkDownloader.MAX_IMAGE_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) return false
                total += count
                if (total > before.size() || total > ArtworkDownloader.MAX_IMAGE_BYTES) return false
                digest.update(buffer, 0, count)
            }
        }
        val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS)
        total == before.size() && after.isRegularFile && before.fileKey() == after.fileKey() &&
            before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expectedHash
    } catch (_: java.io.IOException) { false }
    catch (_: SecurityException) { false }
    catch (_: UnsupportedOperationException) { false }
}
