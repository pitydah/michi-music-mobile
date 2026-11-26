package org.michimusic.data.metadata

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataCapabilityRegistry
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataApeStageRewriter
import org.michimusic.core.metadata.MetadataRiffStageRewriter
import org.michimusic.core.metadata.MetadataAiffStageRewriter
import org.michimusic.core.metadata.MetadataOggStageRewriter
import org.michimusic.core.metadata.MetadataOggFlexibleStageRewriter
import org.michimusic.core.metadata.MetadataMp4StageRewriter
import org.michimusic.core.metadata.MetadataMp4TailStageRewriter
import org.michimusic.core.metadata.MetadataFlacStageRewriter
import org.michimusic.core.metadata.MetadataId3StageRewriter
import org.michimusic.core.metadata.MetadataPrepareDecision
import org.michimusic.core.metadata.MetadataPreparePolicy
import org.michimusic.core.metadata.MetadataPrepareRejection
import org.michimusic.core.metadata.MetadataPrepareTicket
import org.michimusic.core.metadata.MetadataProviderRecoveryProfile
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataWriteProfileRegistry
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.MetadataEditorEvidenceResult

/**
 * ME-05: REAL preparation, NO source commit. Saves an exact private original
 * snapshot and a transformed private stage, both with SHA-256 and a crash-aware
 * journal. No MediaStore/SAF write calls, permission requests, Room mutations,
 * scans, periodic work, network calls or Media3 modifications are made here.
 */
class MetadataPreparationCoordinator(
    context: Context,
    private val metadataRepository: ExtendedTrackMetadataRepository,
) {
    data class Request(
        val ticket: MetadataPrepareTicket,
        val review: MetadataReview,
        val playbackDescriptorClosed: () -> Boolean,
        val stillOwns: () -> Boolean,
    )
    sealed interface Outcome {
        data class Prepared(
            val transactionId: String,
            val container: MetadataAudioContainer,
            val provider: MetadataDocumentProvider,
            val originalBytes: Long,
            val candidateBytes: Long,
            val originalSha256: String,
            val candidateSha256: String,
            /** No physical-write capability exists in ME-05. */
            val canCommit: Boolean = false,
        ) : Outcome
        data class Refused(val reason: MetadataPrepareRejection) : Outcome
    }
    sealed interface Pending {
        data object Clear : Pending
        data class PreparedOrInterrupted(val transactionId: String, val phase: MetadataRecoveryPhase) : Pending
        data object CorruptJournalBlocked : Pending
    }

    private val journal = MetadataRecoveryJournalV1(File(context.noBackupFilesDir,
        "michi-metadata-editor-v1"))
    private val provider = MetadataProviderReadAdapter(context)
    private val intents = MetadataCommitIntentStore(journal)

    suspend fun inspectPending(): Pending = withContext(Dispatchers.IO) {
        try {
            journal.withExclusiveSnapshot { inspection ->
                when (inspection) {
                    MetadataRecoveryJournalV1.Inspection.Clear -> Pending.Clear
                    is MetadataRecoveryJournalV1.Inspection.Pending ->
                        if (journal.privateArtifactsIntact(inspection.entry))
                            Pending.PreparedOrInterrupted(inspection.entry.id, inspection.entry.phase)
                        else Pending.CorruptJournalBlocked
                    is MetadataRecoveryJournalV1.Inspection.Blocked -> Pending.CorruptJournalBlocked
                }
            }.value
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { Pending.CorruptJournalBlocked }
    }

    /** A pre-mutation journal may be discarded by ID. NEVER restores/overwrites media. */
    suspend fun discardPrepared(transactionId: String, userConfirmed: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (!userConfirmed) return@withContext false
            try { journal.exclusive { journal.discardPrepared(transactionId) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { false }
        }

    suspend fun prepare(request: Request): Outcome = withContext(Dispatchers.IO) {
        // Derive live safety signals: no caller-supplied true may replace these checks.
        val ticket=request.ticket.copy(
            playbackClosed=request.playbackDescriptorClosed(),
            ownerStillCurrent=request.stillOwns(),
        )
        val decision=MetadataPreparePolicy.evaluate(ticket,request.review)
        if (decision is MetadataPrepareDecision.Refused)
            return@withContext Outcome.Refused(decision.reason)
        // Re-read the actual cold owner evidence before acquiring the process lock;
        // the later full-byte digest recheck closes the time-of-check/time-of-use gap.
        val fresh = try {
            metadataRepository.loadVerifiedEditorSnapshotResult(ticket.sourceId,
                ticket.trackId,ticket.uri,ticket.expectedBytes)
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { return@withContext Outcome.Refused(MetadataPrepareRejection.PROVIDER_UNREADABLE) }
        val ready=request.review as? MetadataReview.Ready
            ?: return@withContext Outcome.Refused(MetadataPrepareRejection.UNVERIFIED_REVIEW)
        if (fresh !is MetadataEditorEvidenceResult.Verified || fresh.snapshot != ready.original)
            return@withContext Outcome.Refused(MetadataPrepareRejection.PROVIDER_CHANGED)
        val activeJob = currentCoroutineContext()[Job]
        // Phase 1: brief exclusion. Prove the journal is clear and ownership still
        // holds; all provider work below deliberately runs without the journal lock.
        val observation = try {
            journal.withExclusiveSnapshot { inspection ->
                when {
                    inspection != MetadataRecoveryJournalV1.Inspection.Clear ->
                        Outcome.Refused(MetadataPrepareRejection.RECOVERY_PENDING)
                    !request.stillOwns() -> Outcome.Refused(MetadataPrepareRejection.OWNER_CHANGED)
                    !request.playbackDescriptorClosed() ->
                        Outcome.Refused(MetadataPrepareRejection.ACTIVE_PLAYBACK)
                    !MetadataPreparePolicy.hasPrivateSpace(journalRoot.usableSpace,
                        ticket.expectedBytes) ->
                        Outcome.Refused(MetadataPrepareRejection.NO_PRIVATE_SPACE)
                    else -> null
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) {
              return@withContext Outcome.Refused(MetadataPrepareRejection.JOURNAL_UNAVAILABLE)
          }
        observation.value?.let { return@withContext it }
        try {
            prepareUnlocked(request, ticket, ready, observation.generation) {
                activeJob?.ensureActive()
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { Outcome.Refused(MetadataPrepareRejection.JOURNAL_UNAVAILABLE) }
    }

    private class PreparationFailure(val reason: MetadataPrepareRejection) : Exception(reason.name)

    private suspend fun prepareUnlocked(request: Request, ticket: MetadataPrepareTicket,
        ready: MetadataReview.Ready, expectedGeneration: MetadataRecoveryJournalV1.Generation,
        checkCancellation: () -> Unit): Outcome {
        val access=try {provider.openForRead(ticket.sourceId,ticket.uri,ticket.expectedBytes)}
            catch (refused: MetadataProviderReadAdapter.Refused) {
                val reason=when(refused.code){
                    MetadataProviderReadAdapter.Code.ACCESS_DENIED -> MetadataPrepareRejection.READ_PERMISSION_DENIED
                    MetadataProviderReadAdapter.Code.SOURCE_CHANGED -> MetadataPrepareRejection.PROVIDER_CHANGED
                    MetadataProviderReadAdapter.Code.MISSING_OR_INVALID_STAMP -> MetadataPrepareRejection.INVALID_PROVIDER_STAMP
                    else -> MetadataPrepareRejection.UNSUPPORTED_PROVIDER
                }
                return Outcome.Refused(reason)
            }
        // Sniff CURRENT provider bytes BEFORE copying a potentially large file.
        // Extension/MIME/Track.format never grant staging rights.
        val anticipated = try {
            provider.readStream(access) { input -> sniffHeader(input) }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { return Outcome.Refused(MetadataPrepareRejection.PROVIDER_UNREADABLE) }
        val initialCapability=MetadataCapabilityRegistry.reviewStagingCandidate(
            ready,anticipated,access.provider,formatSniffed=true)
        if (initialCapability !is MetadataCapabilityRegistry.StageReview.Candidate)
            return Outcome.Refused(MetadataPrepareRejection.STAGING_NOT_ALLOWED)
        if (provider.stamp(access) != access.stamp)
            return Outcome.Refused(MetadataPrepareRejection.PROVIDER_CHANGED)
        if (!MetadataPreparePolicy.hasPrivateSpace(journalRoot.usableSpace,ticket.expectedBytes))
            return Outcome.Refused(MetadataPrepareRejection.NO_PRIVATE_SPACE)
        val id=UUID.randomUUID().toString()
        val initial=MetadataRecoveryJournalV1.Entry(id,MetadataRecoveryPhase.SNAPSHOTTING,
            ticket.sourceId,ticket.trackId,ticket.uri,access.stamp.bytes,access.stamp.modifiedAt)
        var current=initial
        var generation=expectedGeneration
        try {
            // Durable reservation: the SNAPSHOTTING record is created only if the
            // journal is still the exact generation observed before provider work.
            val started=journal.publishIfUnchanged(generation) {
                if (!request.stillOwns())
                    return@publishIfUnchanged Outcome.Refused(MetadataPrepareRejection.OWNER_CHANGED)
                if (!request.playbackDescriptorClosed())
                    return@publishIfUnchanged Outcome.Refused(MetadataPrepareRejection.ACTIVE_PLAYBACK)
                journal.start(initial)
                null
            }
            when (started) {
                is MetadataRecoveryJournalV1.Publication.Stale ->
                    return Outcome.Refused(MetadataPrepareRejection.RECOVERY_PENDING)
                is MetadataRecoveryJournalV1.Publication.Published -> {
                    started.value?.let { return it }
                    generation=started.generation
                }
            }
            val original=journal.backup(initial)
            val originalSha=captureToPrivateFile(access,original,ticket.expectedBytes,checkCancellation)
            if (provider.stamp(access)!=access.stamp)
                throw PreparationFailure(MetadataPrepareRejection.PROVIDER_CHANGED)
            val backed=current.copy(phase=MetadataRecoveryPhase.BACKUP_DURABLE,originalSha256=originalSha)
            val backupPublication=journal.publishIfUnchanged(generation,current) {
                journal.advance(current,backed)
                backed
            }
            when (backupPublication) {
                is MetadataRecoveryJournalV1.Publication.Stale ->
                    throw PreparationFailure(MetadataPrepareRejection.RECOVERY_PENDING)
                is MetadataRecoveryJournalV1.Publication.Published -> {
                    current=backupPublication.value
                    generation=backupPublication.generation
                }
            }
            checkCancellation()
            val container=sniff(original)
            if (container != anticipated)
                throw PreparationFailure(MetadataPrepareRejection.PROVIDER_CHANGED)
            val staged=journal.stage(current)
            when(container) {
                MetadataAudioContainer.FLAC -> MetadataFlacStageRewriter.stage(original,staged,
                    ready,ticket.expectedBytes)
                MetadataAudioContainer.ID3_V23,MetadataAudioContainer.ID3_V24 ->
                    MetadataId3StageRewriter.stage(original,staged,ready,ticket.expectedBytes)
                MetadataAudioContainer.WAVPACK, MetadataAudioContainer.APE ->
                    MetadataApeStageRewriter.stage(original,staged,ready,ticket.expectedBytes)
                MetadataAudioContainer.WAV ->
                    MetadataRiffStageRewriter.stage(original,staged,ready,ticket.expectedBytes)
                MetadataAudioContainer.AIFF ->
                    MetadataAiffStageRewriter.stage(original,staged,ready,ticket.expectedBytes)
                MetadataAudioContainer.OGG_OPUS, MetadataAudioContainer.OGG_VORBIS -> {
                    // The legacy path is byte-position preserving. Only explicit
                    // length/count refusals are eligible for a second PRIVATE writer.
                    // No broad retry on CRC, ambiguous page layout or ownership errors.
                    try {
                        MetadataOggStageRewriter.stage(original,staged,ready,ticket.expectedBytes)
                    } catch (refused: MetadataOggStageRewriter.Refused) {
                        if (refused.code != MetadataOggStageRewriter.Code.LENGTH_CHANGED &&
                            refused.code != MetadataOggStageRewriter.Code.UNSUPPORTED_CHANGE) throw refused
                        MetadataOggFlexibleStageRewriter.stage(original,staged,ready,ticket.expectedBytes)
                    }
                }
                MetadataAudioContainer.MP4_M4A -> {
                    try {
                        MetadataMp4StageRewriter.stage(original,staged,ready,ticket.expectedBytes)
                    } catch (refused: MetadataMp4StageRewriter.Refused) {
                        // Size changes are safe only when an independently checked
                        // terminal moov follows all media data. This remains PRIVATE.
                        if (refused.code != MetadataMp4StageRewriter.Code.SIZE_CHANGE) throw refused
                        MetadataMp4TailStageRewriter.stage(original,staged,ready,ticket.expectedBytes)
                    }
                }
                else -> throw PreparationFailure(MetadataPrepareRejection.UNSUPPORTED_CONTAINER)
            }
            checkCancellation()
            val stageBytes=staged.length()
            if (stageBytes<=0) throw PreparationFailure(MetadataPrepareRejection.STAGING_FAILED)
            if (!request.stillOwns()) throw PreparationFailure(MetadataPrepareRejection.OWNER_CHANGED)
            if (!request.playbackDescriptorClosed())
                throw PreparationFailure(MetadataPrepareRejection.ACTIVE_PLAYBACK)
            if (!provider.verifyBytes(access,ticket.expectedBytes,originalSha,checkCancellation))
                throw PreparationFailure(MetadataPrepareRejection.PROVIDER_CHANGED)
            val digest=hashFile(staged)
            val finished=current.copy(phase=MetadataRecoveryPhase.STAGE_READY,
                stageSha256=digest,stageBytes=stageBytes,format=container.name)
            // Commit intent must be durable BEFORE stage-ready is visible. A
            // crash before this record leaves BACKUP_DURABLE (discardable),
            // not an unbound write candidate. This record is NOT certification.
            val intent = MetadataCommitIntentManifest.create(id, ready,
                access.provider, container, ticket.expectedBytes, originalSha,
                stageBytes, digest,
                MetadataWriteProfileRegistry.activeForProviderAndContainer(
                    when (access.provider) {
                        MetadataDocumentProvider.MEDIASTORE ->
                            MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO
                        MetadataDocumentProvider.SAF ->
                            MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT
                        MetadataDocumentProvider.REMOTE,
                        MetadataDocumentProvider.UNKNOWN ->
                            MetadataProviderRecoveryProfile.Kind.UNKNOWN_OR_REMOTE
                    },
                    container,
                ),
            )
            // The intent must become durable BEFORE stage-ready is visible. The
            // publication re-hashes the private artifacts inside the exclusion, so a
            // stale or altered observation is refused instead of advertised.
            val publication=journal.publishIfUnchanged(generation,current) {
                if (!request.stillOwns())
                    return@publishIfUnchanged Outcome.Refused(MetadataPrepareRejection.OWNER_CHANGED)
                if (!request.playbackDescriptorClosed())
                    return@publishIfUnchanged Outcome.Refused(MetadataPrepareRejection.ACTIVE_PLAYBACK)
                intents.write(finished,intent)
                journal.advance(current,finished)
                Outcome.Prepared(id,container,access.provider,ticket.expectedBytes,
                    stageBytes,originalSha,digest)
            }
            return when (publication) {
                is MetadataRecoveryJournalV1.Publication.Stale ->
                    throw PreparationFailure(MetadataPrepareRejection.RECOVERY_PENDING)
                is MetadataRecoveryJournalV1.Publication.Published -> publication.value
            }
        } catch (cancel: CancellationException) {
            abandon(id)
            throw cancel
        } catch (error: Exception) {
            // No document has been mutated. Discard only when journal says this
            // transaction never entered MUTATING; otherwise discardPrepared refuses.
            abandon(id)
            return Outcome.Refused((error as? PreparationFailure)?.reason
                ?: MetadataPrepareRejection.STAGING_FAILED)
        }
    }

    /** Best-effort cleanup that must not be skipped by cooperative cancellation. */
    private suspend fun abandon(id: String) {
        withContext(NonCancellable) {
            try { journal.exclusive { journal.discardPrepared(id) } }
            catch (_: Exception) { false }
        }
    }

    private val journalRoot get() = File(rootNoBackup, "michi-metadata-editor-v1")
    private val rootNoBackup: File = context.noBackupFilesDir

    private fun captureToPrivateFile(access: MetadataProviderReadAdapter.Access,
        file: File, expectedBytes: Long, checkCancellation: () -> Unit): String {
        val sha=MessageDigest.getInstance("SHA-256")
        FileChannel.open(file.toPath(),StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { out ->
            provider.readStream(access) { input ->
                val bytes=ByteArray(64*1024)
                var total=0L
                var zeroReads=0
                while(true) {
                    checkCancellation()
                    val n=input.read(bytes)
                    if(n<0)break
                    if(n==0){
                        if(++zeroReads>8)error("PROVIDER_ZERO_PROGRESS")
                        continue
                    }
                    zeroReads=0
                    total+=n
                    if(total>expectedBytes) error("SOURCE_LONGER_THAN_EXPECTED")
                    sha.update(bytes,0,n)
                    val buffer=ByteBuffer.wrap(bytes,0,n)
                    while(buffer.hasRemaining()) out.write(buffer)
                }
                if(total!=expectedBytes) error("SHORT_PROVIDER_READ")
            }
            out.force(true)
        }
        return sha.digest().joinToString(""){(it.toInt() and 255).toString(16).padStart(2,'0')}
    }
    private fun hashFile(file: File): String {
        val sha=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes=ByteArray(64*1024)
            while(true){val n=input.read(bytes);if(n<0)break;if(n>0)sha.update(bytes,0,n)}
        }
        return sha.digest().joinToString(""){(it.toInt() and 255).toString(16).padStart(2,'0')}
    }
    private fun sniff(file: File): MetadataAudioContainer =
        file.inputStream().use(::sniffHeader)

    private fun sniffHeader(stream: java.io.InputStream): MetadataAudioContainer {
        // Android API 31 compatible; no Java InputStream.readNBytes dependency.
        fun exact(n: Int): ByteArray? {
            if (n !in 0..4096) return null
            val out=ByteArray(n)
            var done=0
            while (done<n) {
                val count=stream.read(out,done,n-done)
                if (count<=0) return null
                done+=count
            }
            return out
        }
        val bytes=ByteArray(12)
        var at=0
        while(at<bytes.size){
            val n=stream.read(bytes,at,bytes.size-at)
            if(n<=0)return MetadataAudioContainer.UNKNOWN
            at+=n
        }
        return when {
            bytes.take(4).toByteArray().contentEquals("fLaC".toByteArray()) -> MetadataAudioContainer.FLAC
            bytes.take(4).toByteArray().contentEquals("RIFF".toByteArray()) &&
                bytes.copyOfRange(8,12).contentEquals("WAVE".toByteArray()) -> MetadataAudioContainer.WAV
            bytes.take(4).toByteArray().contentEquals("FORM".toByteArray()) &&
                bytes.copyOfRange(8,12).contentEquals("AIFF".toByteArray()) -> MetadataAudioContainer.AIFF
            bytes.copyOfRange(4,8).contentEquals("ftyp".toByteArray()) -> MetadataAudioContainer.MP4_M4A
            bytes.take(4).toByteArray().contentEquals("OggS".toByteArray()) -> {
                // Read only enough of the first Ogg page to locate its identification packet.
                // The cold parser and stage transformer later verify CRC, page ordering and tags.
                val extra=exact(15)
                if(extra==null)MetadataAudioContainer.UNKNOWN else {
                    val segments=extra[14].toInt() and 255
                    val lacing=exact(segments)
                    val magic=exact(8)
                    when {
                        lacing==null || magic==null -> MetadataAudioContainer.UNKNOWN
                        magic.contentEquals("OpusHead".toByteArray()) -> MetadataAudioContainer.OGG_OPUS
                        magic[0].toInt()==1 && magic.copyOfRange(1,7)
                            .contentEquals("vorbis".toByteArray()) -> MetadataAudioContainer.OGG_VORBIS
                        else -> MetadataAudioContainer.UNKNOWN
                    }
                }
            }
            bytes.take(4).toByteArray().contentEquals("wvpk".toByteArray()) -> MetadataAudioContainer.WAVPACK
            bytes.take(4).toByteArray().contentEquals("MAC ".toByteArray()) -> MetadataAudioContainer.APE
            bytes.take(3).toByteArray().contentEquals("ID3".toByteArray()) && bytes[3].toInt()==3 -> MetadataAudioContainer.ID3_V23
            bytes.take(3).toByteArray().contentEquals("ID3".toByteArray()) && bytes[3].toInt()==4 -> MetadataAudioContainer.ID3_V24
            else -> MetadataAudioContainer.UNKNOWN
        }
    }

    /** Always NO-GO. A separate ME-06 certified commit path must own mutations. */
    suspend fun commit(transactionId: String): Outcome = withContext(Dispatchers.IO) {
        Outcome.Refused(MetadataPrepareRejection.PREPARED_NOT_COMMIT_CERTIFIED)
    }
}
