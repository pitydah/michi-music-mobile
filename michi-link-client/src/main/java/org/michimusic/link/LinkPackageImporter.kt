package org.michimusic.link

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import org.michimusic.link.dto.SyncManifestDto
import java.io.File

class LinkPackageImporter(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    data class LocalPackage(
        val manifest: SyncManifestDto,
        val tracksDir: File,
        val coversDir: File,
    )

    fun scanDirectory(rootDir: File): LocalPackage? {
        val manifestFile = File(rootDir, "manifest.json")
        if (!manifestFile.exists()) return null

        val manifest = json.decodeFromString<SyncManifestDto>(manifestFile.readText())
        val tracksDir = File(rootDir, "tracks").also { it.mkdirs() }
        val coversDir = File(rootDir, "covers").also { it.mkdirs() }
        return LocalPackage(manifest, tracksDir, coversDir)
    }

    fun scanUri(uri: Uri): Boolean {
        // TODO: Implement with Storage Access Framework in future phase
        return false
    }

    fun importTrack(source: File, trackId: String, format: String): File? {
        val ext = format.lowercase().takeIf { it.isNotEmpty() } ?: "mp3"
        val destDir = File(context.filesDir, "synced_music").also { it.mkdirs() }
        val dest = File(destDir, "${trackId}.$ext")
        source.copyTo(dest, overwrite = true)
        return dest.takeIf { it.exists() }
    }
}
