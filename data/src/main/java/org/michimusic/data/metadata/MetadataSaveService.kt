package org.michimusic.data.metadata

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
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
            val libraryChange: org.michimusic.core.metadata.MetadataCommittedLibraryChange? = null,
        ) : Outcome
        data class ConsentRequired(
            val intent: PendingIntent,
            val continuation: MetadataPreparedSaveContinuation,
        ) : Outcome
        data class Blocked(val reason: String) : Outcome
        data class RecoveryRequired(val reason: String) : Outcome
        data class Failed(val reason: String, val needsRecovery: Boolean) : Outcome
    }

    sealed interface ContinuationLookup {
        data class Ready(val continuation: MetadataPreparedSaveContinuation) :
            ContinuationLookup
        data class Refused(val reason: String) : ContinuationLookup
    }

    private val root = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
    private val journal = MetadataRecoveryJournalV1(root)
    private val owners = MetadataRecoveryOwnerStore(root)
    private val preimages = MetadataIndexPreimageStore(root)
    private val transport = MetadataProviderMutationTransport(context)
    private val publication = MetadataFinalizedIndexPublicationCoordinator(context, database)
    private val finalization = MetadataPostMutationFinalizationCoordinator(context)
    private val intents = MetadataCommitIntentStore(journal)
    private val continuationResolver = MetadataPreparedSaveContinuationResolver(
        journal, intents, owners,
    )
    private val committedSnapshots = MetadataVerifiedCommittedSnapshotReader(context)
    private val sourceStamps = MetadataExactSourceStampReader(context)
    private val writePermission = MetadataExactWritePermission(context)
    private fun profileFor(
        provider: MetadataSourceProviderIdentity,
        format: org.michimusic.core.metadata.MetadataAudioContainer,
    ) = org.michimusic.core.metadata.MetadataWriteProfileRegistry
        .forProviderAndContainer(provider.profileProvider, format)

    fun eligibility(sourceId: String, uriString: String): Eligibility {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return Eligibility.UnsupportedProfile("URI no válida")
        if (MetadataSourceProviderIdentity.classify(
                sourceId, uri.scheme, uri.authority,
            ) == null
        ) return Eligibility.UnsupportedProfile("El proveedor local no está certificado")
        val display = query(uri, OpenableColumns.DISPLAY_NAME)
            ?: return Eligibility.UnsupportedProfile("No se puede leer el nombre del archivo")
        val isMp3 = display.endsWith(".mp3", ignoreCase = true)
        val isFlac = display.endsWith(".flac", ignoreCase = true)
        val isM4a = display.endsWith(".m4a", ignoreCase = true)
        if (!isMp3 && !isFlac && !isM4a)
            return Eligibility.UnsupportedProfile("Solo se admiten MP3, FLAC y M4A en este perfil")
        val mime = context.contentResolver.getType(uri)
        if (mime != null && mime !in setOf(
                "audio/mpeg", "audio/mp3", "audio/flac", "audio/x-flac",
                "audio/mp4", "audio/m4a", "audio/x-m4a",
            ))
            return Eligibility.UnsupportedProfile("Contenedor no admitido")
        // Any pending journal forbids a fresh edit until the user resolves it:
        // the physical bytes of the old transaction are still uncertain.
        if (journal.inspect() is MetadataRecoveryJournalV1.Inspection.Pending)
            return Eligibility.RecoveryPending
        return Eligibility.Eligible
    }

    /** Compatibility for existing local MediaStore callers. */
    fun eligibility(uriString: String): Eligibility =
        eligibility(MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID, uriString)

    suspend fun save(
        sourceId: String,
        trackId: String,
        uriString: String,
        expectedBytes: Long,
        reviewInput: MetadataReview.Ready,
        ownerCurrent: () -> Boolean = { false },
        playbackClosed: () -> Boolean = { false },
    ): Outcome {
        return saveBundle(
            sourceId = sourceId,
            trackId = trackId,
            uriString = uriString,
            expectedBytes = expectedBytes,
            targetInput = reviewInput.original.target,
            reviewInput = reviewInput,
            resourceEdits = emptyList(),
            ownerCurrent = ownerCurrent,
            playbackClosed = playbackClosed,
        )
    }

    suspend fun saveBundle(
        sourceId: String,
        trackId: String,
        uriString: String,
        expectedBytes: Long,
        targetInput: org.michimusic.core.metadata.MetadataTarget,
        reviewInput: MetadataReview.Ready?,
        resourceEdits: List<org.michimusic.core.metadata.MetadataSelectiveAssetStage.Edit>,
        ownerCurrent: () -> Boolean = { false },
        playbackClosed: () -> Boolean = { false },
    ): Outcome {
        val key = MetadataSaveLocks.key(sourceId, trackId, uriString)
        val lock = MetadataSaveLocks.lock(key)
        if (!lock.tryLock()) return Outcome.RecoveryRequired("IN_PROGRESS")
        try {
            return saveBody(
                sourceId, trackId, uriString, expectedBytes, targetInput,
                reviewInput, resourceEdits, ownerCurrent, playbackClosed,
            )
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
                if (writePermission.inspect(
                        entry.sourceId, entry.uri, true,
                    ) !is MetadataExactWritePermission.Result.ExistingExactGrant
                )
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

    /**
     * Rebuilds a consent continuation after activity or process recreation.
     * The caller supplies only the retained transaction ID and the screen's
     * expected target identity; all authority comes from durable private state.
     */
    suspend fun reconstructPreparedSave(
        transactionId: String,
        sourceId: String,
        trackId: String,
        uriString: String,
    ): ContinuationLookup = withContext(Dispatchers.IO) {
        when (val resolved = continuationResolver.resolve(
            transactionId, sourceId, trackId, uriString,
        )) {
            is MetadataPreparedSaveContinuationResolver.Result.Ready ->
                ContinuationLookup.Ready(resolved.continuation)
            is MetadataPreparedSaveContinuationResolver.Result.Refused ->
                ContinuationLookup.Refused(resolved.code)
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
            metadataRevision = row?.metadataRevision ?: 0L,
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
            val marker = try { trackDao.getAppliedMetadataTransaction(entry.id) }
                catch (_: Exception) { null }
                ?: return Outcome.RecoveryRequired("APPLIED_MARKER_UNAVAILABLE")
            val libraryChange = org.michimusic.core.metadata.MetadataCommittedLibraryChange(
                transactionId = entry.id,
                sourceId = entry.sourceId,
                trackId = entry.trackId,
                uri = entry.uri,
                changedFields = intent.changes.mapTo(linkedSetOf()) { it.field },
                resourceOperations = intent.resources.mapTo(linkedSetOf()) {
                    org.michimusic.core.metadata.MetadataResourceOperation.of(it)
                },
                snapshot = committed.snapshot,
                previousRevision = marker.originalRevision,
                committedRevision = marker.committedRevision,
                committedBytes = committed.physicalBytes,
                committedSha256 = committed.physicalSha256,
            )
            checkpoints.reach(MetadataSaveCheckpoint.BEFORE_TERMINAL_CLEANUP)
            if (!journal.completeVerified(entry.id))
                return Outcome.RecoveryRequired("INDEXED_CLEANUP_PENDING")
            return Outcome.Saved(committed.snapshot, true,
                committed.physicalBytes, committed.physicalSha256, libraryChange)
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
            MetadataAudioContainer.FLAC -> 1
            MetadataAudioContainer.MP4_M4A -> 1
            else -> return null
        }
        val provider = MetadataSourceProviderIdentity.classify(
            entry.sourceId, Uri.parse(entry.uri).scheme, Uri.parse(entry.uri).authority,
        ) ?: return null
        val profile = org.michimusic.core.metadata.MetadataWriteProfileRegistry
            .forProviderAndContainer(provider.profileProvider, container) ?: return null
        if (intent.provider != provider.documentProvider) return null
        return MetadataWritePermit(
            transactionId = entry.id, sourceId = entry.sourceId,
            trackId = entry.trackId, uri = entry.uri,
            originalSha256 = entry.originalSha256,
            candidateSha256 = entry.stageSha256, stageBytes = entry.stageBytes,
            profileId = profile.id,
            evidenceId = profile.capabilityEvidenceId,
            container = entry.format, revision = revision,
            fields = intent.changes.mapTo(linkedSetOf()) { it.field },
            explicitUserAction = true,
            resources = intent.resources,
        )
    }

    private suspend fun saveBody(
        sourceId: String,
        trackId: String,
        uriString: String,
        expectedBytes: Long,
        targetInput: org.michimusic.core.metadata.MetadataTarget,
        reviewInput: MetadataReview.Ready?,
        resourceEdits: List<org.michimusic.core.metadata.MetadataSelectiveAssetStage.Edit>,
        ownerCurrent: () -> Boolean,
        playbackClosed: () -> Boolean,
    ): Outcome = withContext(Dispatchers.IO) {
        val canonicalTarget = targetInput.copy(
            sourceId = sourceId, trackId = trackId, uri = uriString,
        )
        val review = reviewInput?.copy(
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
        if (review?.diffs.isNullOrEmpty() && resourceEdits.isEmpty())
            return@withContext Outcome.Blocked("No hay cambios efectivos")
        val exactPrepared = (journal.inspect() as?
            MetadataRecoveryJournalV1.Inspection.Pending)?.entry?.let { prepared ->
            prepared.phase == MetadataRecoveryPhase.STAGE_READY &&
                prepared.sourceId == sourceId && prepared.trackId == trackId &&
                prepared.uri == uriString
        } == true
        if (!exactPrepared && eligibility(sourceId, uriString) !is Eligibility.Eligible)
            return@withContext Outcome.Blocked("Perfil de escritura no certificado")
        if (!ownerCurrent() || !playbackClosed())
            return@withContext Outcome.Blocked("OWNER_OR_PLAYBACK_CHANGED")

        val uri = Uri.parse(uriString)
        val provider = MetadataSourceProviderIdentity.classify(
            sourceId, uri.scheme, uri.authority,
        ) ?: return@withContext Outcome.Blocked("El proveedor local no está certificado")
        val stamp = sourceStamps.read(sourceId, uriString, expectedBytes)
            ?: return@withContext Outcome.Blocked("El origen no tiene sello válido")

        val sourceCopy = File(context.cacheDir, "me13-source-${UUID.randomUUID()}.bin")
        var entry: MetadataRecoveryJournalV1.Entry
        var candidate: File
        try {
            copy(uri, sourceCopy)
            val sourceSha = sha(sourceCopy)
            if (sourceCopy.length() != expectedBytes)
                return@withContext Outcome.Blocked("El archivo cambió de tamaño")
            val observed = observeTag(sourceCopy)
                ?: return@withContext Outcome.Blocked("El contenedor de metadatos no es compatible")
            val container = observed.container
            val revision = observed.revision
            if (container == org.michimusic.core.metadata.MetadataAudioContainer.MP4_M4A &&
                resourceEdits.isNotEmpty())
                return@withContext Outcome.Blocked("Los recursos M4A no están certificados")
            val activeProfile = profileFor(provider, container)
                ?: return@withContext Outcome.Blocked("Formato sin perfil de escritura")
            val fields = review?.diffs.orEmpty().mapTo(linkedSetOf()) { it.field }
            val multiValue = review?.diffs.orEmpty()
                .filter { it.after.size > 1 }
                .mapTo(linkedSetOf()) { it.field }
            if (!activeProfile.supports(container, revision, fields, multiValue, false))
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
                val pendingFields = review?.diffs.orEmpty().map { it.field }.sortedBy { it.name }
                val expectedIntent = runCatching {
                    createIntent(
                        entry, canonicalTarget, review, observed, resourceEdits,
                        provider.documentProvider,
                    )
                }.getOrNull() ?: return@withContext Outcome.Blocked(
                    "La revisión no coincide con la intención persistida",
                )
                if (intent.candidateSha256 != entry.stageSha256 ||
                    intent.originalSha256 != entry.originalSha256 ||
                    intent.candidateBytes != entry.stageBytes ||
                    intent.changes.map { it.field } != pendingFields ||
                    intent.resources != expectedIntent.resources ||
                    intent.container != observed.container ||
                    intent.provider != provider.documentProvider ||
                    intent.ownerSha256 !=
                    org.michimusic.core.metadata.MetadataPrivateSessionIdentity.digest(
                        canonicalTarget,
                    ))
                    return@withContext Outcome.Blocked(
                        "La revisión cambió desde la preparación; no se reanuda",
                    )
                val changed = review?.diffs.orEmpty().associateBy { it.field }
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
                    expectedBytes = expectedBytes,
                    expectedModifiedAt = stamp.modifiedAtProviderUnits,
                    format = observed.container.name,
                    ownerEvidenceToken = canonicalTarget.evidenceToken,
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
                val staged = stagePrivate(
                    backup, candidate, sourceSha, expectedBytes, review,
                    observed, resourceEdits,
                )
                entry = backupEntry.copy(phase = MetadataRecoveryPhase.STAGE_READY,
                    stageSha256 = staged.candidateSha256, stageBytes = staged.candidateBytes,
                    format = observed.container.name)
                journal.advance(backupEntry, entry)
                checkpoints.reach(MetadataSaveCheckpoint.AFTER_STAGE_READY)
                val manifest = createIntent(
                    entry, canonicalTarget, review, observed, resourceEdits,
                    provider.documentProvider,
                )
                if (!activeProfile.supports(manifest.resources)) {
                    journal.discardPrepared(entry.id)
                    return@withContext Outcome.Blocked(
                        "Perfil de recursos no certificado",
                    )
                }
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
            when (val grant = writePermission.inspect(sourceId, uriString, true)) {
                is MetadataExactWritePermission.Result.MediaStoreConsentRequired -> {
                    if (!owners.write(entry, canonicalTarget))
                        return@withContext Outcome.Blocked(
                            "No se pudo persistir la evidencia del propietario",
                        )
                    val preparedIntent = (intents.inspect(entry) as?
                        MetadataCommitIntentStore.Inspection.Present)?.receipt
                    val continuation = preparedIntent?.let {
                        MetadataPreparedSaveContinuation.from(entry, it)
                    } ?: return@withContext Outcome.RecoveryRequired(
                        "INTENT_NOT_DURABLE")
                    return@withContext Outcome.ConsentRequired(grant.intent, continuation)
                }
                MetadataExactWritePermission.Result.ExistingExactGrant -> Unit
                MetadataExactWritePermission.Result.NoGrant,
                MetadataExactWritePermission.Result.InvalidTarget ->
                    return@withContext Outcome.Blocked(
                        "El proveedor no concede escritura exacta",
                    )
            }
            if (!sameSource(uri, entry.originalSha256, expectedBytes))
                return@withContext abort(entry, "El origen cambió antes del consentimiento")
            val persisted = (intents.inspect(entry) as? MetadataCommitIntentStore.Inspection.Present)
                ?.receipt ?: return@withContext Outcome.RecoveryRequired("INTENT_NOT_DURABLE")
            if (!activeProfile.supports(persisted.resources))
                return@withContext Outcome.Blocked("Perfil de recursos no certificado")
            val originalSnapshot = review?.original ?: runCatching {
                java.io.FileInputStream(sourceCopy).use { input ->
                    org.michimusic.core.metadata.MetadataColdFieldParser.observe(input.channel)
                        .toSnapshot(canonicalTarget)
                }
            }.getOrElse {
                return@withContext Outcome.Blocked("No se pudo observar el origen")
            }
            return@withContext commitPrepared(
                entry = entry,
                intent = persisted,
                target = canonicalTarget,
                original = originalSnapshot,
                ownerCurrent = ownerCurrent,
                playbackClosed = playbackClosed,
            )
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            android.util.Log.d(
                "MichiSave",
                "prepare_failure=${error.javaClass.simpleName}:${error.message}",
            )
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
                    outcome.committedBytes, outcome.committedSha256,
                    outcome.libraryChange)
            is MetadataRecoveryCoordinator.Outcome.IndexPending ->
                Outcome.Saved(outcome.snapshot, false,
                    outcome.committedBytes, outcome.committedSha256)
            is MetadataRecoveryCoordinator.Outcome.RecoveryRequired ->
                Outcome.RecoveryRequired(outcome.code)
            is MetadataRecoveryCoordinator.Outcome.Blocked ->
                Outcome.RecoveryRequired("RECOVERY_BLOCKED:${outcome.code}")
        }
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

    private fun abort(entry: MetadataRecoveryJournalV1.Entry, reason: String): Outcome {
        if (entry.phase in setOf(MetadataRecoveryPhase.SNAPSHOTTING,
                MetadataRecoveryPhase.BACKUP_DURABLE, MetadataRecoveryPhase.STAGE_READY))
            journal.discardPrepared(entry.id)
        return Outcome.Blocked(reason)
    }

    private data class ObservedTag(
        val container: org.michimusic.core.metadata.MetadataAudioContainer,
        val revision: Int,
        val resources: MetadataArtworkLyricsProbe.Snapshot?,
    )

    private data class StagedCandidate(
        val candidateBytes: Long,
        val candidateSha256: String,
    )

    private fun observeTag(file: File): ObservedTag? = runCatching {
        val report = java.nio.channels.FileChannel.open(
            file.toPath(),
            java.nio.file.StandardOpenOption.READ,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).use(org.michimusic.core.metadata.MetadataColdFieldParser::observe)
        when (report.container) {
            org.michimusic.core.metadata.MetadataPhysicalContainer.ID3_V23 ->
                ObservedTag(
                    org.michimusic.core.metadata.MetadataAudioContainer.ID3_V23,
                    3,
                    MetadataPrivateBundleStage.observeFile(file),
                )
            org.michimusic.core.metadata.MetadataPhysicalContainer.ID3_V24 ->
                ObservedTag(
                    org.michimusic.core.metadata.MetadataAudioContainer.ID3_V24,
                    4,
                    MetadataPrivateBundleStage.observeFile(file),
                )
            org.michimusic.core.metadata.MetadataPhysicalContainer.FLAC ->
                ObservedTag(
                    org.michimusic.core.metadata.MetadataAudioContainer.FLAC,
                    1,
                    MetadataPrivateBundleStage.observeFile(file),
                )
            org.michimusic.core.metadata.MetadataPhysicalContainer.MP4_M4A -> {
                val audioOnly = java.nio.channels.FileChannel.open(
                    file.toPath(),
                    java.nio.file.StandardOpenOption.READ,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                ).use(MetadataM4aAudioOnlyInspector::inspect)
                if (audioOnly !is MetadataM4aAudioOnlyInspector.Result.AudioOnly)
                    error("M4A_NOT_AUDIO_ONLY")
                ObservedTag(
                    org.michimusic.core.metadata.MetadataAudioContainer.MP4_M4A,
                    1,
                    null,
                )
            }
            else -> error("UNSUPPORTED_CONTAINER")
        }
    }.getOrNull()

    private fun stagePrivate(
        backup: File,
        candidate: File,
        sourceSha: String,
        expectedBytes: Long,
        review: MetadataReview.Ready?,
        observed: ObservedTag,
        resourceEdits: List<org.michimusic.core.metadata.MetadataSelectiveAssetStage.Edit>,
    ): StagedCandidate = when (observed.container) {
        org.michimusic.core.metadata.MetadataAudioContainer.MP4_M4A -> {
            require(review != null && resourceEdits.isEmpty()) { "M4A_SCALAR_ONLY" }
            val receipt = MetadataM4aPrivateStage.stage(
                backup,
                candidate,
                MetadataM4aPrivateStage.Request(sourceSha, expectedBytes, review),
            )
            StagedCandidate(receipt.candidateBytes, receipt.candidateSha256)
        }
        else -> {
            val resources = requireNotNull(observed.resources) { "RESOURCE_SNAPSHOT_MISSING" }
            val receipt = MetadataPrivateBundleStage.stage(
                backup,
                candidate,
                MetadataPrivateBundleStage.Request(
                    sourceSha, resources, review, resourceEdits,
                ),
            )
            StagedCandidate(receipt.candidateBytes, receipt.candidateSha256)
        }
    }

    private fun createIntent(
        entry: MetadataRecoveryJournalV1.Entry,
        target: org.michimusic.core.metadata.MetadataTarget,
        review: MetadataReview.Ready?,
        observed: ObservedTag,
        resourceEdits: List<org.michimusic.core.metadata.MetadataSelectiveAssetStage.Edit>,
        provider: MetadataDocumentProvider,
    ): MetadataCommitIntentManifest.Receipt =
        if (observed.container == org.michimusic.core.metadata.MetadataAudioContainer.MP4_M4A) {
            require(review != null && resourceEdits.isEmpty()) { "M4A_SCALAR_ONLY" }
            MetadataCommitIntentManifest.create(
                transactionId = entry.id,
                review = review,
                provider = provider,
                container = observed.container,
                originalBytes = entry.expectedBytes,
                originalSha256 = entry.originalSha256,
                candidateBytes = entry.stageBytes,
                candidateSha256 = entry.stageSha256,
            )
        } else {
            MetadataCommitIntentManifest.createBundle(
                transactionId = entry.id,
                target = target,
                review = review,
                resources = requireNotNull(observed.resources),
                edits = resourceEdits,
                provider = provider,
                container = observed.container,
                originalBytes = entry.expectedBytes,
                originalSha256 = entry.originalSha256,
                candidateBytes = entry.stageBytes,
                candidateSha256 = entry.stageSha256,
            )
        }

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
