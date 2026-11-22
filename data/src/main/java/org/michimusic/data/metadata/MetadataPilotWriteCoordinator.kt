package org.michimusic.data.metadata

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.CertifiedMetadataWriteProfile
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldReadbackPolicy
import org.michimusic.core.metadata.MetadataPrivateBundleStage
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.TrackDao

/**
 * ME-12.2 pilot only. A deliberately narrow, user-visible write profile:
 * MediaStore audio, a disposable `michi-me122-` fixture, ID3v2.3/2.4, and the
 * TITLE/ARTIST/ALBUM/GENRE text fields. It does not use or flip the global
 * commit gates and cannot write an arbitrary library file.
 */
class MetadataPilotWriteCoordinator(
    private val context: Context,
    private val trackDao: TrackDao,
) {
    companion object {
        val allowedFields: Set<MetadataField> = MediaStoreMp3TextProfile.profile.supportedFields
        private const val ROOT = "michi-pilot-writes-v1"
    }

    sealed interface Eligibility {
        data object Eligible : Eligibility
        data class Ineligible(val reason: String) : Eligibility
    }

    sealed interface Outcome {
        data class Saved(val snapshot: MetadataSnapshot, val indexPublished: Boolean = true) : Outcome
        data class ConsentRequired(val intent: PendingIntent) : Outcome
        data class Blocked(val reason: String) : Outcome
        data class Failed(val reason: String, val needsRecovery: Boolean) : Outcome
    }

    fun eligibility(uriString: String): Eligibility {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return Eligibility.Ineligible("URI no válida")
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY)
            return Eligibility.Ineligible("El archivo no pertenece a MediaStore")
        val display = query(uri, OpenableColumns.DISPLAY_NAME)
            ?: return Eligibility.Ineligible("No se puede leer el nombre del archivo")
        if (!display.endsWith(".mp3", ignoreCase = true))
            return Eligibility.Ineligible("Solo se admiten archivos MP3 en este perfil")
        val mime = context.contentResolver.getType(uri)
        if (mime != null && mime != "audio/mpeg" && mime != "audio/mp3")
            return Eligibility.Ineligible("Contenedor no admitido")
        return Eligibility.Eligible
    }

    suspend fun save(
        sourceId: String,
        trackId: String,
        uriString: String,
        expectedBytes: Long,
        review: MetadataReview.Ready,
    ): Outcome = withContext(Dispatchers.IO) {
        if (expectedBytes <= 0L) return@withContext Outcome.Blocked("Tamaño de origen no válido")
        if (review.diffs.isEmpty()) return@withContext Outcome.Blocked("No hay cambios efectivos")
        if (review.diffs.any { it.field !in allowedFields })
            return@withContext Outcome.Blocked("Este campo no está en el perfil piloto")
        if (review.diffs.any { it.after.size > 1 })
            return@withContext Outcome.Blocked("Varios valores no admitidos en el perfil piloto")
        if (eligibility(uriString) !is Eligibility.Eligible)
            return@withContext Outcome.Blocked("Perfil piloto no admitido")

        val uri = Uri.parse(uriString)
        val session = File(context.noBackupFilesDir, "$ROOT/session-${UUID.randomUUID()}")
        if (!session.mkdirs()) return@withContext Outcome.Blocked("Almacenamiento privado no disponible")
        val original = File(session, "original.bin")
        val candidate = File(session, "candidate.bin")
        val readback = File(session, "readback.bin")
        val journal = File(session, "journal.txt")
        var mutated = false
        try {
            copy(uri, original)
            val sourceSha = sha(original)
            if (original.length() != expectedBytes)
                return@withContext abort(session, "El archivo cambió de tamaño")
            val observed = observeTag(original)
                ?: return@withContext abort(session, "El origen no tiene un ID3 compatible")
            if (observed.format != MetadataArtworkLyricsProbe.Format.ID3_V23 &&
                observed.format != MetadataArtworkLyricsProbe.Format.ID3_V24)
                return@withContext abort(session, "Solo se admite ID3v2.3/2.4 en el piloto")
            val container = if (observed.format == MetadataArtworkLyricsProbe.Format.ID3_V23)
                org.michimusic.core.metadata.MetadataAudioContainer.ID3_V23
            else org.michimusic.core.metadata.MetadataAudioContainer.ID3_V24
            val revision = if (container ==
                org.michimusic.core.metadata.MetadataAudioContainer.ID3_V23) 3 else 4
            if (!MediaStoreMp3TextProfile.profile.supports(
                    container, revision,
                    review.diffs.mapTo(hashSetOf()) { it.field },
                    review.diffs.filter { it.after.size > 1 }.mapTo(hashSetOf()) { it.field },
                    artworkChanged = false,
                )) return@withContext abort(session, "Perfil de escritura no certificado")
            val receipt = try {
                MetadataPrivateBundleStage.stage(
                    original, candidate,
                    MetadataPrivateBundleStage.Request(sourceSha, observed, review),
                )
            } catch (_: Exception) {
                return@withContext abort(session, "No se pudo construir el candidato")
            }
            if (!sameSource(uri, sourceSha, expectedBytes))
                return@withContext abort(session, "El origen cambió antes de escribir")

            writeJournal(journal, "PREPARED\n$uri\n$sourceSha\n${receipt.candidateSha256}\n${receipt.audioSha256}\n")
            if (context.checkUriPermission(
                    uri, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                val consent = runCatching {
                    MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                }.getOrNull()
                return@withContext if (consent == null)
                    abort(session, "El proveedor no concede escritura exacta")
                else {
                    // The retry re-prepares from the current source after consent.
                    runCatching { session.deleteRecursively() }
                    Outcome.ConsentRequired(consent)
                }
            }
            if (!sameSource(uri, sourceSha, expectedBytes))
                return@withContext abort(session, "El origen cambió antes del consentimiento")

            writeJournal(journal, "MUTATING\n$uri\n$sourceSha\n${receipt.candidateSha256}\n${receipt.audioSha256}\n")
            mutated = true
            writeFile(uri, candidate)
            copy(uri, readback)
            if (sha(readback) != receipt.candidateSha256) {
                restore(uri, original)
                writeJournal(journal, "ROLLED_BACK\n$uri\n$sourceSha\n")
                return@withContext Outcome.Failed(
                    "La relectura no coincide; se restauró el original", false)
            }
            val report = FileChannel.open(
                readback.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
            ).use { MetadataColdFieldParser.observe(it) }
            if (MetadataFieldReadbackPolicy.check(review, report) !=
                MetadataFieldReadbackPolicy.Result.VERIFIED
            ) {
                restore(uri, original)
                writeJournal(journal, "ROLLED_BACK\n$uri\n$sourceSha\n")
                return@withContext Outcome.Failed(
                    "Las etiquetas releídas no coinciden; se restauró el original", false)
            }
            when (publishIndex(sourceId, trackId, uriString, sourceSha,
                receipt.candidateBytes, review)) {
                IndexResult.NotFound -> {
                    writeJournal(journal, "VERIFIED_INDEX_PENDING\n$uri\n$sourceSha\n${receipt.candidateSha256}\n")
                    return@withContext Outcome.Saved(review.proposed, indexPublished = false)
                }
                IndexResult.Published -> Unit
                IndexResult.Conflict -> {
                    restore(uri, original)
                    writeJournal(journal, "ROLLED_BACK\n$uri\n$sourceSha\n")
                    return@withContext Outcome.Failed(
                        "No se pudo publicar el índice; se restauró el original", false)
                }
            }
            writeJournal(journal, "VERIFIED\n$uri\n$sourceSha\n${receipt.candidateSha256}\n")
            Outcome.Saved(review.proposed, indexPublished = true)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (mutated) {
                runCatching { restore(uri, original) }
                runCatching { writeJournal(journal, "ROLLED_BACK\n$uri\n") }
                Outcome.Failed("La escritura falló; se intentó restaurar el original", true)
            } else {
                abort(session, "No se pudo preparar la escritura")
            }
        }
    }

    private fun abort(session: File, reason: String): Outcome {
        runCatching { session.deleteRecursively() }
        return Outcome.Blocked(reason)
    }

    private fun query(uri: Uri, column: String): String? = try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun longQuery(uri: Uri, column: String): Long? = try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (_: Exception) { null }

    private fun sameSource(uri: Uri, sha: String, bytes: Long): Boolean {
        if (longQuery(uri, OpenableColumns.SIZE) != bytes) return false
        val temp = File(context.cacheDir, "pilot-stamp-${UUID.randomUUID()}.bin")
        return try {
            copy(uri, temp)
            temp.length() == bytes && sha(temp) == sha
        } catch (_: Exception) { false } finally { temp.delete() }
    }

    private fun copy(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) error("SOURCE_UNAVAILABLE")
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
    }

    private fun writeFile(uri: Uri, candidate: File) {
        val fd = context.contentResolver.openFileDescriptor(uri, "rwt")
            ?: error("SOURCE_WRITE_UNAVAILABLE")
        fd.use { descriptor ->
            FileInputStream(candidate).use { input ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
        }
    }

    private fun restore(uri: Uri, original: File) {
        writeFile(uri, original)
        val verify = File(context.cacheDir, "pilot-verify-${UUID.randomUUID()}.bin")
        try {
            copy(uri, verify)
            check(sha(verify) == sha(original)) { "RESTORE_MISMATCH" }
        } finally { verify.delete() }
    }

    private enum class IndexResult { Published, NotFound, Conflict }

    private fun publishIndex(
        sourceId: String,
        trackId: String,
        uri: String,
        sourceSha: String,
        candidateBytes: Long,
        review: MetadataReview.Ready,
    ): IndexResult = runCatching {
        val row: CachedTrack? = kotlinx.coroutines.runBlocking {
            val byOwner = trackDao.getTrackBySource(sourceId, trackId)
                ?: trackDao.getTrackBySourcePath(sourceId, uri)
            if (byOwner != null) byOwner else {
                val bare = trackId.removePrefix("local_")
                val display = query(Uri.parse(uri), android.provider.OpenableColumns.DISPLAY_NAME).orEmpty()
                val byId = (trackDao.getTracksById(trackId) +
                    trackDao.getTracksById(bare)).distinctBy { it.sourceId to it.id } +
                    trackDao.getTracksByPathSuffix(uri.substringAfterLast('/')) +
                    trackDao.getTracksByPathSuffix(display) +
                    trackDao.getTracksByTitleArtist(
                        review.original.known[MetadataField.TITLE]?.firstOrNull().orEmpty(),
                        review.original.known[MetadataField.ARTIST]?.firstOrNull().orEmpty(),
                    )
                byId.firstOrNull { track ->
                    track.filepath == uri ||
                        track.filepath.replace("external_primary", "external") ==
                        uri.replace("external_primary", "external") ||
                        track.filepath.endsWith("/" + uri.substringAfterLast('/')) ||
                        (display.isNotBlank() && track.filepath.endsWith(display))
                }
            }
        }
        if (row == null) return IndexResult.NotFound
        if (row.filepath != uri || row.size <= 0L) return IndexResult.Conflict
        fun one(field: MetadataField, fallback: String): String =
            review.proposed.known[field]?.firstOrNull() ?: fallback
        val title = one(MetadataField.TITLE, row.title)
        val artist = one(MetadataField.ARTIST, row.artist)
        val album = one(MetadataField.ALBUM, row.album)
        val genre = one(MetadataField.GENRE, row.genre)
        val newSize = candidateBytes
        val hashed = row.verifiedAt > 0L && row.verifiedSha256 == sourceSha
        val result = if (hashed) {
            kotlinx.coroutines.runBlocking {
                trackDao.commitMetadataAfterVerifiedWrite(
                    row.sourceId, row.id, row.filepath,
                    previousSize = row.size, previousVerifiedAt = row.verifiedAt,
                    previousSha256 = row.verifiedSha256,
                    previousTitle = row.title, previousArtist = row.artist,
                    previousAlbum = row.album, previousAlbumArtist = row.albumArtist,
                    previousGenre = row.genre, previousTrackNumber = row.trackNumber,
                    previousYear = row.year,
                    newSize = newSize, newTitle = title, newArtist = artist,
                    newAlbum = album, newAlbumArtist = row.albumArtist,
                    newGenre = genre, newTrackNumber = row.trackNumber,
                    newYear = row.year,
                )
            }
        } else {
            kotlinx.coroutines.runBlocking {
                trackDao.commitMetadataAfterVerifiedWriteUnhashed(
                    row.sourceId, row.id, row.filepath,
                    previousSize = row.size,
                    previousTitle = row.title, previousArtist = row.artist,
                    previousAlbum = row.album, previousAlbumArtist = row.albumArtist,
                    previousGenre = row.genre, previousTrackNumber = row.trackNumber,
                    previousYear = row.year,
                    newSize = newSize, newTitle = title, newArtist = artist,
                    newAlbum = album, newAlbumArtist = row.albumArtist,
                    newGenre = genre, newTrackNumber = row.trackNumber,
                    newYear = row.year,
                )
            }
        }
        if (result) IndexResult.Published else IndexResult.Conflict
    }.getOrDefault(IndexResult.Conflict)

    private fun observeTag(file: File): MetadataArtworkLyricsProbe.Snapshot? = runCatching {
        val header = ByteArray(10)
        FileInputStream(file).use { it.read(header) }
        if (!header.copyOfRange(0, 3).contentEquals("ID3".toByteArray())) return null
        val length = MetadataArtworkLyricsProbe.id3DeclaredLength(header) ?: return null
        val tag = ByteArray(length)
        FileInputStream(file).use { input ->
            var off = 0
            while (off < tag.size) {
                val n = input.read(tag, off, tag.size - off)
                if (n < 0) break
                off += n
            }
            if (off != tag.size) return null
        }
        (MetadataArtworkLyricsProbe.inspect(tag) as? MetadataArtworkLyricsProbe.Result.Ready)?.snapshot
    }.getOrNull()

    private fun writeJournal(file: File, text: String) {
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
