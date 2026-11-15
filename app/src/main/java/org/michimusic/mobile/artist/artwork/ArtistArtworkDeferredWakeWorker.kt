package org.michimusic.mobile.artist.artwork

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.MusicRootPreferences

/**
 * Single delayed *local* dispatch after a provider cooldown. No provider request is
 * performed by this worker; the regular worker revalidates Room identity, album
 * binding, active source, user consent and WorkManager network constraints.
 */
internal class ArtistArtworkDeferredWakeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        if (!ArtistArtworkAutoScheduler.isEnabled(app) ||
            ArtistArtworkAutoScheduler.isPaused(app) || isStopped
        ) return@withContext Result.success()

        val libraryId = inputData.getString(LIBRARY_ID).orEmpty()
        if (libraryId != MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID) return@withContext Result.success()
        val expectedDigest = inputData.getString(SOURCE_FINGERPRINT).orEmpty()
        val enteredRootEpoch = inputData.getLong(ROOT_EPOCH, -1L)
        val rootStamp = MusicRootPreferences(app).currentRootStamp()
        if (enteredRootEpoch < 0L || rootStamp.epoch != enteredRootEpoch) {
            // A previous generation's wake is not a new user-authorized event.
            return@withContext Result.success()
        }
        val sourceId = rootStamp.sourceId
        val actualDigest = ArtistArtworkDeferredWakePolicy.sourceFingerprint(libraryId, sourceId)
        if (actualDigest == null || actualDigest != expectedDigest) {
            // Old root work must not revive the new root's outbox.
            return@withContext Result.success()
        }
        try {
            if (MusicRootPreferences(app).currentRootStamp() != rootStamp) {
                return@withContext Result.success()
            }
            ArtistArtworkAutoScheduler(app).dispatchPending(requiredRootStamp = rootStamp)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        internal const val LIBRARY_ID = "libraryId"
        internal const val SOURCE_FINGERPRINT = "sourceFingerprint"
        internal const val ROOT_EPOCH = "rootEpoch"
    }
}
