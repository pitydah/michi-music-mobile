package org.michimusic.data.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.metadata.MetadataVerifiedReceiptGate

class MetadataIndexCasCoordinatorTest {
    @Test fun uncataloguedWriterDoesNotEvenReadRoom()=runBlocking {
        var requests=0
        val coordinator=MetadataIndexCasCoordinator(object : MetadataIndexCasCoordinator.Port {
            override suspend fun current(targetSource: String,targetTrack: String):
                MetadataIndexPublicationPolicy.IndexIdentity? { requests++;return null }
            override suspend fun updateIfVersion(
                expected: MetadataIndexPublicationPolicy.IndexIdentity,
                delta: MetadataSourceIndexDelta.Delta): Boolean { requests++;return true }
        })
        val source=MetadataIndexPublicationPolicy.IndexIdentity("","1","content://media/1",2,"a".repeat(64))
        val target=MetadataTarget("","1","content://media/1","evidence")
        val delta=MetadataSourceIndexDelta.Result.PreparedForExternalCas(
            MetadataSourceIndexDelta.Delta(target,"12345678-1234-4123-8123-123456789abc",
                "b".repeat(64), emptyMap(),emptySet()))
        val result=coordinator.attempt(source,delta,MetadataRecoveryPhase.POSTCHECK_VERIFIED,
            MetadataVerifiedReceiptGate.Decision.ReadyForIndexCompare(
                "12345678-1234-4123-8123-123456789abc","b".repeat(64)),true,{true})
        assertEquals(MetadataIndexCasCoordinator.Result.Blocked(
            MetadataIndexCasCoordinator.Refusal.NOT_CERTIFIED),result)
        assertEquals(0,requests)
    }
}
