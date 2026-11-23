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
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataPrivateBundleStage
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.TrackDao

/** ME-13: the single Save facade. All source changes are reserved by Journal V1. */
class MetadataSaveService(
    private val context: Context,
    private val trackDao: TrackDao,
    database: MichiDatabase,
) {
    sealed interface Eligibility {
        data object Eligible : Eligibility
        /** A durable journal obligation still owns this or another source. */
        data object RecoveryPending : Eligibility
        data class UnsupportedProfile(val reason: String) : Eligibility
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
    private val publication = MetadataFinalizedIndexPublicationCoordinator(context, database)
    private val finalization = MetadataPostMutationFinalizationCoordinator(context)
    private val intents = MetadataCommitIntentStore(journal)
    private val profile = MediaStoreMp3TextProfile.profile

    fun eligibility(uriString: String): Eligibility {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return Eligibility.UnsupportedProfile("URI no válida")
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY)
            return Eligibility.UnsupportedProfile("El archivo no pertenece a MediaStore")
        val display = query(uri, OpenableColumns.DISPLAY_NAME)
            ?: return Eligibility.UnsupportedProfile("No se puede leer el nombre del archivo")
        if (!display.endsWith(".mp3", ignoreCase = true))
            return Eligibility.UnsupportedProfile("Solo se admiten archivos MP3 en este perfil")
        val mime = context.contentResolver.getType(uri)
        if (mime != null && mime != "audio/mpeg" && mime != "audio/mp3")
            return Eligibility.UnsupportedProfile("Contenedor no admitido")
        // Any pending journal forbids a fresh edit until the user resolves it:
        // the physical bytes of the old transaction are still uncertain.
        if (journal.inspect() is MetadataRecoveryJournalV1.Inspection.Pending)
            return Eligibility.RecoveryPending
        return Eligibility.Eligible
    }

    suspend fun save(
        sourceId: String,
        trackId: String,
        uriString: String,
        expectedBytes: Long,
        reviewInput: MetadataReview.Ready,
        ownerCurrent: () -> Boolean = { false },
        playbackClosed: () -> Boolean = { false },
    ): Outcome = withContext(Dispatchers.IO) {
        val canonicalTarget = reviewInput.proposed.target.copy(
            sourceId = sourceId, trackId = trackId, uri = uriString,
        )
        val review = reviewInput.copy(
            original = reviewInput.original.copy(target = canonicalTarget),
            proposed = reviewInput.proposed.copy(target = canonicalTarget),
        )
        // A source already in MUTATING/UNKNOWN/VERIFIED must be finalized, not
        // processed as a fresh write. Its physical size can differ from the old
        // expectedBytes, so perform this branch BEFORE source-copy size checks.
        when (val recovery = journal.inspect()) {
            is MetadataRecoveryJournalV1.Inspection.Blocked ->
                return@withContext Outcome.RecoveryRequired("JOURNAL_UNREADABLE")
            is MetadataRecoveryJournalV1.Inspection.Pending -> {
                if (MetadataSaveRecoveryRouting.requiresReadOnlyFinalization(recovery.entry.phase)) {
                    return@withContext resumePostMutation(
                        recovery.entry, sourceId, trackId, uriString,
                        review, ownerCurrent, playbackClosed,
                    )
                }
            }
            MetadataRecoveryJournalV1.Inspection.Clear -> Unit
        }
        if (expectedBytes <= 0L) return@withContext Outcome.Blocked("Tamaño de origen no válido")
        if (review.diffs.isEmpty()) return@withContext Outcome.Blocked("No hay cambios efectivos")
        if (review.diffs.any { it.field !in profile.supportedFields })
            return@withContext Outcome.Blocked("Este campo no está en el perfil certificado")
        if (review.diffs.any { it.after.size > 1 })
            return@withContext Outcome.Blocked("Varios valores no admitidos en este perfil")
        if (eligibility(uriString) !is Eligibility.Eligible)
            return@withContext Outcome.Blocked("Perfil de escritura no certificado")
        if (!ownerCurrent() || !playbackClosed())
            return@withContext Outcome.Blocked("OWNER_OR_PLAYBACK_CHANGED")

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

            var pending = journal.inspect()
            if (pending is MetadataRecoveryJournalV1.Inspection.Blocked)
                return@withContext Outcome.Failed("Journal no legible", true)
            // PREPARED-ONLY: no candidate/intent exists and no source bytes changed.
            // A previous attempt without an intent cannot be "resumed"; safely
            // discard its private-only artifacts and start a fresh preparation.
            if (pending is MetadataRecoveryJournalV1.Inspection.Pending &&
                MetadataSaveRecoveryRouting.mayDiscardPrivatePreparation(
                    pending.entry.phase,
                    pending.entry.sourceId, pending.entry.trackId, pending.entry.uri,
                    sourceId, trackId, uriString,
                )) {
                if (!journal.discardPrepared(pending.entry.id))
                    return@withContext Outcome.RecoveryRequired("PREPARED_CLEANUP_FAILED")
                pending = journal.inspect()
            }
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
                    ownerEvidenceToken = review.original.target.evidenceToken,
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
            if (MetadataSaveRecoveryRouting.requiresReadOnlyFinalization(entry.phase)) {
                return@withContext resumePostMutation(
                    entry, sourceId, trackId, uriString,
                    review, ownerCurrent, playbackClosed,
                )
            }
            if (entry.phase != MetadataRecoveryPhase.STAGE_READY)
                return@withContext Outcome.Failed("La transacción no está preparada", true)

            if (!sameSource(uri, entry.originalSha256, expectedBytes))
                return@withContext abort(entry, "El origen cambió antes de escribir")
            if (!hasExactWriteAccess(uri)) {
                val consent = runCatching {
                    MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                }.getOrNull()
                return@withContext if (consent == null)
                    Outcome.Blocked("El proveedor no concede escritura exacta")
                else Outcome.ConsentRequired(consent)
            }
            if (!sameSource(uri, entry.originalSha256, expectedBytes))
                return@withContext abort(entry, "El origen cambió antes del consentimiento")

            val expectedRow = trackDao.getTrackBySource(sourceId, trackId)
            val expectedIndex = MetadataIndexPublicationPolicy.IndexIdentity(
                sourceId = sourceId, trackId = trackId, uri = uriString,
                version = expectedRow?.verifiedAt ?: 0L,
                observedSourceSha256 = expectedRow?.verifiedSha256.orEmpty(),
            )
            val permit = MetadataWritePermit(
                transactionId = entry.id, sourceId = entry.sourceId, trackId = entry.trackId,
                uri = entry.uri, originalSha256 = entry.originalSha256,
                candidateSha256 = entry.stageSha256, stageBytes = entry.stageBytes,
                profileId = profile.id, evidenceId = profile.capabilityEvidenceId,
                container = entry.format, revision = revision, fields = fields,
                explicitUserAction = true,
            )
            if (!ownerCurrent() || !playbackClosed())
                return@withContext Outcome.Blocked("OWNER_OR_PLAYBACK_CHANGED")
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

            val published = publication.reconcile(
                transactionId = entry.id,
                original = review.original,
                expectedIndex = expectedIndex,
                explicitUserAction = true,
                ownerStillCurrent = ownerCurrent,
                playbackDescriptorClosed = playbackClosed,
                permit = permit,
            )
            val indexed = published is MetadataFinalizedIndexPublicationCoordinator.Result.IndexedOnly ||
                published is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredGenreIndexed ||
                published is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleIndexed ||
                published is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleAndGenreIndexed
            val coldSnapshot = review.proposed.copy(
                target = review.proposed.target.copy(
                    evidenceToken = "me13:sha256:${entry.stageSha256}",
                ),
            )
            if (indexed) {
                if (!journal.completeVerified(entry.id))
                    return@withContext Outcome.RecoveryRequired("INDEXED_CLEANUP_PENDING")
                return@withContext Outcome.Saved(coldSnapshot, true)
            }
            val durable = writeIndexReceipt(
                entry.copy(phase = MetadataRecoveryPhase.POSTCHECK_VERIFIED), review,
                expectedIndex,
            )
            if (!durable) return@withContext Outcome.RecoveryRequired(
                "INDEX_OBLIGATION_NOT_DURABLE",
            )
            Outcome.Saved(coldSnapshot, false)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            android.util.Log.d("MichiSave", "prepare_failure=${error.javaClass.simpleName}:${error.message}")
            val pending = (journal.inspect() as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
            if (pending != null && pending.sourceId == sourceId &&
                pending.trackId == trackId && pending.uri == uriString &&
                pending.phase in setOf(
                    MetadataRecoveryPhase.SNAPSHOTTING,
                    MetadataRecoveryPhase.BACKUP_DURABLE,
                    MetadataRecoveryPhase.STAGE_READY,
                )) {
                if (journal.discardPrepared(pending.id))
                    Outcome.Blocked("No se pudo preparar la escritura: ${error.javaClass.simpleName}")
                else Outcome.RecoveryRequired("PREPARED_CLEANUP_FAILED")
            } else Outcome.Failed("La escritura quedó en recuperación pendiente", true)
        } finally {
            sourceCopy.delete()
        }
    }

    /**
     * Read-only recovery of a journaled physical write. Never opens the source
     * for mutation, never stages a new candidate, and never uses stale file size
     * as a veto before verifying the candidate that may already be installed.
     * A changed review cannot authorize reusing the journal-bound intent.
     */
    private suspend fun resumePostMutation(
        entry: MetadataRecoveryJournalV1.Entry,
        sourceId: String,
        trackId: String,
        uriString: String,
        review: MetadataReview.Ready,
        ownerCurrent: () -> Boolean,
        playbackClosed: () -> Boolean,
    ): Outcome {
        // The screen's cached size can be either the old size or the new one.
        // For an already-mutated source, only the journal's original/candidate
        // fingerprints, manifest and actual readback may decide recovery.
        if (entry.sourceId != sourceId || entry.trackId != trackId ||
            entry.uri != uriString)
            return Outcome.RecoveryRequired("OTHER_OR_CHANGED_TRANSACTION")
        if (!ownerCurrent() || !playbackClosed())
            return Outcome.RecoveryRequired("OWNER_OR_PLAYBACK_CHANGED")
        val container = runCatching { MetadataAudioContainer.valueOf(entry.format) }
            .getOrNull() ?: return Outcome.RecoveryRequired("INVALID_JOURNAL_CONTAINER")
        if (container !in setOf(MetadataAudioContainer.ID3_V23,
                MetadataAudioContainer.ID3_V24))
            return Outcome.RecoveryRequired("UNSUPPORTED_RECOVERY_PROFILE")
        val intent = (intents.inspect(entry) as?
            MetadataCommitIntentStore.Inspection.Present)?.receipt
            ?: return Outcome.RecoveryRequired("INTENT_NOT_DURABLE")
        val reviewIntent = runCatching {
            MetadataCommitIntentManifest.create(
                transactionId = entry.id,
                review = review,
                provider = MetadataDocumentProvider.MEDIASTORE,
                container = container,
                originalBytes = entry.expectedBytes,
                originalSha256 = entry.originalSha256,
                candidateBytes = entry.stageBytes,
                candidateSha256 = entry.stageSha256,
            )
        }.getOrNull() ?: return Outcome.RecoveryRequired("REVIEW_NOT_REPRODUCIBLE")
        if (reviewIntent != intent)
            return Outcome.RecoveryRequired("REVIEW_CHANGED_KEEP_BACKUP")
        val fields = review.diffs.mapTo(linkedSetOf()) { it.field }
        val permit = MetadataWritePermit(
            transactionId = entry.id, sourceId = entry.sourceId,
            trackId = entry.trackId, uri = entry.uri,
            originalSha256 = entry.originalSha256,
            candidateSha256 = entry.stageSha256, stageBytes = entry.stageBytes,
            profileId = profile.id, evidenceId = profile.capabilityEvidenceId,
            container = entry.format,
            revision = if (container == MetadataAudioContainer.ID3_V23) 3 else 4,
            fields = fields, explicitUserAction = true,
        )
        if (!permit.matches(entry))
            return Outcome.RecoveryRequired("PERMIT_MISMATCH")
        val postEntry = entry.copy(phase = MetadataRecoveryPhase.POSTCHECK_VERIFIED)
        val expectedIndex = currentIndexIdentity(entry)
        val coldSnapshot = review.proposed.copy(
            target = review.proposed.target.copy(
                evidenceToken = "me13:sha256:${postEntry.stageSha256}",
            ),
        )
        if (expectedIndex == null) {
            // A historical MUTATING/MUTATION_UNKNOWN journal must be re-proved
            // against the physical source before any "saved" claim, even when
            // the Room row cannot be used for an exact CAS.
            val confirmed = finalization.confirm(
                transactionId = entry.id,
                target = review.proposed.target,
                explicitUserAction = true,
                ownerCurrent = ownerCurrent,
                playbackClosed = playbackClosed,
                permit = permit,
            )
            if (confirmed !is MetadataPostMutationFinalizationCoordinator.Result
                    .PreparedForExternalCas) {
                val blocked = confirmed as? MetadataPostMutationFinalizationCoordinator.Result.Blocked
                return Outcome.RecoveryRequired(
                    "${blocked?.step?.name ?: "POSTCHECK"}:${blocked?.code ?: "NOT_VERIFIED"}",
                )
            }
            if (alreadyApplied(entry, review)) {
                if (!journal.completeVerified(entry.id))
                    return Outcome.RecoveryRequired("INDEXED_CLEANUP_PENDING")
                return Outcome.Saved(coldSnapshot, true)
            }
            // The exact pre-write row is absent or still conflicting. Preserve a
            // durable, non-actionable receipt; the on-demand reconciler and the
            // recovery panel keep the obligation visible without a blind CAS.
            if (!writeIndexReceipt(postEntry, review, null))
                return Outcome.RecoveryRequired("INDEX_OBLIGATION_NOT_DURABLE")
            return Outcome.Saved(coldSnapshot, false)
        }
        val published = publication.reconcile(
            transactionId = postEntry.id,
            original = review.original,
            expectedIndex = expectedIndex,
            explicitUserAction = true,
            ownerStillCurrent = ownerCurrent,
            playbackDescriptorClosed = playbackClosed,
            permit = permit,
        )
        val indexed = published is MetadataFinalizedIndexPublicationCoordinator.Result.IndexedOnly ||
            published is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredGenreIndexed ||
            published is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleIndexed ||
            published is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleAndGenreIndexed
        if (indexed) {
            if (!journal.completeVerified(postEntry.id))
                return Outcome.RecoveryRequired("INDEXED_CLEANUP_PENDING")
            return Outcome.Saved(coldSnapshot, true)
        }
        // Physical bytes are already postcheck-verified, so a CAS refusal is an
        // index obligation, never a claim that the audio is damaged.
        if (!writeIndexReceipt(postEntry, review, expectedIndex))
            return Outcome.RecoveryRequired("INDEX_OBLIGATION_NOT_DURABLE")
        if (published is MetadataFinalizedIndexPublicationCoordinator.Result.Blocked)
            return Outcome.RecoveryRequired("PUBLICATION:${published.reason.name}")
        return Outcome.Saved(coldSnapshot, false)
    }

    private suspend fun alreadyApplied(
        entry: MetadataRecoveryJournalV1.Entry,
        review: MetadataReview.Ready,
    ): Boolean {
        val row = trackDao.getTrackBySource(entry.sourceId, entry.trackId) ?: return false
        if (row.filepath != entry.uri || row.size != entry.stageBytes) return false
        if (row.verifiedAt != 0L || row.verifiedSha256.isNotEmpty() ||
            row.verifiedSize != 0L || row.verifiedAlgorithm.isNotEmpty()) return false
        if (review.diffs.isEmpty()) return false
        return review.diffs.all { diff ->
            val expected = diff.after.firstOrNull().orEmpty()
            when (diff.field) {
                MetadataField.TITLE -> row.title == expected
                MetadataField.ARTIST -> row.artist == expected
                MetadataField.ALBUM -> row.album == expected
                MetadataField.GENRE -> row.genre == expected
                else -> false
            }
        }
    }

    /**
     * A URI grant is not the only proof of exact write access: MediaStore items
     * created by this app are writable through the owning provider without a
     * persisted grant. Probe with a non-truncating "rw" descriptor before asking
     * the user for a system consent dialog.
     */
    private fun hasExactWriteAccess(uri: Uri): Boolean {
        if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private suspend fun currentIndexIdentity(
        entry: MetadataRecoveryJournalV1.Entry,
    ): MetadataIndexPublicationPolicy.IndexIdentity? {
        val row = trackDao.getTrackBySource(entry.sourceId, entry.trackId) ?: return null
        if (row.filepath != entry.uri || row.size != entry.expectedBytes) return null
        val verified = row.verifiedAt > 0L && row.verifiedSha256 == entry.originalSha256 &&
            row.verifiedSize == row.size && row.verifiedAlgorithm.lowercase() == "sha256"
        val unhashed = row.verifiedAt == 0L && row.verifiedSha256.isEmpty() &&
            row.verifiedSize == 0L && row.verifiedAlgorithm.isEmpty()
        if (!verified && !unhashed) return null
        return MetadataIndexPublicationPolicy.IndexIdentity(
            sourceId = row.sourceId, trackId = row.id, uri = row.filepath,
            version = row.verifiedAt, observedSourceSha256 = row.verifiedSha256,
        )
    }

    private fun writeIndexReceipt(
        entry: MetadataRecoveryJournalV1.Entry,
        review: MetadataReview.Ready,
        expectedIndex: MetadataIndexPublicationPolicy.IndexIdentity?,
    ): Boolean {
        val root = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
        val intent = (intents.inspect(entry) as? MetadataCommitIntentStore.Inspection.Present)
            ?.receipt
        val token = review.original.target.evidenceToken
        val ownerBound = intent != null && token.isNotBlank() && runCatching {
            org.michimusic.core.metadata.MetadataPrivateSessionIdentity.digest(
                review.original.target) == intent.ownerSha256
        }.getOrDefault(false)
        // V2 is written only with the exact pre-write row identity and the exact
        // owner token bound into the durable intent. A legacy V1 receipt is
        // retained for readback but can never authorize a Room CAS.
        if (expectedIndex != null && ownerBound && token.isNotBlank()) {
            val v2 = MetadataIndexReconciliationReceipt(
                transactionId = entry.id, sourceId = entry.sourceId,
                trackId = entry.trackId, uri = entry.uri,
                originalSha256 = entry.originalSha256,
                candidateSha256 = entry.stageSha256,
                candidateBytes = entry.stageBytes, format = entry.format,
                ownerEvidenceToken = token,
                previousVerifiedAt = expectedIndex.version,
                previousVerifiedSha256 = expectedIndex.observedSourceSha256,
                previousSize = entry.expectedBytes,
            )
            if (v2.actionable &&
                MetadataIndexReconciliationReceipt.write(root, v2)) return true
        }
        return MetadataIndexReconciliationReceipt.write(
            root,
            MetadataIndexReconciliationReceipt(
                transactionId = entry.id, sourceId = entry.sourceId,
                trackId = entry.trackId, uri = entry.uri,
                originalSha256 = entry.originalSha256,
                candidateSha256 = entry.stageSha256,
                candidateBytes = entry.stageBytes, format = entry.format,
                version = 1,
            ),
        )
    }

    private fun abort(entry: MetadataRecoveryJournalV1.Entry, reason: String): Outcome {
        if (entry.phase in setOf(MetadataRecoveryPhase.SNAPSHOTTING,
                MetadataRecoveryPhase.BACKUP_DURABLE, MetadataRecoveryPhase.STAGE_READY))
            journal.discardPrepared(entry.id)
        return Outcome.Blocked(reason)
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
