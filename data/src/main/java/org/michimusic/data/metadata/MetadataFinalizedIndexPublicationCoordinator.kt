package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataScalarIndexRefreshPolicy
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.TrackDao

/**
 * ME-11D: end-to-end, CLOSED post-write reconciliation wiring.
 * After a separately certified physical write and its journal postcheck,
 * this derives the index delta from a FRESH source read and invokes the
 * actual Room CAS adapter. Any race/refusal leaves recovery files protected.
 * Without certification, the very first branch prevents even a cache read.
 */
internal class MetadataFinalizedIndexPublicationCoordinator(
    context:Context, private val database:MichiDatabase,
) {
    private val dao:TrackDao=database.trackDao()
    private val journal=MetadataRecoveryJournalV1(
        File(context.noBackupFilesDir,"michi-metadata-editor-v1"))
    private val intentStore=MetadataCommitIntentStore(journal)
    private val preimages=MetadataIndexPreimageStore(
        File(context.noBackupFilesDir,"michi-metadata-editor-v1"))
    private val finalizer=MetadataPostMutationFinalizationCoordinator(context)
    private val bytes=MetadataCurrentBytesReader(context)
    private val stamps=MetadataExactSourceStampReader(context)
    private fun index(permit: MetadataWritePermit): MetadataIndexCasCoordinator {
        val needsEvidence = permit.fields.any {
            it in MetadataEmbeddedEvidence.nonProjectedFields
        }
        val port = if (!needsEvidence) {
            MetadataTrackDaoCasPort(dao, permit, preimages)
        } else {
            MetadataTrackDaoCasPort(dao, permit, preimages,
                evidenceFor = { delta ->
                    val stamp = stamps.read(delta.target.sourceId,
                        delta.target.uri, delta.candidateBytes)
                    if (stamp == null) null
                    else MetadataEmbeddedEvidence.fromDelta(
                        delta, stamp.modifiedAtProviderUnits)
                })
        }
        return MetadataIndexCasCoordinator(port)
    }
    private val genreIndex=MetadataIndexCasCoordinator(
        MetadataSelectiveGenreCasPort(context,dao,preimages))
    private val peopleIndex=MetadataIndexCasCoordinator(
        MetadataSelectivePeopleCasPort(context,database,preimages))
    private val combinedIndex=MetadataIndexCasCoordinator(
        MetadataSelectiveCombinedCasPort(context,database,preimages))
    private val resolver=context.contentResolver

    enum class Reason {
        NOT_CERTIFIED, ACTION_REQUIRED, WRONG_OWNER, JOURNAL_MISSING,
        INTENT_UNVERIFIED, POSTCHECK_NOT_READY, SOURCE_CHANGED, FIELDS_UNVERIFIED,
        CAS_REJECTED,
    }
    sealed interface Result {
        data class Blocked(val reason: Reason) : Result
        /** UI / library reload is a separate explicit action, NOT promised here. */
        data class IndexedOnly(val transactionId: String) : Result
        /** The structured genre evidence and scalar hint were committed together.
         * Room invalidation notifies the existing GenreLibraryViewModel Flow. */
        data class StructuredGenreIndexed(val transactionId: String) : Result
        /** A first-value display hint and both exact person lists share one Room CAS. */
        data class StructuredPeopleIndexed(val transactionId: String) : Result
        /** GENRE + ARTIST/ALBUM_ARTIST exact lists published in one Room transaction. */
        data class StructuredPeopleAndGenreIndexed(val transactionId: String) : Result
        /** Exact source verified, but scalar Room cannot represent all values.
         * A user-triggered scoped library re-read is required. No cache is mutated. */
        data class SelectiveRefreshRequired(val transactionId: String,
            val fields: Set<org.michimusic.core.metadata.MetadataField>) : Result
    }
    suspend fun reconcile(
        transactionId:String,original:MetadataSnapshot,
        expectedIndex:MetadataIndexPublicationPolicy.IndexIdentity,
        explicitUserAction:Boolean,ownerStillCurrent:()->Boolean,
        playbackDescriptorClosed:()->Boolean,
        permit: MetadataWritePermit,
    ):Result = withContext(Dispatchers.IO) {
        if (permit.transactionId != transactionId)
            return@withContext Result.Blocked(Reason.NOT_CERTIFIED)
        if (!explicitUserAction)
            return@withContext Result.Blocked(Reason.ACTION_REQUIRED)
        if (!original.tagsVerified || original.target.sourceId!=expectedIndex.sourceId ||
            original.target.trackId!=expectedIndex.trackId ||
            original.target.uri!=expectedIndex.uri || !ownerStillCurrent() ||
            !playbackDescriptorClosed())
            return@withContext Result.Blocked(Reason.WRONG_OWNER)
        val finalized=finalizer.confirm(transactionId,original.target,true,
            ownerStillCurrent,playbackDescriptorClosed,permit)
        // Diagnostics only: the finalization step and refusal code. Never the URI,
        // the transaction id or any tag value (contract P0-B.7).
        if (finalized !is MetadataPostMutationFinalizationCoordinator.Result
                .PreparedForExternalCas) {
            val blocked=finalized as? MetadataPostMutationFinalizationCoordinator.Result.Blocked
            android.util.Log.d("MetadataFinalize",
                "blocked=${blocked?.step?.name ?: "UNKNOWN"}:${blocked?.code ?: "NO_CODE"}")
        }
        val certified=(finalized as?
            MetadataPostMutationFinalizationCoordinator.Result.PreparedForExternalCas)?.receipt
            ?: return@withContext Result.Blocked(Reason.POSTCHECK_NOT_READY)
        // Brief exclusion: capture the durable generation and the local evidence.
        // The Room CAS below is the atomic publication step; this snapshot proves
        // which durable journal state the derived delta belongs to.
        val snapshot=try { journal.withExclusiveSnapshot { inspection ->
            val entry=(inspection as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                ?: return@withExclusiveSnapshot null
            if (!permit.matches(entry)) return@withExclusiveSnapshot null
            if (entry.phase!=MetadataRecoveryPhase.POSTCHECK_VERIFIED ||
                entry.id!=transactionId || entry.trackId!=expectedIndex.trackId ||
                entry.sourceId!=expectedIndex.sourceId || entry.uri!=expectedIndex.uri ||
                !(entry.originalSha256==expectedIndex.observedSourceSha256 ||
                    (expectedIndex.version==0L &&
                        expectedIndex.observedSourceSha256.isEmpty())) ||
                !journal.privateArtifactsIntact(entry)) return@withExclusiveSnapshot null
            val intent=(intentStore.inspect(entry) as?
                MetadataCommitIntentStore.Inspection.Present)?.receipt
                ?: return@withExclusiveSnapshot null
            if (!MetadataCommitIntentManifest.valid(intent) ||
                intent.transactionId!=entry.id ||
                intent.originalSha256!=entry.originalSha256 ||
                intent.candidateSha256!=entry.stageSha256) return@withExclusiveSnapshot null
            entry to intent
        }} catch (cancel:CancellationException) { throw cancel }
          catch (_:Exception) { null }
            ?: return@withContext Result.Blocked(Reason.JOURNAL_MISSING)
        val (entry,intent)=snapshot.value
            ?: return@withContext Result.Blocked(Reason.JOURNAL_MISSING)
        if (expectedIndex.version == 0L && expectedIndex.observedSourceSha256.isEmpty()) {
            // Empty cache hash is not an original-source proof: that proof
            // comes exclusively from this retained, validated journal entry.
            val cached = dao.getTrackBySource(expectedIndex.sourceId, expectedIndex.trackId)
                ?: return@withContext Result.Blocked(Reason.CAS_REJECTED)
            val unverified = org.michimusic.core.metadata.MetadataUnverifiedIndexRowPolicy.inspect(
                org.michimusic.core.metadata.MetadataUnverifiedIndexRowPolicy.Row(
                    cached.sourceId, cached.id, cached.filepath, cached.size,
                    cached.verifiedSize, cached.verifiedAt, cached.verifiedSha256,
                    cached.verifiedAlgorithm), expectedIndex, entry.originalSha256)
            if (unverified != org.michimusic.core.metadata.MetadataUnverifiedIndexRowPolicy.Verdict.MayAttemptExternalCas)
                return@withContext Result.Blocked(Reason.CAS_REJECTED)
        }
        // Content is the authority here (contract P0-B.6 / T26): the exact committed
        // candidate SHA and physical length, read twice with a settled announced
        // stamp. MediaStore's announced SIZE may still lag; that bookkeeping lag is
        // reported by the reader and never substitutes for the candidate hash.
        val first=bytes.readCommittedCandidate(entry.sourceId,entry.uri,entry.stageBytes,
            entry.stageSha256)
            as? MetadataCurrentBytesReader.PostCommitRead.Exact
            ?: return@withContext Result.Blocked(Reason.SOURCE_CHANGED)
        if (first.actualSha256!=entry.stageSha256 || first.actualSize!=entry.stageBytes)
            return@withContext Result.Blocked(Reason.SOURCE_CHANGED)
        val parsed=try {
            val descriptor=resolver.openFileDescriptor(Uri.parse(entry.uri),"r")
                ?: return@withContext Result.Blocked(Reason.FIELDS_UNVERIFIED)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                MetadataColdFieldParser.observe(stream.channel)
            }
        } catch (cancel:CancellationException) { throw cancel }
          catch (_:Exception) { return@withContext Result.Blocked(Reason.FIELDS_UNVERIFIED) }
        if (!ownerStillCurrent() || !playbackDescriptorClosed())
            return@withContext Result.Blocked(Reason.WRONG_OWNER)
        val second=bytes.readCommittedCandidate(entry.sourceId,entry.uri,entry.stageBytes,
            entry.stageSha256)
            as? MetadataCurrentBytesReader.PostCommitRead.Exact
            ?: return@withContext Result.Blocked(Reason.SOURCE_CHANGED)
        if (second.actualSize!=first.actualSize ||
            second.actualSha256!=first.actualSha256)
            return@withContext Result.Blocked(Reason.SOURCE_CHANGED)
        // The journal, the private evidence and the intent binding are re-proved
        // unchanged after both cold reads. Room's conditional UPDATE remains the
        // atomic publication step; this fence proves the evidence the delta derives
        // from. A stale generation is a refusal, never a silent success.
        val fenced=try { journal.publishIfUnchanged(snapshot.generation,entry) {
            val live=(intentStore.inspect(entry) as?
                MetadataCommitIntentStore.Inspection.Present)?.receipt
            journal.privateArtifactsIntact(entry) && live!=null &&
                MetadataCommitIntentManifest.valid(live) && live.transactionId==entry.id &&
                live.originalSha256==entry.originalSha256 &&
                live.candidateSha256==entry.stageSha256
        }} catch (cancel:CancellationException) { throw cancel }
          catch (_:Exception) { null }
        if (fenced !is MetadataRecoveryJournalV1.Publication.Published || fenced.value!=true)
            return@withContext Result.Blocked(Reason.JOURNAL_MISSING)
        val delta=MetadataSourceIndexDelta.propose(original,intent,
            second.actualSha256,parsed,candidateBytes=second.actualSize)
        if (delta !is MetadataSourceIndexDelta.Result.PreparedForExternalCas)
            return@withContext Result.Blocked(Reason.FIELDS_UNVERIFIED)
        when (val refresh=MetadataScalarIndexRefreshPolicy.evaluate(delta.delta)) {
            is MetadataScalarIndexRefreshPolicy.Verdict.SelectiveRefreshRequired -> {
                // Structured GENRE and structured PEOPLE use separate, exact,
                // owner-scoped CAS ports. Do not flatten mixed unsupported fields.
                val genreOnly = refresh.reason == MetadataScalarIndexRefreshPolicy.Reason.AMBIGUOUS_MULTIVALUE &&
                    refresh.fields == setOf(org.michimusic.core.metadata.MetadataField.GENRE) &&
                    org.michimusic.core.metadata.MetadataStructuredGenreRefreshPolicy.prepare(
                        delta.delta) is org.michimusic.core.metadata.MetadataStructuredGenreRefreshPolicy.Result.Prepared
                val peopleOnly = refresh.reason == MetadataScalarIndexRefreshPolicy.Reason.AMBIGUOUS_MULTIVALUE &&
                    refresh.fields.isNotEmpty() && setOf(
                        org.michimusic.core.metadata.MetadataField.ARTIST,
                        org.michimusic.core.metadata.MetadataField.ALBUM_ARTIST).containsAll(refresh.fields) &&
                    org.michimusic.core.metadata.MetadataStructuredPeopleRefreshPolicy.inspect(
                        delta.delta) is org.michimusic.core.metadata.MetadataStructuredPeopleRefreshPolicy.Result.Prepared
                val combined = !genreOnly && !peopleOnly &&
                    org.michimusic.core.metadata.MetadataStructuredCombinedRefreshPolicy.inspect(
                        delta.delta) is org.michimusic.core.metadata.MetadataStructuredCombinedRefreshPolicy.Result.Prepared
                if (!genreOnly && !peopleOnly && !combined)
                    return@withContext Result.SelectiveRefreshRequired(entry.id,refresh.fields)
                val chosen = when {
                    genreOnly -> genreIndex
                    peopleOnly -> peopleIndex
                    else -> combinedIndex
                }
                val cas = chosen.attempt(expectedIndex,delta,
                    MetadataRecoveryPhase.POSTCHECK_VERIFIED,certified,playbackDescriptorClosed(),
                    ownerStillCurrent, permit)
                return@withContext when (cas) {
                    is MetadataIndexCasCoordinator.Result.AppliedToIndexOnly ->
                        when {
                            genreOnly -> Result.StructuredGenreIndexed(entry.id)
                            peopleOnly -> Result.StructuredPeopleIndexed(entry.id)
                            else -> Result.StructuredPeopleAndGenreIndexed(entry.id)
                        }
                    is MetadataIndexCasCoordinator.Result.Blocked ->
                        Result.Blocked(Reason.CAS_REJECTED)
                }
            }
            MetadataScalarIndexRefreshPolicy.Verdict.MayTryRoomCas -> Unit
        }
        return@withContext when(index(permit).attempt(expectedIndex,delta,
            MetadataRecoveryPhase.POSTCHECK_VERIFIED,certified,playbackDescriptorClosed(),
            ownerStillCurrent, permit)) {
            is MetadataIndexCasCoordinator.Result.AppliedToIndexOnly -> Result.IndexedOnly(entry.id)
            is MetadataIndexCasCoordinator.Result.Blocked -> Result.Blocked(Reason.CAS_REJECTED)
        }
    }
}
