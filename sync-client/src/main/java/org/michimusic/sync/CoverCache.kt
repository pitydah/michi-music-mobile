package org.michimusic.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CoverCache(
    private val context: Context,
) {
    private val coverDir: File
        get() = File(context.cacheDir, "covers").also { it.mkdirs() }

    suspend fun getCover(
        client: MichiSyncClient,
        coverId: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(coverDir, coverId)

        if (file.exists()) {
            return@withContext file.readBytes()
        }

        val baos = java.io.ByteArrayOutputStream()
        client.fetchCover(coverId, baos)
            .onSuccess { bytes ->
                file.parentFile?.mkdirs()
                file.writeBytes(baos.toByteArray())
            }
            .onFailure { return@withContext null }

        file.takeIf { it.exists() }?.readBytes()
    }

    fun getCachedCoverFile(coverId: String): File? {
        return File(coverDir, coverId).takeIf { it.exists() }
    }

    fun clear() {
        coverDir.listFiles()?.forEach { it.delete() }
    }
}
