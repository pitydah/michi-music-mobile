package org.michimusic.data.metadata

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataOpaquePreservationAudit
import org.michimusic.core.metadata.MetadataPhysicalOutcomePolicy
import org.michimusic.core.metadata.MetadataPreparedCommitSnapshot
import org.michimusic.core.metadata.MetadataProviderRecoveryProfile
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataSourceMutationPolicy
import org.michimusic.data.metadata.MetadataRecoveryJournalV1.Inspection
import org.michimusic.data.metadata.MetadataRecoveryJournalV1.Publication

/**
 * ME-10M: closed, isolated write coordinator for review in OpenCode. No UI
 * invokes this class and both source-mutation certification gates remain false.
 * It reuses the ME-05 journal, not the unrelated V2 preview receipt.
 *
 * Once a provider FD has been opened, even a matching SHA-256 is only a
 * diagnostic observation: never publish Room, automatically restore, or return
 * a "saved" result here. An interrupted operation retains private backups.
 *
 * ME-11R execution shape:
 * 1. [MetadataRecoveryJournalV1.withExclusiveSnapshot] proves transaction identity
 *    and structural eligibility.
 * 2. Provider reads, semantic/opaque audits and consent probing run with NO journal
 *    lock held.
 * 3. [MetadataRecoveryJournalV1.publishIfUnchanged] re-enters the journal and
 *    durably reserves MUTATING before any source FD is opened. That durable phase is
 *    the write reservation: it is not discardable, so no other coordinator can
 *    release or replace the private evidence while the write is in flight.
 * 4. The byte transport runs outside the lock; afterwards a second publication
 *    records MUTATION_UNKNOWN. Losing that publication leaves MUTATING, which is
 *    itself a protected recovery state.
 *
 * Source bytes can never be atomically reserved against an external provider, so the
 * residual window is compensated, not hidden: private backups stay digest-pinned and
 * retained, post-write cold readback classifies the ACTUAL source, and this path never
 * returns a saved result.
 */
internal class MetadataCommitMutationCoordinator(context: Context) {
    data class Request(
        val transactionId: String,
        val sourceId: String,
        val trackId: String,
        val uri: String,
        /** Captured from the exact cold, owner-checked editor read. */
        val expectedTarget: MetadataTarget,
        val explicitUserAction: Boolean,
        val ownerStillCurrent: () -> Boolean,
        val playbackClosed: () -> Boolean,
        /** Never substitute a self-declared app preference for external evidence. */
        val providerProfile: MetadataProviderRecoveryProfile.Evidence,
    )

    enum class Refusal {
        NOT_CERTIFIED, NOT_REQUESTED, JOURNAL_UNREADABLE, NOT_EXACT_TARGET,
        PRIVATE_ARTIFACTS_INVALID, PROVIDER_CHANGED, NO_EXACT_WRITE_GRANT,
        FORMAT_OR_FIELDS_UNCERTIFIED, PROVIDER_NOT_RECOVERABLE, TRANSITION_REJECTED,
        INTENT_MISSING_OR_CORRUPT, INTENT_SCOPE_MISMATCH,
        SEMANTIC_COMPARISON_UNVERIFIED, OPAQUE_PRESERVATION_UNVERIFIED,
    }
    sealed interface Outcome {
        data class Blocked(val reason: Refusal) : Outcome
        data class PermissionRequired(val pending: android.app.PendingIntent) : Outcome
        /** Source has been opened; only recovery inspection may classify it. */
        data class KeepBackupForManualReview(
            val transactionId: String,
            val observed: MetadataPhysicalOutcomePolicy.State,
            /** Source fields were independently inspected, NOT certified saved. */
            val sourceFieldsObserved: Boolean = false,
        ) : Outcome
    }

    private sealed interface Reserved {
        data class Refused(val outcome: Outcome) : Reserved
        data class Advanced(val entry: MetadataRecoveryJournalV1.Entry) : Reserved
    }

    private val journal = MetadataRecoveryJournalV1(
        File(context.noBackupFilesDir, "michi-metadata-editor-v1"))
    private val intents = MetadataCommitIntentStore(journal)
    private val reader = MetadataProviderReadAdapter(context)
    private val permission = MetadataExactWritePermission(context)
    private val transport = MetadataProviderMutationTransport(context)
    private val currentBytes = MetadataCurrentBytesReader(context)
    private val postSource = MetadataPostMutationSourceInspector(context)

    suspend fun attempt(request: Request): Outcome = withContext(Dispatchers.IO) {
        if (!request.explicitUserAction)
            return@withContext Outcome.Blocked(Refusal.NOT_REQUESTED)
        // Two hard-closed production gates, checked BEFORE querying or requesting
        // source write permission and again inside the byte transport itself.
        if (!MetadataCommitCertification.mayMutateOriginal() ||
            !MetadataProviderRecoveryProfile.permitsSourceMutation(request.providerProfile))
            return@withContext Outcome.Blocked(Refusal.NOT_CERTIFIED)
        val job = currentCoroutineContext()[Job]
        try {
            // Phase 1: brief exclusion, local evidence only.
            val snapshot = journal.withExclusiveSnapshot { inspection ->
                preflight(request, inspection)
            }
            val entry = when (val reserved = snapshot.value) {
                is Reserved.Refused -> return@withContext reserved.outcome
                is Reserved.Advanced -> reserved.entry
            }
            job?.ensureActive()
            // Phase 2: every provider read and every local audit runs unlocked.
            val observed = try {
                reader.openForRead(entry.sourceId, entry.uri, entry.expectedBytes)
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) {
                return@withContext Outcome.Blocked(Refusal.PROVIDER_CHANGED)
            }
            if (observed.stamp.modifiedAt != entry.expectedModifiedAt ||
                !reader.verifyBytes(observed, entry.expectedBytes, entry.originalSha256) {
                    job?.ensureActive()
                }) return@withContext Outcome.Blocked(Refusal.PROVIDER_CHANGED)
            val profileProviderMatches = when (observed.provider) {
                MetadataDocumentProvider.MEDIASTORE -> request.providerProfile.provider ==
                    MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO
                MetadataDocumentProvider.SAF -> request.providerProfile.provider ==
                    MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT
                else -> false
            }
            if (!profileProviderMatches)
                return@withContext Outcome.Blocked(Refusal.PROVIDER_NOT_RECOVERABLE)
            // A V1 stage with no owner+field manifest (including every
            // pre-ME-10N journal) is strictly NOT eligible for mutation.
            val intent = when (val inspected = intents.inspect(entry)) {
                is MetadataCommitIntentStore.Inspection.Present -> inspected.receipt
                else -> return@withContext Outcome.Blocked(Refusal.INTENT_MISSING_OR_CORRUPT)
            }
            val intentMatch = MetadataCommitIntentManifest.verify(intent,
                entry.id, request.expectedTarget, observed.provider,
                containerOf(entry), entry.expectedBytes, entry.originalSha256,
                entry.stageBytes, entry.stageSha256)
            if (intentMatch != MetadataCommitIntentManifest.Verdict.BoundForExternalReview)
                return@withContext Outcome.Blocked(Refusal.INTENT_SCOPE_MISMATCH)
            // ME-10O: physically read the EXACT private backup and candidate,
            // verify their hashes using the same read-only descriptors, and
            // compare before/after tags to journal-bound field intentions.
            // Unverified third-party / opaque frames still require separate
            // evidence; a compared report is never certification of writes.
            val semantic = MetadataPreparedSemanticInspector.inspectFiles(
                journal.backup(entry), journal.stage(entry), intent) { job?.ensureActive() }
            if (semantic !is MetadataPreparedSemanticInspector.Inspection.Compared)
                return@withContext Outcome.Blocked(Refusal.SEMANTIC_COMPARISON_UNVERIFIED)
            // ME-10P: compare the actual unedited opaque ID3 frames / FLAC
            // metadata blocks, plus a streaming SHA-256 over every audio byte.
            // Conservative: MBID TXXX and described COMM/USLT edits will be
            // refused until a per-entry proof supports those identity types.
            val opaqueAudit = MetadataOpaquePreservationAudit.compare(
                journal.backup(entry), journal.stage(entry), intent.container,
                intent.changes.mapTo(linkedSetOf()) { it.field }, intent.resources)
            if (opaqueAudit !is MetadataOpaquePreservationAudit.Verdict.Compared)
                return@withContext Outcome.Blocked(Refusal.OPAQUE_PRESERVATION_UNVERIFIED)
            // No permission prompt for an old/unbound or semantically invalid transaction.
            // This probe performs binder/provider IPC, so it MUST stay in this
            // unlocked phase; the journal contract forbids holding the exclusion
            // across provider IPC.
            val permissionResult = permission.inspect(entry.sourceId,
                entry.uri, request.explicitUserAction)
            if (permissionResult is MetadataExactWritePermission.Result.MediaStoreConsentRequired)
                return@withContext Outcome.PermissionRequired(permissionResult.intent)
            val exactGrant =
                permissionResult == MetadataExactWritePermission.Result.ExistingExactGrant
            job?.ensureActive()
            // Phase 3: reopen the journal and durably reserve the source write.
            // Only local, non-suspending evidence is re-proved here; the grant was
            // read in this unlocked phase and cannot be atomically fenced afterwards.
            val reservation = journal.publishIfUnchanged(snapshot.generation, entry) {
                reserve(request, entry, observed.provider, exactGrant)
            }
            val (mutating, reservedGeneration) = when (val publication = reservation) {
                // Journal or private evidence changed while the source was read.
                is Publication.Stale -> return@withContext Outcome.Blocked(
                    Refusal.TRANSITION_REJECTED)
                is Publication.Published -> when (val value = publication.value) {
                    is Reserved.Refused -> return@withContext value.outcome
                    is Reserved.Advanced -> value.entry to publication.generation
                }
            }
            // Phase 4: the reservation is durable; the provider write runs unlocked.
            val output = transport.overwrite(mutating, journal.stage(entry),
                request.providerProfile) { job?.ensureActive() }
            // Even after an unsuccessful send, the provider may be partly
            // truncated. Never return to STAGE_READY or delete backups.
            val mutationUnknown = mutating.copy(phase = MetadataRecoveryPhase.MUTATION_UNKNOWN)
            // Phase 5: record the uncertain outcome. A lost publication leaves
            // MUTATING, which is already a protected recovery phase.
            journal.publishIfUnchanged(reservedGeneration, mutating) {
                try { journal.advance(mutating, mutationUnknown) }
                catch (_: Exception) { /* MUTATING is itself a protected phase. */ }
                true
            }
            val classified = when (output) {
                MetadataProviderMutationTransport.Result.BytesSentRequireColdReadback -> {
                    val readback = currentBytes.read(entry.sourceId, entry.uri,
                        maxOf(entry.expectedBytes,entry.stageBytes)) { job?.ensureActive() }
                    if (readback is MetadataCurrentBytesReader.Result.Read)
                        MetadataPhysicalOutcomePolicy.classify(
                            MetadataPhysicalOutcomePolicy.Expected(entry.expectedBytes,
                                entry.originalSha256, entry.stageBytes, entry.stageSha256),
                            readback.fingerprint)
                    else MetadataPhysicalOutcomePolicy.State.READBACK_UNAVAILABLE
                }
                else -> MetadataPhysicalOutcomePolicy.State.UNKNOWN_BYTES
            }
            // A matching digest alone is insufficient for semantic success.
            // ME-10T cold-reads the ACTUAL provider source and checks each
            // modified field against the journal-bound candidate intent.
            // Even when both proofs match, artifacts remain protected and
            // no index publication, UI SAVED state or cleanup is allowed.
            val readback = if (classified ==
                MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED)
                postSource.inspect(entry.sourceId, entry.uri, request.expectedTarget, intent,
                    userRequested=request.explicitUserAction,
                    ownerCurrent={ request.ownerStillCurrent() && request.playbackClosed() },
                    checkCancellation={ job?.ensureActive() })
            else null
            Outcome.KeepBackupForManualReview(entry.id, classified,
                sourceFieldsObserved=readback is MetadataPostMutationSourceInspector.Result.Inspected)
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) {
            // No rollback is safe to infer after an exception. An existing
            // MUTATING/MUTATION_UNKNOWN journal remains the recovery authority.
            Outcome.Blocked(Refusal.TRANSITION_REJECTED)
        }
    }

    /** Local, non-suspending checks that may reject before any provider I/O. */
    private fun preflight(request: Request, inspection: Inspection): Reserved {
        val entry = (inspection as? Inspection.Pending)?.entry
            ?: return Reserved.Refused(Outcome.Blocked(Refusal.JOURNAL_UNREADABLE))
        if (entry.id != request.transactionId || entry.sourceId != request.sourceId ||
            entry.trackId != request.trackId || entry.uri != request.uri ||
            request.expectedTarget.sourceId != entry.sourceId ||
            request.expectedTarget.trackId != entry.trackId ||
            request.expectedTarget.uri != entry.uri)
            return Reserved.Refused(Outcome.Blocked(Refusal.NOT_EXACT_TARGET))
        val proof = proofOf(entry)
        if (proof !is MetadataPreparedCommitSnapshot.Decision.Candidate ||
            !journal.privateArtifactsIntact(entry))
            return Reserved.Refused(Outcome.Blocked(Refusal.PRIVATE_ARTIFACTS_INVALID))
        if (!request.ownerStillCurrent() || !request.playbackClosed())
            return Reserved.Refused(Outcome.Blocked(Refusal.PROVIDER_CHANGED))
        return Reserved.Advanced(entry)
    }

    /**
     * Re-proves every precondition that is local and cheap, then persists MUTATING.
     * The byte transport runs after this method returns, outside the journal lock.
     *
     * The exact write grant is passed in from the unlocked phase. Re-probing it here
     * would be provider IPC under the exclusion, which this journal forbids. A grant
     * revoked in the snapshot→publish window cannot be atomically fenced; the
     * durable MUTATING reservation, the transport's own permission failure and the
     * cold-readback classification keep that case fail-closed (never a success).
     */
    private fun reserve(
        request: Request,
        entry: MetadataRecoveryJournalV1.Entry,
        provider: MetadataDocumentProvider,
        exactGrant: Boolean,
    ): Reserved {
        val proof = proofOf(entry)
        if (proof !is MetadataPreparedCommitSnapshot.Decision.Candidate ||
            !journal.privateArtifactsIntact(entry))
            return Reserved.Refused(Outcome.Blocked(Refusal.PRIVATE_ARTIFACTS_INVALID))
        // A V1 stage with no owner+field manifest is strictly NOT eligible.
        val intent = (intents.inspect(entry) as? MetadataCommitIntentStore.Inspection.Present)
            ?.receipt ?: return Reserved.Refused(Outcome.Blocked(Refusal.INTENT_MISSING_OR_CORRUPT))
        if (MetadataCommitIntentManifest.verify(intent, entry.id, request.expectedTarget,
                provider, proof.evidence.format, entry.expectedBytes, entry.originalSha256,
                entry.stageBytes, entry.stageSha256) !=
            MetadataCommitIntentManifest.Verdict.BoundForExternalReview)
            return Reserved.Refused(Outcome.Blocked(Refusal.INTENT_SCOPE_MISMATCH))
        // The grant was observed in the unlocked phase. Provider revocation after
        // that observation is compensated by the durable MUTATING reservation and
        // the fail-closed post-write readback, never treated as success.
        val grant = exactGrant
        if (!grant) return Reserved.Refused(Outcome.Blocked(Refusal.NO_EXACT_WRITE_GRANT))
        // Exact field inventory exists now. External format×field×provider
        // certification does not. This explicit gate remains FALSE, as do the
        // independently checked provider-recovery and commit gates.
        val preflight = MetadataSourceMutationPolicy.evaluate(
            MetadataSourceMutationPolicy.Input(
                explicitUserAction=request.explicitUserAction,
                phase=entry.phase,
                transactionMatches=true,
                exactTargetMatches=request.ownerStillCurrent(),
                sourceBytesReverified=true,
                privateArtifactsVerified=true,
                playbackClosed=request.playbackClosed(),
                exactWriteGrantPresent=grant,
                provider=provider,
                format=proof.evidence.format,
                formatAndFieldsCertified=intent.sourceWriteCertified,
                transportCertified=MetadataCommitCertification.mayMutateOriginal(),
                providerRecoveryCertified=MetadataProviderRecoveryProfile.permitsSourceMutation(
                    request.providerProfile),
            ))
        if (preflight != MetadataSourceMutationPolicy.Verdict.PrerequisitesObserved)
            return Reserved.Refused(Outcome.Blocked(Refusal.FORMAT_OR_FIELDS_UNCERTIFIED))
        // This branch is currently unreachable due to the format manifest above
        // and both hard-closed certification gates.
        if (!request.ownerStillCurrent() || !request.playbackClosed())
            return Reserved.Refused(Outcome.Blocked(Refusal.PROVIDER_CHANGED))
        val mutating = entry.copy(phase=MetadataRecoveryPhase.MUTATING)
        // MUST persist before opening a source FD: this is the write reservation.
        journal.advance(entry, mutating)
        return Reserved.Advanced(mutating)
    }

    private fun proofOf(entry: MetadataRecoveryJournalV1.Entry) =
        MetadataPreparedCommitSnapshot.evaluate(
            MetadataPreparedCommitSnapshot.Input(entry.id, entry.phase,
                entry.sourceId, entry.trackId, entry.uri,
                entry.expectedBytes, entry.originalSha256,
                entry.stageBytes, entry.stageSha256, entry.format))

    private fun containerOf(entry: MetadataRecoveryJournalV1.Entry) =
        proofOf(entry).let {
            (it as? MetadataPreparedCommitSnapshot.Decision.Candidate)?.evidence?.format
                ?: error("PREPARED_SNAPSHOT_NOT_CANDIDATE")
        }
}
