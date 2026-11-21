package org.michimusic.core.metadata

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** ME-10J contracts, written for OpenCode; not executed or compiled in Drive. */
class MetadataPrivateCandidateAuditTest {
    private val audio=ByteArray(600) { (it*13).toByte() }
    private fun sync(n: Int)=byteArrayOf((n ushr 21 and 127).toByte(),
        (n ushr 14 and 127).toByte(),(n ushr 7 and 127).toByte(),(n and 127).toByte())
    private fun fileWithTitle(title:String):ByteArray {
        val text=byteArrayOf(3)+title.toByteArray(Charsets.UTF_8)
        val frame="TIT2".toByteArray()+sync(text.size)+byteArrayOf(0,0)+text
        val body=frame+ByteArray(3)
        return "ID3".toByteArray()+byteArrayOf(4,0,0)+sync(body.size)+body+audio
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
    private fun receipt(id:String,original:ByteArray,candidate:ByteArray,
        audioSha:String=sha(audio), canCommit:String="CAN_COMMIT=false")=
        "ME10H_PRIVATE_PREVIEW_V1\n$id\n${sha(original)}\n${sha(candidate)}\n"+
            "$audioSha\n${candidate.size}\n$canCommit\n"
    private fun <T> withPrivate(work:(String,File,File,ByteArray,ByteArray)->T):T {
        val root=Files.createTempDirectory("me10j-audit-")
        val id=UUID.randomUUID().toString()
        val dir=Files.createDirectory(root.resolve("session-$id"))
        val source=fileWithTitle("Original")
        val changed=fileWithTitle("Cambiado")
        val a=dir.resolve("original.bin").toFile().apply { writeBytes(source) }
        val b=dir.resolve("candidate.bin").toFile().apply { writeBytes(changed) }
        try { return work(id,a,b,source,changed) }
        finally {
            Files.list(dir).use { it.forEach { path->Files.deleteIfExists(path) } }
            Files.deleteIfExists(dir); Files.deleteIfExists(root)
        }
    }
    @Test fun validPrivateHashesDoNotGrantAWrite(){withPrivate {id,a,b,orig,changed ->
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,receipt(id,orig,changed))
        assertTrue(result is MetadataPrivateCandidateAudit.Result.HashesMatchPrivateOnly)
        assertFalse((result as MetadataPrivateCandidateAudit.Result.HashesMatchPrivateOnly).canCommit)
        assertEquals(orig.toList(),a.readBytes().toList())
    }}
    @Test fun differentSessionIdNeverReusesOldReceipt(){withPrivate {id,a,b,orig,changed ->
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,receipt(UUID.randomUUID().toString(),orig,changed))
        assertEquals(MetadataPrivateCandidateAudit.Reason.INVALID_RECEIPT,
            (result as MetadataPrivateCandidateAudit.Result.Refused).reason)
    }}
    @Test fun manifestMustExplicitlyDenyCommit(){withPrivate {id,a,b,orig,changed ->
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,receipt(id,orig,changed,
            canCommit="CAN_COMMIT=true"))
        assertEquals(MetadataPrivateCandidateAudit.Reason.INVALID_RECEIPT,
            (result as MetadataPrivateCandidateAudit.Result.Refused).reason)
    }}
    @Test fun appendedManifestTextIsRejected(){withPrivate {id,a,b,orig,changed ->
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,receipt(id,orig,changed)+"INJECTED\n")
        assertEquals(MetadataPrivateCandidateAudit.Reason.INVALID_RECEIPT,
            (result as MetadataPrivateCandidateAudit.Result.Refused).reason)
    }}
    @Test fun oversizedReceiptIsRejectedBeforeFileReads(){withPrivate {id,a,b,_,_ ->
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,"X".repeat(513))
        assertEquals(MetadataPrivateCandidateAudit.Reason.INVALID_RECEIPT,
            (result as MetadataPrivateCandidateAudit.Result.Refused).reason)
    }}
    @Test fun changedOriginalBytesAreDetected(){withPrivate {id,a,b,orig,changed ->
        val savedReceipt=receipt(id,orig,changed)
        val altered=orig.copyOf().apply { this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte() }
        a.writeBytes(altered)
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,savedReceipt)
        assertEquals(MetadataPrivateCandidateAudit.Reason.ORIGINAL_HASH_MISMATCH,
            (result as MetadataPrivateCandidateAudit.Result.Refused).reason)
    }}
    @Test fun changedCandidateBytesAreDetected(){withPrivate {id,a,b,orig,changed ->
        val savedReceipt=receipt(id,orig,changed)
        val altered=changed.copyOf().apply { this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte() }
        b.writeBytes(altered)
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,savedReceipt)
        assertEquals(MetadataPrivateCandidateAudit.Reason.CANDIDATE_HASH_MISMATCH,
            (result as MetadataPrivateCandidateAudit.Result.Refused).reason)
    }}
    @Test fun changedAudioReceiptIsRejected(){withPrivate {id,a,b,orig,changed ->
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,receipt(id,orig,changed,
            audioSha="0".repeat(64)))
        assertEquals(MetadataPrivateCandidateAudit.Reason.AUDIO_HASH_MISMATCH,
            (result as MetadataPrivateCandidateAudit.Result.Refused).reason)
    }}
    @Test fun missingCandidateCannotBeVerified(){withPrivate {id,a,b,orig,changed ->
        val r=receipt(id,orig,changed);b.delete()
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,r)
        assertEquals(MetadataPrivateCandidateAudit.Reason.MISSING_OR_UNSAFE_FILES,
            (result as MetadataPrivateCandidateAudit.Result.Refused).reason)
    }}
    @Test fun zeroChangesInPrivateTagsStillRequireRealDigests(){withPrivate {id,a,b,orig,_ ->
        b.writeBytes(orig)
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,receipt(id,orig,orig))
        assertTrue(result is MetadataPrivateCandidateAudit.Result.HashesMatchPrivateOnly)
    }}
    @Test fun cancellationPropagatesInsteadOfReturningAReceipt(){withPrivate {id,a,b,orig,changed ->
        try {
            MetadataPrivateCandidateAudit.verify(id,a,b,receipt(id,orig,changed)) {
                throw CancellationException("cancelled")
            }
            fail("cancellation swallowed")
        } catch (_: CancellationException) { /* expected */ }
    }}
    @Test fun differentPrivateParentIsRejected(){withPrivate {id,a,b,orig,changed ->
        val extra=Files.createTempFile("me10j-unowned-", ".bin")
        try {
            val result=MetadataPrivateCandidateAudit.verify(id,a,extra.toFile(),receipt(id,orig,changed))
            assertEquals(MetadataPrivateCandidateAudit.Reason.MISSING_OR_UNSAFE_FILES,
                (result as MetadataPrivateCandidateAudit.Result.Refused).reason)
        } finally { Files.deleteIfExists(extra) }
    }}
    private fun v2Receipt(id:String,original:ByteArray,candidate:ByteArray,owner:String) =
        "ME10K_PRIVATE_PREVIEW_V2\n$id\n${sha(original)}\n${sha(candidate)}\n"+
            "${sha(audio)}\n${candidate.size}\nOWNER_SHA256=$owner\nCAN_COMMIT=false\n"
    @Test fun v2MatchingOwnerIsReportedOnlyAsPrivateProof(){withPrivate {id,a,b,orig,changed ->
        val owner=MetadataPrivateSessionIdentity.digest(MetadataTarget("","42","content://music/42","snap-a"))
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,v2Receipt(id,orig,changed,owner),owner)
        assertTrue(result is MetadataPrivateCandidateAudit.Result.HashesMatchPrivateOnly)
        val matched=result as MetadataPrivateCandidateAudit.Result.HashesMatchPrivateOnly
        assertTrue(matched.ownerMatched)
        assertTrue(matched.ownerBound)
        assertFalse(matched.canCommit)
    }}
    @Test fun v2WrongOwnerIsRejectedBeforeHashingFiles(){withPrivate {id,a,b,orig,changed ->
        val owner="a".repeat(64)
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,v2Receipt(id,orig,changed,owner),"b".repeat(64))
        assertEquals(MetadataPrivateCandidateAudit.Reason.OWNER_MISMATCH,
            (result as MetadataPrivateCandidateAudit.Result.Refused).reason)
    }}
    @Test fun v1HashCheckMustNotPretendToKnowTheTrack(){withPrivate {id,a,b,orig,changed ->
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,receipt(id,orig,changed),"b".repeat(64))
        assertEquals(MetadataPrivateCandidateAudit.Reason.LEGACY_OWNER_UNBOUND,
            (result as MetadataPrivateCandidateAudit.Result.Refused).reason)
    }}
    @Test fun unscopedV2AuditDoesNotClaimOwnerMatch(){withPrivate {id,a,b,orig,changed ->
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,v2Receipt(id,orig,changed,"c".repeat(64)))
        assertTrue(result is MetadataPrivateCandidateAudit.Result.HashesMatchPrivateOnly)
        assertFalse((result as MetadataPrivateCandidateAudit.Result.HashesMatchPrivateOnly).ownerMatched)
    }}
    @Test fun nonCanonicalV2OwnerDigestRejected(){withPrivate {id,a,b,orig,changed ->
        val result=MetadataPrivateCandidateAudit.verify(id,a,b,v2Receipt(id,orig,changed,"D".repeat(64)))
        assertEquals(MetadataPrivateCandidateAudit.Reason.INVALID_RECEIPT,
            (result as MetadataPrivateCandidateAudit.Result.Refused).reason)
    }}

}
