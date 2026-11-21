package org.michimusic.data.metadata

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import org.michimusic.core.metadata.MetadataPrepareDecision
import org.michimusic.core.metadata.MetadataPreparePolicy
import org.michimusic.core.metadata.MetadataPrepareRejection
import org.michimusic.core.metadata.MetadataPrepareTicket
import org.michimusic.core.metadata.MetadataPrivateBundleStage
import org.michimusic.core.metadata.MetadataPrivateCandidateAudit
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSelectiveAssetStage
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.metadata.MetadataPrivateSessionIdentity
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.data.repository.MetadataEditorEvidenceResult

/**
 * ME-10H. Explicit user-triggered ANDROID READ boundary for bundles. This
 * deliberately does not touch the durable writer journal, SAF write modes,
 * MediaStore.update(), Room, or the original document. Sessions are private,
 * disposable PREVIEWS, not restoration backups or write capabilities.
 *
 * Recovery of an interrupted original belongs to MetadataRecoveryJournalV1,
 * never to this preview-only component.
 */
class MetadataPrivateBundleCoordinator(
    context: Context,
    private val metadataRepository: ExtendedTrackMetadataRepository,
) {
    private val appNoBackup = context.noBackupFilesDir
    private val root = File(appNoBackup,"michi-metadata-bundle-previews-v1")
    private val provider = MetadataProviderReadAdapter(context)
    // Per-process exclusivity across ALL coordinator instances. A multiprocess
    // Android deployment still requires an independent OS-level lock/certification.
    private val mutex = processMutex
    private companion object {
        val processMutex = Mutex()
        const val SESSION_PAGE_LIMIT = 64
        const val MAX_RETAINED_SESSIONS = 8
    }
    private val idFormat=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val hex64=Regex("[a-f0-9]{64}")
    private val tempPattern=Regex("\\.me10h-[0-9a-f-]{36}\\.tmp")
    private val maxBytes=128L * 1024 * 1024
    private val reserveBytes=64L * 1024 * 1024

    data class Request(
        val ticket: MetadataPrepareTicket,
        val original: MetadataSnapshot,
        val fields: MetadataReview.Ready? = null,
        val observed: MetadataArtworkLyricsProbe.Snapshot,
        val assets: List<MetadataSelectiveAssetStage.Edit>,
        val playbackDescriptorClosed: () -> Boolean,
        val stillOwns: () -> Boolean,
        /** This is phase/byte progress, not a claim that the Android write is available. */
        val onProgress: ((Progress) -> Unit)? = null,
    )
    enum class Phase {
        CHECKING_IDENTITY, COPYING_SOURCE, VERIFYING_SOURCE,
        REWRITING_PRIVATE_COPY, FINAL_VERIFICATION, RECEIPT_CREATED,
    }
    data class Progress(val phase: Phase, val copiedBytes: Long = 0, val totalBytes: Long = 0)

    enum class Rejection {
        USER_ACTION_REQUIRED, BAD_OWNER_OR_TARGET, ACTIVE_PLAYBACK, UNVERIFIED_TAGS,
        INVALID_OR_UNSUPPORTED_SIZE, FIELD_REVIEW_REJECTED, SOURCE_CHANGED,
        PROVIDER_UNAVAILABLE, NOT_ALLOWED_PROVIDER, PRIVATE_STORAGE_UNAVAILABLE,
        PRIVATE_BUNDLE_REJECTED, PROPOSAL_FAILED, PRIVATE_SESSION_LIMIT_REACHED,
    }
    sealed interface Result {
        data class Prepared(
            val sessionId: String, val originalSha256: String, val candidateSha256: String,
            val audioSha256: String, val candidateBytes: Long,
            val changes: List<String>, val canCommit: Boolean = false,
        ) : Result
        data class Refused(val reason: Rejection) : Result
    }
    enum class SessionState { PENDING_OR_INTERRUPTED, FILES_PRESENT_UNVERIFIED, UNREADABLE }
    enum class SessionInspectionStatus { READY, NOT_INITIALIZED, UNSAFE_ROOT, ENUMERATION_FAILED }
    data class Session(val id:String,val state:SessionState)
    data class SessionInventory(
        val sessions: List<Session>,
        val hasMore: Boolean,
        val status: SessionInspectionStatus,
    )

    /** Explicit on-demand enumeration. Never silently reports zero at >64 sessions. */
    suspend fun inspectPrivateSessions():SessionInventory = withContext(Dispatchers.IO) {
        mutex.withLock {
            if(Files.isSymbolicLink(appNoBackup.toPath()) ||
                !Files.isDirectory(appNoBackup.toPath(),LinkOption.NOFOLLOW_LINKS))
                return@withLock SessionInventory(emptyList(),false,SessionInspectionStatus.UNSAFE_ROOT)
            if(!Files.exists(root.toPath(),LinkOption.NOFOLLOW_LINKS))
                return@withLock SessionInventory(emptyList(),false,SessionInspectionStatus.NOT_INITIALIZED)
            if(!safeRoot(false))
                return@withLock SessionInventory(emptyList(),false,SessionInspectionStatus.UNSAFE_ROOT)
            try {
                val sessions=ArrayList<Session>(SESSION_PAGE_LIMIT)
                var hasMore=false
                Files.newDirectoryStream(root.toPath()).use { children ->
                    for(path in children) {
                        val name=path.fileName.toString()
                        if(!name.startsWith("session-") ||
                            !idFormat.matches(name.removePrefix("session-")))continue
                        if(sessions.size==SESSION_PAGE_LIMIT) { hasMore=true;break }
                        val state=when {
                            !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) -> SessionState.UNREADABLE
                            Files.isRegularFile(path.resolve("original.bin"),LinkOption.NOFOLLOW_LINKS) &&
                                Files.isRegularFile(path.resolve("candidate.bin"),LinkOption.NOFOLLOW_LINKS) &&
                                Files.isRegularFile(path.resolve("receipt.txt"),LinkOption.NOFOLLOW_LINKS) &&
                                (path.toFile().listFiles()?.let { children ->
                                    children.size == 3 && children.all(::isKnownChild)
                                } == true) -> SessionState.FILES_PRESENT_UNVERIFIED
                            else -> SessionState.PENDING_OR_INTERRUPTED
                        }
                        sessions+=Session(name.removePrefix("session-"),state)
                    }
                }
                SessionInventory(sessions.sortedBy { it.id },hasMore,SessionInspectionStatus.READY)
            }catch(cancel:CancellationException){throw cancel}
             catch(_:Exception){SessionInventory(emptyList(),false,SessionInspectionStatus.ENUMERATION_FAILED)}
        }
    }

    /**
     * Manual, read-only private candidate audit. It does not reopen the provider,
     * modify a library file or certify metadata semantics. Never launched in idle.
     */
    suspend fun verifyPrivateSession(
        id: String, expectedTarget: MetadataTarget? = null,
    ): MetadataPrivateCandidateAudit.Result =
        withContext(Dispatchers.IO) {
            if (!idFormat.matches(id))
                return@withContext MetadataPrivateCandidateAudit.Result.Refused(
                    MetadataPrivateCandidateAudit.Reason.INVALID_RECEIPT)
            val job = currentCoroutineContext()[Job]
            mutex.withLock {
                if (!safeRoot(false)) return@withLock MetadataPrivateCandidateAudit.Result.Refused(
                    MetadataPrivateCandidateAudit.Reason.MISSING_OR_UNSAFE_FILES)
                val dir=File(root,"session-$id")
                if (dir.parentFile != root || Files.isSymbolicLink(dir.toPath()) ||
                    !Files.isDirectory(dir.toPath(),LinkOption.NOFOLLOW_LINKS))
                    return@withLock MetadataPrivateCandidateAudit.Result.Refused(
                        MetadataPrivateCandidateAudit.Reason.MISSING_OR_UNSAFE_FILES)
                try {
                    job?.ensureActive()
                    val children=dir.listFiles() ?: return@withLock MetadataPrivateCandidateAudit.Result.Refused(
                        MetadataPrivateCandidateAudit.Reason.MISSING_OR_UNSAFE_FILES)
                    if (children.size!=3 || children.any { !isKnownChild(it) })
                        return@withLock MetadataPrivateCandidateAudit.Result.Refused(
                            MetadataPrivateCandidateAudit.Reason.MISSING_OR_UNSAFE_FILES)
                    val original=File(dir,"original.bin")
                    val candidate=File(dir,"candidate.bin")
                    val receipt=File(dir,"receipt.txt")
                    if (!children.map { it.name }.toSet().containsAll(setOf(
                            "original.bin","candidate.bin","receipt.txt")) ||
                        Files.size(receipt.toPath()) !in 1..MetadataPrivateCandidateAudit.MAX_RECEIPT_BYTES ||
                        Files.size(original.toPath()) !in 20..maxBytes ||
                        Files.size(candidate.toPath()) !in 20..(256L*1024L*1024L))
                        return@withLock MetadataPrivateCandidateAudit.Result.Refused(
                            MetadataPrivateCandidateAudit.Reason.MISSING_OR_UNSAFE_FILES)
                    val receiptBytes=Files.newInputStream(receipt.toPath(),LinkOption.NOFOLLOW_LINKS)
                        .use { it.readBytes() }
                    if (receiptBytes.size.toLong()>MetadataPrivateCandidateAudit.MAX_RECEIPT_BYTES)
                        return@withLock MetadataPrivateCandidateAudit.Result.Refused(
                            MetadataPrivateCandidateAudit.Reason.INVALID_RECEIPT)
                    val expectedOwner = expectedTarget?.let(MetadataPrivateSessionIdentity::digest)
                    MetadataPrivateCandidateAudit.verify(id,original,candidate,
                        receiptBytes.toString(Charsets.UTF_8), expectedOwner) {
                        job?.ensureActive()
                    }
                } catch (cancel: CancellationException) { throw cancel }
                  catch (_: Exception) { MetadataPrivateCandidateAudit.Result.Refused(
                    MetadataPrivateCandidateAudit.Reason.IO_UNAVAILABLE) }
            }
        }

    /** Do not evict valid sessions automatically to create space for a new preview. */
    private fun underSessionLimit():Boolean {
        return try {
            var count=0
            var spaceForSession=true
            Files.newDirectoryStream(root.toPath()).use { children ->
                for(path in children) {
                    val name=path.fileName.toString()
                    if(name.startsWith("session-") && idFormat.matches(name.removePrefix("session-"))) {
                        if(++count>=MAX_RETAINED_SESSIONS) { spaceForSession=false;break }
                    }
                }
            }
            spaceForSession
        }catch(_:Exception){false}
    }

    private fun isKnownChild(file:File):Boolean =
        (file.name in setOf("original.bin","candidate.bin","receipt.txt") ||
            tempPattern.matches(file.name)) &&
            !Files.isSymbolicLink(file.toPath()) &&
            Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)

    private fun deleteOwnedSession(dir:File):Boolean {
        if(dir.parentFile!=root || !Files.isDirectory(dir.toPath(),LinkOption.NOFOLLOW_LINKS))
            return false
        val children=dir.listFiles() ?:return false
        if(children.any { !isKnownChild(it) })return false
        children.forEach { Files.delete(it.toPath()) }
        Files.delete(dir.toPath())
        return true
    }

    /** Requires explicit confirmation; never deletes a provider document. */
    suspend fun discardPrivateSession(id:String,userConfirmed:Boolean):Boolean = withContext(Dispatchers.IO) {
        if(!userConfirmed || !idFormat.matches(id))return@withContext false
        mutex.withLock {
            if(!safeRoot(false))return@withLock false
            deleteOwnedSession(File(root,"session-$id"))
        }
    }

    suspend fun prepare(request:Request):Result = withContext(Dispatchers.IO) {
        if(!request.ticket.explicitUserAction)return@withContext Result.Refused(Rejection.USER_ACTION_REQUIRED)
        request.onProgress?.invoke(Progress(Phase.CHECKING_IDENTITY))
        val ticket=request.ticket.copy(
            playbackClosed=request.playbackDescriptorClosed(), ownerStillCurrent=request.stillOwns())
        if(!ticket.ownerStillCurrent || ticket.uri!=request.original.target.uri ||
            ticket.sourceId!=request.original.target.sourceId ||
            ticket.trackId!=request.original.target.trackId || !ticket.uri.startsWith("content://"))
            return@withContext Result.Refused(Rejection.BAD_OWNER_OR_TARGET)
        if(!ticket.playbackClosed)return@withContext Result.Refused(Rejection.ACTIVE_PLAYBACK)
        if(!request.original.tagsVerified || request.assets.size>4 ||
            (request.fields==null && request.assets.isEmpty()))
            return@withContext Result.Refused(Rejection.UNVERIFIED_TAGS)
        if(ticket.expectedBytes !in 20..maxBytes)return@withContext Result.Refused(Rejection.INVALID_OR_UNSUPPORTED_SIZE)
        if(request.fields!=null) {
            if(request.fields.original!=request.original ||
                MetadataPreparePolicy.evaluate(ticket,request.fields) !is MetadataPrepareDecision.EligibleToInspectProvider)
                return@withContext Result.Refused(Rejection.FIELD_REVIEW_REJECTED)
        }
        val verified=try {
            metadataRepository.loadVerifiedEditorSnapshotResult(ticket.sourceId,ticket.trackId,
                ticket.uri,ticket.expectedBytes)
        }catch(cancel:CancellationException){throw cancel}
         catch(_:Exception){return@withContext Result.Refused(Rejection.PROVIDER_UNAVAILABLE)}
        if(verified !is MetadataEditorEvidenceResult.Verified || verified.snapshot!=request.original)
            return@withContext Result.Refused(Rejection.SOURCE_CHANGED)
        val job=currentCoroutineContext()[Job]
        mutex.withLock {
            if(!request.stillOwns() || !request.playbackDescriptorClosed())
                return@withLock Result.Refused(Rejection.BAD_OWNER_OR_TARGET)
            if(!safeRoot(true))return@withLock Result.Refused(Rejection.PRIVATE_STORAGE_UNAVAILABLE)
            if(!underSessionLimit())
                return@withLock Result.Refused(Rejection.PRIVATE_SESSION_LIMIT_REACHED)
            val access=try{provider.openForRead(ticket.sourceId,ticket.uri,ticket.expectedBytes)}
                catch(cancel:CancellationException){throw cancel}
                catch(_:MetadataProviderReadAdapter.Refused){return@withLock Result.Refused(Rejection.NOT_ALLOWED_PROVIDER)}
                catch(_:SecurityException){return@withLock Result.Refused(Rejection.NOT_ALLOWED_PROVIDER)}
                catch(_:Exception){return@withLock Result.Refused(Rejection.PROVIDER_UNAVAILABLE)}
            val steps=request.assets.size+(if(request.fields!=null)1 else 0)
            val projected=ticket.expectedBytes*(steps+2L)+reserveBytes
            if(root.usableSpace<projected)return@withLock Result.Refused(Rejection.PRIVATE_STORAGE_UNAVAILABLE)
            val id=UUID.randomUUID().toString()
            val dir=File(root,"session-$id")
            var completed=false
            try {
                Files.createDirectory(dir.toPath())
                val original=File(dir,"original.bin")
                val digest=MessageDigest.getInstance("SHA-256")
                var actualSize=0L
                var lastReported=0L
                request.onProgress?.invoke(Progress(Phase.COPYING_SOURCE, 0, ticket.expectedBytes))
                FileChannel.open(original.toPath(),StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { channel ->
                    provider.readStream(access){input ->
                        val buf=ByteArray(64*1024);var stalled=0
                        while(true){
                            job?.ensureActive()
                            val n=input.read(buf)
                            if(n<0)break
                            if(n==0){if(++stalled>8)error("Provider stalled");continue}
                            stalled=0;actualSize+=n
                            if(actualSize>ticket.expectedBytes)error("Provider bytes exceed stamp")
                            digest.update(buf,0,n)
                            if(actualSize-lastReported>=1024L*1024 || actualSize==ticket.expectedBytes) {
                                lastReported=actualSize
                                request.onProgress?.invoke(Progress(Phase.COPYING_SOURCE,actualSize,ticket.expectedBytes))
                            }
                            val chunk=ByteBuffer.wrap(buf,0,n)
                            while(chunk.hasRemaining())channel.write(chunk)
                        }
                        if(actualSize!=ticket.expectedBytes)error("Partial provider read")
                    }
                    channel.force(true)
                }
                request.onProgress?.invoke(Progress(Phase.VERIFYING_SOURCE))
                if(provider.stamp(access)!=access.stamp || !request.stillOwns() ||
                    !request.playbackDescriptorClosed())return@withLock Result.Refused(Rejection.SOURCE_CHANGED)
                val sha=digest.digest().joinToString("") {(it.toInt() and 255).toString(16).padStart(2,'0')}
                if(!hex64.matches(sha) || !provider.verifyBytes(access,ticket.expectedBytes,sha){job?.ensureActive()})
                    return@withLock Result.Refused(Rejection.SOURCE_CHANGED)
                request.onProgress?.invoke(Progress(Phase.REWRITING_PRIVATE_COPY))
                val candidate=File(dir,"candidate.bin")
                val assembled=MetadataPrivateBundleStage.stage(original,candidate,
                    MetadataPrivateBundleStage.Request(sha,request.observed,request.fields,request.assets)) {
                    job?.ensureActive()
                }
                job?.ensureActive()
                request.onProgress?.invoke(Progress(Phase.FINAL_VERIFICATION))
                if(!request.stillOwns() || !request.playbackDescriptorClosed() ||
                    provider.stamp(access)!=access.stamp ||
                    !provider.verifyBytes(access,ticket.expectedBytes,sha){job?.ensureActive()})
                    return@withLock Result.Refused(Rejection.SOURCE_CHANGED)
                job?.ensureActive()
                // A receipt represents private staging ONLY, never source mutation.
                val manifest=File(dir,"receipt.txt")
                FileChannel.open(manifest.toPath(),StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { c ->
                    val ownerSha = MetadataPrivateSessionIdentity.digest(request.original.target)
                    val receipt="ME10K_PRIVATE_PREVIEW_V2\n$id\n$sha\n${assembled.candidateSha256}\n${assembled.audioSha256}\n${assembled.candidateBytes}\nOWNER_SHA256=$ownerSha\nCAN_COMMIT=false\n"
                    val buffer=ByteBuffer.wrap(receipt.toByteArray(Charsets.UTF_8))
                    while(buffer.hasRemaining())c.write(buffer)
                    c.force(true)
                }
                job?.ensureActive()
                completed=true
                request.onProgress?.invoke(Progress(Phase.RECEIPT_CREATED))
                Result.Prepared(id,sha,assembled.candidateSha256,assembled.audioSha256,
                    assembled.candidateBytes,assembled.changes)
            } catch(cancel:CancellationException) { throw cancel }
              catch(_:MetadataPrivateBundleStage.Rejected) { Result.Refused(Rejection.PRIVATE_BUNDLE_REJECTED) }
              catch(_:Exception) { Result.Refused(Rejection.PROPOSAL_FAILED) }
            finally {
                // A normal refusal/cancellation releases only this new private
                // session. A process kill can leave an orphan; the manual inspector
                // may enumerate and discard it on a later explicit user gesture.
                if(!completed)try{deleteOwnedSession(dir)}catch(_:Exception){}
            }
        }
    }

    private fun safeRoot(create:Boolean):Boolean {
        val parent=appNoBackup.toPath()
        if(Files.isSymbolicLink(parent) || !Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS) ||
            root.toPath().parent!=parent)return false
        val path=root.toPath()
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))
            return Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
        if(!create)return false
        return try{Files.createDirectory(path);true}catch(_:Exception){false}
    }
}
