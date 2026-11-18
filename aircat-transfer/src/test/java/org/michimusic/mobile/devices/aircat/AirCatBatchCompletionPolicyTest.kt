package org.michimusic.mobile.devices.aircat

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures exercise the production contract; intentionally NOT run in Drive-only workflow. */
class AirCatBatchCompletionPolicyTest {
    private val items = listOf(
        AirCatManifestItem("logical-a", "remote-x", "A", "Artist", "Album", "flac", 3L, "a".repeat(64)),
        AirCatManifestItem("logical-b", "remote-y", "B", "Artist", "Album", "flac", 4L, "b".repeat(64)),
    )
    private val manifest = AirCatManifest(
        transferId = "tx",
        sourceDeviceId = "source",
        totalTracks = 2,
        totalBytes = 7L,
        items = items,
    )

    @Test fun incompleteOrMisorderedTypedReceiptCannotCompleteBatch() {
        val dir = Files.createTempDirectory("aircat-receipt").toFile()
        try {
            val a = File(dir,"a.flac").apply { writeBytes(byteArrayOf(1,2,3)) }
            val b = File(dir,"b.flac").apply { writeBytes(byteArrayOf(4,5,6,7)) }
            fun receipt(id:String, remote:String, size:Long, sha:String, file:File) =
                AirCatCommitReceiptPolicy.Confirmed("key-$id","tx","source","library",
                    id, remote, file.absolutePath, size, sha)
            val x = receipt("logical-a","remote-x",3L,"a".repeat(64),a)
            val y = receipt("logical-b","remote-y",4L,"b".repeat(64),b)
            assertTrue(AirCatBatchCompletionPolicy.completeWithReceipts(
                manifest,listOf(a,b),listOf(x,y),true,true,7L))
            assertFalse(AirCatBatchCompletionPolicy.completeWithReceipts(
                manifest,listOf(a,b),listOf(x),true,true,7L))
            assertFalse(AirCatBatchCompletionPolicy.completeWithReceipts(
                manifest,listOf(a,b),listOf(y,x),true,true,7L))
            assertFalse(AirCatBatchCompletionPolicy.completeWithReceipts(
                manifest,listOf(a,b),listOf(x,y.copy(transferId="other")),true,true,7L))
        } finally { dir.deleteRecursively() }
    }

    @Test fun twoPersistedOriginalsAreComplete() {
        val dir = Files.createTempDirectory("aircat-batch").toFile()
        try {
            val a = File(dir, "a.flac").apply { writeBytes(byteArrayOf(1,2,3)) }
            val b = File(dir, "b.flac").apply { writeBytes(byteArrayOf(4,5,6,7)) }
            assertTrue(AirCatBatchCompletionPolicy.complete(manifest, listOf(a,b), true,true,7L))
            assertFalse(AirCatBatchCompletionPolicy.complete(manifest, listOf(a), true,true,7L))
            assertFalse(AirCatBatchCompletionPolicy.complete(manifest, listOf(a,b), true,false,7L))
            assertFalse(AirCatBatchCompletionPolicy.complete(manifest, listOf(a,b), false,true,7L))
            assertFalse(AirCatBatchCompletionPolicy.complete(manifest, listOf(a,b), true,true,6L))
            assertFalse(AirCatBatchCompletionPolicy.complete(manifest.copy(totalBytes=8L), listOf(a,b), true,true,8L))
            assertFalse(AirCatBatchCompletionPolicy.complete(manifest.copy(items=listOf(items[0], items[0])), listOf(a,b), true,true,7L))
            assertFalse(AirCatBatchCompletionPolicy.complete(manifest, listOf(b,a), true,true,7L))
        } finally { dir.deleteRecursively() }
    }
}
