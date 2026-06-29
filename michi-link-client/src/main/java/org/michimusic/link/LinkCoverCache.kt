package org.michimusic.link

import android.content.Context
import java.io.File

class LinkCoverCache(private val context: Context) {

    private val cacheDir: File
        get() = File(context.cacheDir, "covers").also { it.mkdirs() }

    fun getFile(coverId: String): File = File(cacheDir, "$coverId.jpg")

    fun get(coverId: String): File? = getFile(coverId).takeIf { it.exists() }

    suspend fun download(client: LinkClient, coverId: String): Result<File> {
        val file = getFile(coverId)
        return client.fetchCover(coverId = coverId, outputStream = java.io.FileOutputStream(file))
            .map { file }
    }

    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
