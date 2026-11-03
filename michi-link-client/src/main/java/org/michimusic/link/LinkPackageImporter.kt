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
        if (!source.isFile || !source.canRead()) return null
        val destDir = File(context.filesDir, "synced_music").also { it.mkdirs() }
        val dest = validatedImportDestination(destDir, trackId, format) ?: return null
        // CREATE_NEW is atomic: an attacker or concurrent import cannot insert a file
        // between destination validation and FileOutputStream creation.
        var created = false
        return try {
            java.nio.file.Files.newOutputStream(
                dest.toPath(),
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE,
            ).use { out ->
                created = true
                source.inputStream().use { input -> input.copyTo(out) }
            }
            dest
        } catch (_: Exception) {
            if (created) runCatching { java.nio.file.Files.deleteIfExists(dest.toPath()) }
            null
        }
    }
}
