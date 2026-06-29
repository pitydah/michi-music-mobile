package org.michimusic.sync

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import org.michimusic.core.models.ManifestPlaylist
import org.michimusic.core.models.SyncManifest
import java.io.File
import java.io.FileInputStream

class LocalSyncPackageImporter(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    data class LocalSyncPackage(
        val manifest: SyncManifest,
        val tracksDir: File,
        val coversDir: File,
    )

    fun scanDirectory(rootDir: File): LocalSyncPackage? {
        val manifestFile = File(rootDir, "manifest.json")
        if (!manifestFile.exists()) return null

        val manifest = json.decodeFromString<SyncManifest>(manifestFile.readText())
        val tracksDir = File(rootDir, "tracks").also { it.mkdirs() }
        val coversDir = File(rootDir, "covers").also { it.mkdirs() }
        return LocalSyncPackage(manifest, tracksDir, coversDir)
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
