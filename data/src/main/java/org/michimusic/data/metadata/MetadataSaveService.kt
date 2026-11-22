package org.michimusic.data.metadata

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import org.michimusic.core.metadata.MetadataPrivateBundleStage
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.TrackDao

/** ME-13: the single Save facade. All source changes are reserved by Journal V1. */
class MetadataSaveService(
    private val context: Context,
    private val trackDao: TrackDao,
) {
    sealed interface Eligibility {
        data object Eligible : Eligibility
        data class Ineligible(val reason: String) : Eligibility
    }

    sealed interface Outcome {
        data class Saved(val snapshot: MetadataSnapshot, val indexPublished: Boolean) : Outcome
        data class ConsentRequired(val intent: PendingIntent) : Outcome
        data class Blocked(val reason: String) : Outcome
        data class RecoveryRequired(val reason: String) : Outcome
        data class Failed(val reason: String, val needsRecovery: Boolean) : Outcome
    }

    private val journal = MetadataRecoveryJournalV1(
        File(context.noBackupFilesDir, "michi-metadata-editor-v1"),
    )
    private val transport = MetadataProviderMutationTransport(context)
    private val finalization = MetadataPostMutationFinalizationCoordinator(context)
    private val intents = MetadataCommitIntentStore(journal)
    private val profile = MediaStoreMp3TextProfile.profile

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
        val pending = journal.inspect()
        if (pending is MetadataRecoveryJournalV1.Inspection.Pending &&
            pending.entry.uri != uriString)
            return Eligibility.Ineligible("Hay otra escritura pendiente de recuperación")
        return Eligibility.Eligible
    }

    suspend fun save(
        sourceId: String,
        trackId: String,
        uriString: String,
        expectedBytes: Long,
        reviewInput: MetadataReview.Ready,
    ): Outcome = withContext(Dispatchers.IO) {
        val canonicalTarget = reviewInput.proposed.target.copy(
            sourceId = sourceId, trackId = trackId, uri = uriString,
        )
        val review = reviewInput.copy(
            original = reviewInput.original.copy(target = canonicalTarget),
            proposed = reviewInput.proposed.copy(target = canonicalTarget),
        )
        if (expectedBytes <= 0L) return@withContext Outcome.Blocked("Tamaño de origen no válido")
        if (review.diffs.isEmpty()) return@withContext Outcome.Blocked("No hay cambios efectivos")
        if (review.diffs.any { it.field !in profile.supportedFields })
            return@withContext Outcome.Blocked("Este campo no está en el perfil certificado")
        if (review.diffs.any { it.after.size > 1 })
            return@withContext Outcome.Blocked("Varios valores no admitidos en este perfil")
        if (eligibility(uriString) !is Eligibility.Eligible)
            return@withContext Outcome.Blocked("Perfil de escritura no certificado")

        val uri = Uri.parse(uriString)
        val stamp = (longQuery(uri, MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L) * 1000L
        if (stamp <= 0L) return@withContext Outcome.Blocked("El origen no tiene sello válido")

        val sourceCopy = File(context.cacheDir, "me13-source-${UUID.randomUUID()}.bin")
        var entry: MetadataRecoveryJournalV1.Entry
        var candidate: File
        try {
            copy(uri, sourceCopy)
            val sourceSha = sha(sourceCopy)
            if (sourceCopy.length() != expectedBytes)
                return@withContext Outcome.Blocked("El archivo cambió de tamaño")
            val observed = observeTag(sourceCopy)
                ?: return@withContext Outcome.Blocked("El origen no tiene un ID3 compatible")
            val revision = when (observed.format) {
                MetadataArtworkLyricsProbe.Format.ID3_V23 -> 3
                MetadataArtworkLyricsProbe.Format.ID3_V24 -> 4
                else -> return@withContext Outcome.Blocked("Solo se admite ID3v2.3/2.4")
            }
            val fields = review.diffs.mapTo(linkedSetOf()) { it.field }
            if (!profile.supports(observed.format.toContainer(), revision, fields, emptySet(), false))
                return@withContext Outcome.Blocked("Perfil de escritura no certificado")

            val pending = journal.inspect()
            if (pending is MetadataRecoveryJournalV1.Inspection.Blocked)
                return@withContext Outcome.Failed("Journal no legible", true)
            if (pending is MetadataRecoveryJournalV1.Inspection.Pending) {
                val existing = pending.entry
                if (existing.uri != uriString || existing.sourceId != sourceId ||
                    existing.trackId != trackId || existing.expectedBytes != expectedBytes ||
                    existing.phase !in setOf(MetadataRecoveryPhase.STAGE_READY,
                        MetadataRecoveryPhase.SNAPSHOTTING, MetadataRecoveryPhase.BACKUP_DURABLE,
                        MetadataRecoveryPhase.MUTATING, MetadataRecoveryPhase.MUTATION_UNKNOWN,
                        MetadataRecoveryPhase.POSTCHECK_VERIFIED)) {
                    return@withContext Outcome.Failed("Hay una recuperación pendiente", true)
                }
                entry = existing
                candidate = journal.stage(entry)
                if (entry.phase == MetadataRecoveryPhase.SNAPSHOTTING) {
                    return@withContext Outcome.Failed("Recuperación pendiente incompleta", true)
                }
                val persisted = intents.inspect(entry)
                if (persisted !is MetadataCommitIntentStore.Inspection.Present)
                    return@withContext Outcome.Blocked(
                        "La intención persistida no es válida; no se reanuda",
                    )
                val intent = persisted.receipt
                val pendingFields = review.diffs.map { it.field }.sortedBy { it.name }
                if (intent.candidateSha256 != entry.stageSha256 ||
                    intent.originalSha256 != entry.originalSha256 ||
                    intent.candidateBytes != entry.stageBytes ||
                    intent.changes.map { it.field } != pendingFields ||
                    intent.container != observed.format.toContainer() ||
                    intent.provider != MetadataDocumentProvider.MEDIASTORE ||
                    intent.ownerSha256 !=
                    org.michimusic.core.metadata.MetadataPrivateSessionIdentity.digest(
                        review.original.target,
                    ))
                    return@withContext Outcome.Blocked(
                        "La revisión cambió desde la preparación; no se reanuda",
                    )
                val changed = review.diffs.associateBy { it.field }
                if (intent.changes.any { change ->
                        val diff = changed[change.field]
                            ?: return@withContext Outcome.Blocked("La revisión no coincide")
                        MetadataCommitIntentManifest.valuesDigest(diff.before) !=
                            change.beforeSha256 ||
                            MetadataCommitIntentManifest.valuesDigest(diff.after) !=
                            change.afterSha256
                    })
                    return@withContext Outcome.Blocked(
                        "El valor revisado no coincide con la intención persistida",
                    )
            } else {
                entry = MetadataRecoveryJournalV1.Entry(
                    id = UUID.randomUUID().toString(), phase = MetadataRecoveryPhase.SNAPSHOTTING,
                    sourceId = sourceId, trackId = trackId, uri = uriString,
                    expectedBytes = expectedBytes, expectedModifiedAt = stamp,
                    format = observed.format.name,
                )
                journal.start(entry)
                val snapshotEntry = entry
                val backup = journal.backup(entry)
                sourceCopy.copyTo(backup, overwrite = false)
                if (sha(backup) != sourceSha)
                    return@withContext abort(entry, "La copia privada no coincide")
                entry = snapshotEntry.copy(phase = MetadataRecoveryPhase.BACKUP_DURABLE,
                    originalSha256 = sourceSha)
                journal.advance(snapshotEntry, entry)
                val backupEntry = entry
                candidate = journal.stage(entry)
                val receipt = MetadataPrivateBundleStage.stage(
                    backup, candidate,
                    MetadataPrivateBundleStage.Request(sourceSha, observed, review),
                )
                entry = backupEntry.copy(phase = MetadataRecoveryPhase.STAGE_READY,
                    stageSha256 = receipt.candidateSha256, stageBytes = receipt.candidateBytes,
                    format = observed.format.name)
                journal.advance(backupEntry, entry)
                val manifest = MetadataCommitIntentManifest.create(
                    transactionId = entry.id, review = review,
                    provider = MetadataDocumentProvider.MEDIASTORE,
                    container = observed.format.toContainer(),
                    originalBytes = entry.expectedBytes,
                    originalSha256 = entry.originalSha256,
                    candidateBytes = entry.stageBytes,
                    candidateSha256 = entry.stageSha256,
                )
                val written = journal.exclusive {
                    runCatching { intents.write(entry, manifest) }.isSuccess
                }
                if (!written) {
                    journal.discardPrepared(entry.id)
                    return@withContext Outcome.Blocked("No se pudo persistir la intención")
                }
            }
            if (entry.phase in setOf(MetadataRecoveryPhase.MUTATING,
                    MetadataRecoveryPhase.MUTATION_UNKNOWN,
                    MetadataRecoveryPhase.POSTCHECK_VERIFIED)) {
                val revision = when (entry.format) {
                    "ID3_V23" -> 3
                    "ID3_V24" -> 4
                    else -> return@withContext Outcome.RecoveryRequired("UNSUPPORTED_FORMAT")
                }
                val fields = review.diffs.mapTo(linkedSetOf()) { it.field }
                val permit = MetadataWritePermit(
                    transactionId = entry.id, sourceId = entry.sourceId,
                    trackId = entry.trackId, uri = entry.uri,
                    originalSha256 = entry.originalSha256,
                    candidateSha256 = entry.stageSha256, stageBytes = entry.stageBytes,
                    profileId = profile.id, evidenceId = profile.capabilityEvidenceId,
                    container = entry.format, revision = revision, fields = fields,
                    explicitUserAction = true,
                )
                val confirmed = finalization.confirm(
                    transactionId = entry.id, target = review.proposed.target,
                    explicitUserAction = true, ownerCurrent = { true },
                    playbackClosed = { true }, permit = permit,
                )
                return@withContext when (confirmed) {
                    is MetadataPostMutationFinalizationCoordinator.Result.PreparedForExternalCas -> {
                        val postEntry = entry.copy(phase = MetadataRecoveryPhase.POSTCHECK_VERIFIED)
                        val indexed = publish(sourceId, trackId, uriString, postEntry, review)
                        if (indexed) {
                            journal.completeVerified(postEntry.id)
                            Outcome.Saved(review.proposed.copy(
                                target = review.proposed.target.copy(
                                    evidenceToken = "me13:sha256:${postEntry.stageSha256}"),
                            ), true)
                        } else {
                            val durable = writeIndexReceipt(postEntry, review)
                            if (!durable) Outcome.RecoveryRequired("INDEX_OBLIGATION_NOT_DURABLE")
                            else Outcome.Saved(review.proposed.copy(
                                target = review.proposed.target.copy(
                                    evidenceToken = "me13:sha256:${postEntry.stageSha256}"),
                            ), false)
                        }
                    }
                    is MetadataPostMutationFinalizationCoordinator.Result.Blocked ->
                        Outcome.RecoveryRequired("${confirmed.step.name}:${confirmed.code}")
                    else -> Outcome.RecoveryRequired("RECEIPT_GATE_NOT_CERTIFIED")
                }
            }
            if (entry.phase != MetadataRecoveryPhase.STAGE_READY)
                return@withContext Outcome.Failed("La transacción no está preparada", true)

            if (!sameSource(uri, entry.originalSha256, expectedBytes))
                return@withContext abort(entry, "El origen cambió antes de escribir")
            if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val consent = runCatching {
                    MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                }.getOrNull()
                return@withContext if (consent == null)
                    Outcome.Blocked("El proveedor no concede escritura exacta")
                else Outcome.ConsentRequired(consent)
            }
            if (!sameSource(uri, entry.originalSha256, expectedBytes))
                return@withContext abort(entry, "El origen cambió antes del consentimiento")

            val permit = MetadataWritePermit(
                transactionId = entry.id, sourceId = entry.sourceId, trackId = entry.trackId,
                uri = entry.uri, originalSha256 = entry.originalSha256,
                candidateSha256 = entry.stageSha256, stageBytes = entry.stageBytes,
                profileId = profile.id, evidenceId = profile.capabilityEvidenceId,
                container = entry.format, revision = revision, fields = fields,
                explicitUserAction = true,
            )
            val mutating = entry.copy(phase = MetadataRecoveryPhase.MUTATING)
            journal.advance(entry, mutating, permit)
            val write = transport.overwriteWithPermit(mutating, candidate, permit)
            when (write) {
                MetadataProviderMutationTransport.Result.BytesSentRequireColdReadback -> {
                    journal.advance(mutating,
                        mutating.copy(phase = MetadataRecoveryPhase.MUTATION_UNKNOWN))
                }
                MetadataProviderMutationTransport.Result.UnknownAfterOpeningSource -> {
                    journal.advance(mutating,
                        mutating.copy(phase = MetadataRecoveryPhase.MUTATION_UNKNOWN))
                    return@withContext Outcome.Failed(
                        "La escritura abrió el origen y quedó en recuperación pendiente", true)
                }
                is MetadataProviderMutationTransport.Result.Refused -> {
                    return@withContext Outcome.Failed(
                        "Escritura rechazada antes de abrir el origen (${write.reason})", true)
                }
            }

            val confirmed = finalization.confirm(
                transactionId = entry.id,
                target = review.proposed.target,
                explicitUserAction = true,
                ownerCurrent = { true },
                playbackClosed = { true },
                permit = permit,
            )
            if (confirmed !is MetadataPostMutationFinalizationCoordinator.Result.PreparedForExternalCas)
                return@withContext if (confirmed is MetadataPostMutationFinalizationCoordinator.Result.Blocked)
                    Outcome.Failed("La finalización física no se pudo demostrar (${confirmed.reason})", true)
                else Outcome.RecoveryRequired("POSTCHECK_NOT_CERTIFIED")
            val postEntry = entry.copy(phase = MetadataRecoveryPhase.POSTCHECK_VERIFIED)

            val indexed = publish(sourceId, trackId, uriString, postEntry, review)
            if (indexed) {
                journal.completeVerified(postEntry.id)
                val coldSnapshot = review.proposed.copy(
                    target = review.proposed.target.copy(
                        evidenceToken = "me13:sha256:${postEntry.stageSha256}",
                    ),
                )
                return@withContext Outcome.Saved(coldSnapshot, true)
            }
            val durable = writeIndexReceipt(postEntry, review)
            if (!durable) return@withContext Outcome.RecoveryRequired(
                "INDEX_OBLIGATION_NOT_DURABLE",
            )
            val coldSnapshot = review.proposed.copy(
                target = review.proposed.target.copy(
                    evidenceToken = "me13:sha256:${postEntry.stageSha256}",
                ),
            )
            Outcome.Saved(coldSnapshot, false)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            val pending = (journal.inspect() as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
            if (pending != null && pending.phase == MetadataRecoveryPhase.STAGE_READY)
                abort(pending, "No se pudo preparar la escritura")
            else Outcome.Failed("La escritura quedó en recuperación pendiente", true)
        } finally {
            sourceCopy.delete()
        }
    }

    private fun writeIndexReceipt(
        entry: MetadataRecoveryJournalV1.Entry,
        review: MetadataReview.Ready,
    ): Boolean {
        return MetadataIndexReconciliationReceipt.write(
            File(context.noBackupFilesDir, "michi-metadata-editor-v1"),
            MetadataIndexReconciliationReceipt(
                transactionId = entry.id, sourceId = entry.sourceId,
                trackId = entry.trackId, uri = entry.uri,
                originalSha256 = entry.originalSha256,
                candidateSha256 = entry.stageSha256,
                candidateBytes = entry.stageBytes, format = entry.format,
            ),
        )
    }

    private fun abort(entry: MetadataRecoveryJournalV1.Entry, reason: String): Outcome {
        if (entry.phase in setOf(MetadataRecoveryPhase.SNAPSHOTTING,
                MetadataRecoveryPhase.BACKUP_DURABLE, MetadataRecoveryPhase.STAGE_READY))
            journal.discardPrepared(entry.id)
        return Outcome.Blocked(reason)
    }

    private fun publish(
        sourceId: String, trackId: String, uri: String,
        entry: MetadataRecoveryJournalV1.Entry, review: MetadataReview.Ready,
    ): Boolean {
        val row: CachedTrack = kotlinx.coroutines.runBlocking {
            trackDao.getTrackBySource(sourceId, trackId)
        } ?: return false
        if (row.filepath != uri || row.size <= 0L) return false
        fun one(field: org.michimusic.core.metadata.MetadataField, fallback: String): String =
            review.proposed.known[field]?.firstOrNull() ?: fallback
        val title = one(org.michimusic.core.metadata.MetadataField.TITLE, row.title)
        val artist = one(org.michimusic.core.metadata.MetadataField.ARTIST, row.artist)
        val album = one(org.michimusic.core.metadata.MetadataField.ALBUM, row.album)
        val genre = one(org.michimusic.core.metadata.MetadataField.GENRE, row.genre)
        return kotlinx.coroutines.runBlocking {
            if (row.verifiedAt > 0L && row.verifiedSha256 == entry.originalSha256)
                trackDao.commitMetadataAfterVerifiedWrite(
                    row.sourceId, row.id, row.filepath,
                    previousSize = row.size, previousVerifiedAt = row.verifiedAt,
                    previousSha256 = row.verifiedSha256,
                    previousTitle = row.title, previousArtist = row.artist,
                    previousAlbum = row.album, previousAlbumArtist = row.albumArtist,
                    previousGenre = row.genre, previousTrackNumber = row.trackNumber,
                    previousYear = row.year,
                    newSize = entry.stageBytes, newTitle = title, newArtist = artist,
                    newAlbum = album, newAlbumArtist = row.albumArtist,
                    newGenre = genre, newTrackNumber = row.trackNumber, newYear = row.year,
                )
            else trackDao.commitMetadataAfterVerifiedWriteUnhashed(
                row.sourceId, row.id, row.filepath,
                previousSize = row.size, previousTitle = row.title,
                previousArtist = row.artist, previousAlbum = row.album,
                previousAlbumArtist = row.albumArtist, previousGenre = row.genre,
                previousTrackNumber = row.trackNumber, previousYear = row.year,
                newSize = entry.stageBytes, newTitle = title, newArtist = artist,
                newAlbum = album, newAlbumArtist = row.albumArtist,
                newGenre = genre, newTrackNumber = row.trackNumber, newYear = row.year,
            )
        }
    }

    private fun MetadataArtworkLyricsProbe.Format.toContainer() =
        when (this) {
            MetadataArtworkLyricsProbe.Format.ID3_V23 ->
                org.michimusic.core.metadata.MetadataAudioContainer.ID3_V23
            MetadataArtworkLyricsProbe.Format.ID3_V24 ->
                org.michimusic.core.metadata.MetadataAudioContainer.ID3_V24
            else -> org.michimusic.core.metadata.MetadataAudioContainer.UNKNOWN
        }

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

    private fun sameSource(uri: Uri, sha: String, bytes: Long): Boolean {
        if (longQuery(uri, OpenableColumns.SIZE) != bytes) return false
        val temp = File(context.cacheDir, "me13-stamp-${UUID.randomUUID()}.bin")
        return try { copy(uri, temp); sha(temp) == sha } catch (_: Exception) { false }
            finally { temp.delete() }
    }

    private fun copy(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) error("SOURCE_UNAVAILABLE")
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) output.write(buffer, 0, n)
                }
                output.fd.sync()
            }
        }
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

    private fun sha(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
