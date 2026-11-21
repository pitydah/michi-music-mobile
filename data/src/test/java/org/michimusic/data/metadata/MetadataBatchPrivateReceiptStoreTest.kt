package org.michimusic.data.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.metadata.MetadataBatchRunPolicy
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MetadataBatchPrivateReceiptStoreTest {
    private fun track(id: String) = MetadataSnapshot(MetadataTarget("",id,
        "content://media/$id","e-$id"),true,
        mapOf(MetadataField.TITLE to listOf(id)))

    @Test fun ownerBoundProgressAndNoBackwardTransition()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val store=MetadataBatchPrivateReceiptStore(context)
        val batchId=java.util.UUID.randomUUID().toString()
        val x=track("songA")
        val initial=listOf(MetadataBatchRunPolicy.FileResult(0,x.target,
            MetadataBatchRunPolicy.State.NOT_STARTED))
        assertEquals(MetadataBatchPrivateReceiptStore.StoreResult.STORED,
            store.save(batchId,listOf(x),initial))
        assertTrue(store.load(batchId,listOf(x)) is MetadataBatchPrivateReceiptStore.Read.Verified)
        val next=listOf(MetadataBatchRunPolicy.FileResult(0,x.target,
            MetadataBatchRunPolicy.State.PRIVATE_STAGE_READY,
            "01234567-89ab-4cde-8f00-123456789abc"))
        assertEquals(MetadataBatchPrivateReceiptStore.StoreResult.STORED,
            store.save(batchId,listOf(x),next))
        assertEquals(MetadataBatchPrivateReceiptStore.StoreResult.REFUSED_OR_UNVERIFIABLE,
            store.save(batchId,listOf(x),initial))
        assertEquals(MetadataBatchPrivateReceiptStore.Read.UntrustedKeepExisting,
            store.load(batchId,listOf(track("songB"))))
    }
    @Test fun reviewBoundProgressCannotBeReusedForAnotherRecipe()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val store=MetadataBatchPrivateReceiptStore(context)
        val batchId=java.util.UUID.randomUUID().toString()
        val song=track("songVersion")
        val original=listOf(song)
        val recipeA=listOf(MetadataEditPreview.review(song,listOf(
            MetadataChange.Replace(MetadataField.TITLE,listOf("First")))))
        val recipeB=listOf(MetadataEditPreview.review(song,listOf(
            MetadataChange.Replace(MetadataField.TITLE,listOf("Other")))))
        val rows=listOf(MetadataBatchRunPolicy.FileResult(0,song.target,
            MetadataBatchRunPolicy.State.NOT_STARTED))
        assertEquals(MetadataBatchPrivateReceiptStore.StoreResult.STORED,
            store.save(batchId,original,rows,recipeA))
        assertTrue(store.load(batchId,original,recipeA) is
            MetadataBatchPrivateReceiptStore.Read.Verified)
        assertEquals(MetadataBatchPrivateReceiptStore.Read.UntrustedKeepExisting,
            store.load(batchId,original,recipeB))
        assertEquals(MetadataBatchPrivateReceiptStore.StoreResult.REFUSED_OR_UNVERIFIABLE,
            store.save(batchId,original,rows,recipeB))
    }
    @Test fun V2StageCanBeDiscardedButNotReplayed()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val store=MetadataBatchPrivateReceiptStore(context)
        val id=java.util.UUID.randomUUID().toString()
        val song=track("privateStageClear")
        val review=listOf(MetadataEditPreview.review(song,listOf(
            MetadataChange.Replace(MetadataField.TITLE,listOf("Updated")))))
        val tx="12345678-1234-4123-8123-123456789abc"
        val initial=listOf(MetadataBatchRunPolicy.FileResult(0,song.target,
            MetadataBatchRunPolicy.State.NOT_STARTED))
        val staged=listOf(initial[0].copy(state=MetadataBatchRunPolicy.State.PRIVATE_STAGE_READY,
            transactionId=tx))
        val discarded=listOf(staged[0].copy(state=MetadataBatchRunPolicy.State.PRIVATE_STAGE_DISCARDED))
        assertEquals(MetadataBatchPrivateReceiptStore.StoreResult.STORED,
            store.save(id,listOf(song),initial,review))
        assertEquals(MetadataBatchPrivateReceiptStore.StoreResult.STORED,
            store.save(id,listOf(song),staged,review))
        assertEquals(MetadataBatchPrivateReceiptStore.StoreResult.STORED,
            store.save(id,listOf(song),discarded,review))
        assertEquals(MetadataBatchPrivateReceiptStore.StoreResult.REFUSED_OR_UNVERIFIABLE,
            store.save(id,listOf(song),staged,review))
    }
}
