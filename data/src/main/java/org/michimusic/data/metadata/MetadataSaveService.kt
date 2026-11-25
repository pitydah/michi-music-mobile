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
class MetadataSaveService internal constructor(
    private val context: Context,
    private val trackDao: TrackDao,
    private val database: MichiDatabase,
    private val checkpoints: MetadataSaveCheckpointHook,
) {
    constructor(context: Context, trackDao: TrackDao, database: MichiDatabase) :
        this(context, trackDao, database, NoMetadataSaveCheckpoint)
    sealed interface Eligibility {
        data object Eligible : Eligibility
        /** A durable journal obligation still owns this or another source. */
        data object RecoveryPending : Eligibility
        data class UnsupportedProfile(val reason: String) : Eligibility
    }

    sealed interface Outcome {
        data class Saved(
            val snapshot: MetadataSnapshot,
            val indexPublished: Boolean,
            val committedBytes: Long = 0L,
            val committedSha256: String = "",
        ) : Outcome
        data class ConsentRequired(
            val intent: PendingIntent,
            val continuation: MetadataPreparedSaveContinuation,
        ) : Outcome
        data class Blocked(val reason: String) : Outcome
        data class RecoveryRequired(val reason: String) : Outcome
        data class Failed(val reason: String, val needsRecovery: Boolean) : Outcome
    }

    private val root = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
    private val journal = MetadataRecoveryJournalV1(root)
    private val owners = MetadataRecoveryOwnerStore(root)
    private val preimages = MetadataIndexPreimageStore(root)
    private val transport = MetadataProviderMutationTransport(context)
    private val publication = MetadataFinalizedIndexPublicationCoordinator(context, database)
    private val finalization = MetadataPostMutationFinalizationCoordinator(context)
    private val intents = MetadataCommitIntentStore(journal)
    private val committedSnapshots = MetadataVerifiedCommittedSnapshotReader(context)
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
    ): Outcome {
        val key = MetadataSaveLocks.key(sourceId, trackId, uriString)
        val lock = MetadataSaveLocks.lock(key)
        if (!lock.tryLock()) return Outcome.RecoveryRequired("IN_PROGRESS")
        try {
            return saveBody(sourceId, trackId, uriString, expectedBytes, reviewInput,
                ownerCurrent, playbackClosed)
        } finally {
            lock.unlock()
            MetadataSaveLocks.release(key, lock)
        }
    }

    /**
     * F1: continue the exact prepared save after Android returned a write
     * grant. No UI review or draft is trusted or required.
     */
    suspend fun continuePreparedSave(
        token: MetadataPreparedSaveContinuation,
        ownerCurrent: () -> Boolean,
        playbackClosed: () -> Boolean,
    ): Outcome {
        val key = MetadataSaveLocks.key(token.sourceId, token.trackId, token.uri)
        val lock = MetadataSaveLocks.lock(key)
        if (!lock.tryLock()) return Outcome.Blocked("IN_PROGRESS")
        try {
            return withContext(Dispatchers.IO) {
                val entry = (journal.inspect() as?
                    MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                    ?: return@withContext Outcome.Blocked("NO_PREPARED_TRANSACTION")
                val intent = (intents.inspect(entry) as?
                    MetadataCommitIntentStore.Inspection.Present)?.receipt
                    ?: return@withContext Outcome.RecoveryRequired("INTENT_NOT_DURABLE")
                if (!token.matches(entry, intent))
                    return@withContext Outcome.RecoveryRequired("CONTINUATION_MISMATCH")
                if (!ownerCurrent() || !playbackClosed())
                    return@withContext Outcome.Blocked("OWNER_OR_PLAYBACK_CHANGED")
                val owner = owners.read(entry)
                    ?: return@withContext Outcome.RecoveryRequired(
                        "OWNER_RECEIPT_MISSING")
                if (owner.ownerSha256 != intent.ownerSha256)
                    return@withContext Outcome.RecoveryRequired(
                        "OWNER_EVIDENCE_MISMATCH")
                val target = org.michimusic.core.metadata.MetadataTarget(
                    entry.sourceId, entry.trackId, entry.uri, owner.evidenceToken)
                val evidence = when (val rebuilt = MetadataDurableEvidenceBuilder.rebuild(
                    journal, entry, intent, target)) {
                    is MetadataDurableEvidenceBuilder.Result.Rebuilt -> rebuilt
                    is MetadataDurableEvidenceBuilder.Result.Refused ->
                        return@withContext Outcome.RecoveryRequired(rebuilt.code)
                }
                if (!sameSource(Uri.parse(entry.uri), entry.originalSha256,
                        entry.expectedBytes))
                    return@withContext Outcome.RecoveryRequired(
                        "ORIGINAL_CHANGED_KEEP_BACKUP")
                if (!hasExactWriteAccess(Uri.parse(entry.uri)))
                    return@withContext Outcome.Blocked("EXACT_GRANT_MISSING")
                return@withContext commitPrepared(
                    entry, intent, target, evidence.original,
                    ownerCurrent, playbackClosed,
                )
            }
        } finally {
            lock.unlock()
            MetadataSaveLocks.release(key, lock)
        }
    }

    private suspend fun commitPrepared(
        entry: MetadataRecoveryJournalV1.Entry,
        intent: MetadataCommitIntentManifest.Receipt,
        target: org.michimusic.core.metadata.MetadataTarget,
        original: MetadataSnapshot,
        ownerCurrent: () -> Boolean,
        playbackClosed: () -> Boolean,
    ): Outcome {
        if (entry.phase != MetadataRecoveryPhase.STAGE_READY)
            return Outcome.RecoveryRequired("NOT_PREPARED")
        if (!journal.privateArtifactsIntact(entry))
            return Outcome.RecoveryRequired("PRIVATE_EVIDENCE_INVALID")
        val row = try { trackDao.getTrackBySource(entry.sourceId, entry.trackId) }
            catch (_: Exception) { return Outcome.RecoveryRequired("PREIMAGE_UNAVAILABLE") }
        val preimage = row?.asMetadataPreimage() ?: MetadataIndexPreimage.NoRow
        if (!preimages.write(entry, preimage))
            return Outcome.RecoveryRequired("PREIMAGE_NOT_DURABLE")
        val expectedIndex = MetadataIndexPublicationPolicy.IndexIdentity(
            sourceId = entry.sourceId, trackId = entry.trackId, uri = entry.uri,
            version = row?.verifiedAt ?: 0L,
            observedSourceSha256 = row?.verifiedSha256.orEmpty(),
        )
        val permit = permitFor(entry, intent)
            ?: return Outcome.Blocked("PERMIT_NOT_BINDABLE")
        checkpoints.reach(MetadataSaveCheckpoint.BEFORE_MUTATING)
        if (!sameSource(Uri.parse(entry.uri), entry.originalSha256,
                entry.expectedBytes))
            return abort(entry, "El origen cambió antes de escribir")
        if (!ownerCurrent() || !playbackClosed())
            return Outcome.Blocked("OWNER_OR_PLAYBACK_CHANGED")
        if (!owners.write(entry, target))
            return Outcome.Blocked("No se pudo persistir la evidencia del propietario")
        val candidate = journal.stage(entry)
        val mutating = entry.copy(phase = MetadataRecoveryPhase.MUTATING)
        journal.advance(entry, mutating, permit)
        checkpoints.reach(MetadataSaveCheckpoint.AFTER_MUTATING_DURABLE)
        if (!ownerCurrent() || !playbackClosed())
            return Outcome.RecoveryRequired("OWNER_OR_PLAYBACK_CHANGED")
        val write = transport.overwriteWithPermit(mutating, candidate, permit)
        when (write) {
            MetadataProviderMutationTransport.Result.BytesSentRequireColdReadback ->
                journal.advance(mutating,
                    mutating.copy(phase = MetadataRecoveryPhase.MUTATION_UNKNOWN))
            MetadataProviderMutationTransport.Result.UnknownAfterOpeningSource -> {
                journal.advance(mutating,
                    mutating.copy(phase = MetadataRecoveryPhase.MUTATION_UNKNOWN))
                return Outcome.Failed(
                    "La escritura abrió el origen y quedó en recuperación pendiente", true)
            }
            is MetadataProviderMutationTransport.Result.Refused ->
                return Outcome.Failed(
                    "Escritura rechazada antes de abrir el origen (${write.reason})", true)
        }
        checkpoints.reach(MetadataSaveCheckpoint.AFTER_PROVIDER_CLOSE)
        checkpoints.reach(MetadataSaveCheckpoint.BEFORE_INDEX_CAS)
        val published = publication.reconcile(
            transactionId = entry.id, original = original,
            expectedIndex = expectedIndex, explicitUserAction = true,
            ownerStillCurrent = ownerCurrent,
            playbackDescriptorClosed = playbackClosed, permit = permit,
        )
        checkpoints.reach(MetadataSaveCheckpoint.AFTER_INDEX_CAS)
        val indexed = published is MetadataFinalizedIndexPublicationCoordinator.Result.IndexedOnly ||
            published is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredGenreIndexed ||
            published is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleIndexed ||
            published is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleAndGenreIndexed
        val committed = when (val read = committedSnapshots.read(
            entry, target, intent,
        )) {
            is MetadataVerifiedCommittedSnapshotReader.Result.Verified -> read
            is MetadataVerifiedCommittedSnapshotReader.Result.Refused ->
                return Outcome.RecoveryRequired("POSTCHECK_SNAPSHOT_UNAVAILABLE")
        }
        if (indexed) {
            if (!journal.completeVerified(entry.id))
                return Outcome.RecoveryRequired("INDEXED_CLEANUP_PENDING")
            return Outcome.Saved(committed.snapshot, true,
                committed.physicalBytes, committed.physicalSha256)
        }
        if (!writeIndexReceipt(entry.copy(phase = MetadataRecoveryPhase.POSTCHECK_VERIFIED),
                target, expectedIndex))
            return Outcome.RecoveryRequired("INDEX_OBLIGATION_NOT_DURABLE")
        return Outcome.Saved(committed.snapshot, false,
            committed.physicalBytes, committed.physicalSha256)
    }

    private fun permitFor(
        entry: MetadataRecoveryJournalV1.Entry,
        intent: MetadataCommitIntentManifest.Receipt,
    ): MetadataWritePermit? {
        val container = runCatching {
            MetadataAudioContainer.valueOf(entry.format)
        }.getOrNull() ?: return null
        if (intent.container != container) return null
        val revision = when (container) {
            MetadataAudioContainer.ID3_V23 -> 3
            MetadataAudioContainer.ID3_V24 -> 4
            else -> return null
        }
        return MetadataWritePermit(
            transactionId = entry.id, sourceId = entry.sourceId,
            trackId = entry.trackId, uri = entry.uri,
            originalSha256 = entry.originalSha256,
            candidateSha256 = entry.stageSha256, stageBytes = entry.stageBytes,
            profileId = profile.id, evidenceId = profile.capabilityEvidenceId,
            container = entry.format, revision = revision,
            fields = intent.changes.mapTo(linkedSetOf()) { it.field },
            explicitUserAction = true,
        )
    }

    private suspend fun saveBody(
        sourceId: String,
        trackId: String,
        uriString: String,
        expectedBytes: Long,
        reviewInput: MetadataReview.Ready,
        ownerCurrent: () -> Boolean,
        playbackClosed: () -> Boolean,
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
                        ownerCurrent, playbackClosed,
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
        val exactPrepared = (journal.inspect() as?
            MetadataRecoveryJournalV1.Inspection.Pending)?.entry?.let { prepared ->
            prepared.phase == MetadataRecoveryPhase.STAGE_READY &&
                prepared.sourceId == sourceId && prepared.trackId == trackId &&
                prepared.uri == uriString
        } == true
        if (!exactPrepared && eligibility(uriString) !is Eligibility.Eligible)
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
                checkpoints.reach(MetadataSaveCheckpoint.AFTER_BACKUP_DURABLE)
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
                checkpoints.reach(MetadataSaveCheckpoint.AFTER_STAGE_READY)
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
                    ownerCurrent, playbackClosed,
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
                else {
                    val preparedIntent = (intents.inspect(entry) as?
                        MetadataCommitIntentStore.Inspection.Present)?.receipt
                    val continuation = preparedIntent?.let {
                        MetadataPreparedSaveContinuation.from(entry, it)
                    } ?: return@withContext Outcome.RecoveryRequired(
                        "INTENT_NOT_DURABLE")
                    Outcome.ConsentRequired(consent, continuation)
                }
            }
            if (!sameSource(uri, entry.originalSha256, expectedBytes))
                return@withContext abort(entry, "El origen cambió antes del consentimiento")

            val expectedRow = try { trackDao.getTrackBySource(sourceId, trackId) }
                catch (_: Exception) {
                    return@withContext Outcome.RecoveryRequired("PREIMAGE_UNAVAILABLE")
                }
            val preimage = expectedRow?.asMetadataPreimage() ?: MetadataIndexPreimage.NoRow
            if (!preimages.write(entry, preimage))
                return@withContext Outcome.RecoveryRequired("PREIMAGE_NOT_DURABLE")
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
            checkpoints.reach(MetadataSaveCheckpoint.BEFORE_MUTATING)
            if (!sameSource(uri, entry.originalSha256, expectedBytes))
                return@withContext abort(entry, "El origen cambió antes de escribir")
            if (!ownerCurrent() || !playbackClosed())
                return@withContext Outcome.Blocked("OWNER_OR_PLAYBACK_CHANGED")
            if (!owners.write(entry, review.original.target))
                return@withContext Outcome.Blocked(
                    "No se pudo persistir la evidencia del propietario")
            val mutating = entry.copy(phase = MetadataRecoveryPhase.MUTATING)
            journal.advance(entry, mutating, permit)
            checkpoints.reach(MetadataSaveCheckpoint.AFTER_MUTATING_DURABLE)
            if (!ownerCurrent() || !playbackClosed())
                return@withContext Outcome.RecoveryRequired("OWNER_OR_PLAYBACK_CHANGED")
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
            checkpoints.reach(MetadataSaveCheckpoint.AFTER_PROVIDER_CLOSE)

            checkpoints.reach(MetadataSaveCheckpoint.BEFORE_INDEX_CAS)
            val published = publication.reconcile(
                transactionId = entry.id,
                original = review.original,
                expectedIndex = expectedIndex,
                explicitUserAction = true,
                ownerStillCurrent = ownerCurrent,
                playbackDescriptorClosed = playbackClosed,
                permit = permit,
            )
            checkpoints.reach(MetadataSaveCheckpoint.AFTER_INDEX_CAS)
            android.util.Log.d("MichiSave", "publication=${publicationCode(published)}")
            val indexed = published is MetadataFinalizedIndexPublicationCoordinator.Result.IndexedOnly ||
                published is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredGenreIndexed ||
                published is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleIndexed ||
                published is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleAndGenreIndexed
            val persisted = (intents.inspect(entry) as? MetadataCommitIntentStore.Inspection.Present)
                ?.receipt ?: return@withContext Outcome.RecoveryRequired("INTENT_NOT_DURABLE")
            val committed = when (val read = committedSnapshots.read(
                entry, review.original.target, persisted,
            )) {
                is MetadataVerifiedCommittedSnapshotReader.Result.Verified -> read
                is MetadataVerifiedCommittedSnapshotReader.Result.Refused ->
                    return@withContext Outcome.RecoveryRequired("POSTCHECK_SNAPSHOT_UNAVAILABLE")
            }
            if (indexed) {
                checkpoints.reach(MetadataSaveCheckpoint.BEFORE_TERMINAL_CLEANUP)
                if (!journal.completeVerified(entry.id))
                    return@withContext Outcome.RecoveryRequired("INDEXED_CLEANUP_PENDING")
                return@withContext Outcome.Saved(committed.snapshot, true,
                    committed.physicalBytes, committed.physicalSha256)
            }
            val durable = writeIndexReceipt(
                entry.copy(phase = MetadataRecoveryPhase.POSTCHECK_VERIFIED),
                review.original.target, expectedIndex,
            )
            if (!durable) return@withContext Outcome.RecoveryRequired(
                "INDEX_OBLIGATION_NOT_DURABLE",
            )
            Outcome.Saved(committed.snapshot, false,
                committed.physicalBytes, committed.physicalSha256)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            android.util.Log.d("MichiSave", "prepare_failure=${error.javaClass.simpleName}")
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
     * ME-13 P0-A: draft-free recovery. The durable owner receipt, journal,
     * intent and private artifacts rebuild the original snapshot; no UI review
     * is required or trusted, and this path never rewrites the source.
     */
    private suspend fun resumePostMutation(
        entry: MetadataRecoveryJournalV1.Entry,
        sourceId: String,
        trackId: String,
        uriString: String,
        ownerCurrent: () -> Boolean,
        playbackClosed: () -> Boolean,
    ): Outcome {
        val coordinator = MetadataRecoveryCoordinator(context, database)
        return when (val outcome = coordinator.resume(
            MetadataRecoveryCoordinator.RecoveryRequest(
                transactionId = entry.id,
                sourceId = sourceId,
                trackId = trackId,
                uri = uriString,
                userRequested = true,
                ownerStillCurrent = ownerCurrent,
                playbackClosed = playbackClosed,
            ),
        )) {
            is MetadataRecoveryCoordinator.Outcome.Indexed ->
                Outcome.Saved(outcome.snapshot, true,
                    outcome.committedBytes, outcome.committedSha256)
            is MetadataRecoveryCoordinator.Outcome.IndexPending ->
                Outcome.Saved(outcome.snapshot, false,
                    outcome.committedBytes, outcome.committedSha256)
            is MetadataRecoveryCoordinator.Outcome.RecoveryRequired ->
                Outcome.RecoveryRequired(outcome.code)
            is MetadataRecoveryCoordinator.Outcome.Blocked ->
                Outcome.RecoveryRequired("RECOVERY_BLOCKED:${outcome.code}")
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

    private fun writeIndexReceipt(
        entry: MetadataRecoveryJournalV1.Entry,
        target: org.michimusic.core.metadata.MetadataTarget,
        expectedIndex: MetadataIndexPublicationPolicy.IndexIdentity?,
    ): Boolean {
        val root = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
        val intent = (intents.inspect(entry) as? MetadataCommitIntentStore.Inspection.Present)
            ?.receipt
        return MetadataIndexObligation.write(
            root = root, entry = entry, target = target,
            intent = intent, expectedIndex = expectedIndex,
        )
    }

    private fun publicationCode(result: MetadataFinalizedIndexPublicationCoordinator.Result): String =
        when (result) {
            is MetadataFinalizedIndexPublicationCoordinator.Result.Blocked ->
                "BLOCKED_${result.reason.name}"
            is MetadataFinalizedIndexPublicationCoordinator.Result.SelectiveRefreshRequired ->
                "SELECTIVE_REFRESH"
            is MetadataFinalizedIndexPublicationCoordinator.Result.IndexedOnly -> "INDEXED"
            is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredGenreIndexed -> "INDEXED"
            is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleIndexed -> "INDEXED"
            is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleAndGenreIndexed ->
                "INDEXED"
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
