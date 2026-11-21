package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No Android provider or mutation tests. These verify WAV's PRIVATE-only intent. */
class MetadataRiffSemanticIntentTest {
    private val snapshot = MetadataSnapshot(
        MetadataTarget("","source-wav","content://saf/wav","cold-token"),
        true,mapOf(MetadataField.TITLE to listOf("Old"),MetadataField.ARTIST to listOf("Singer")),
        verifiedFields=setOf(MetadataField.TITLE,MetadataField.ARTIST))
    private val review = MetadataEditPreview.review(snapshot,
        listOf(MetadataChange.Replace(MetadataField.TITLE,listOf("New")))) as MetadataReview.Ready
    private val transaction="00000000-0000-4000-8000-000000000003"
    private fun receipt():MetadataCommitIntentManifest.Receipt =
        MetadataCommitIntentManifest.create(transaction,review,MetadataDocumentProvider.SAF,
            MetadataAudioContainer.WAV,4096,"a".repeat(64),4100,"b".repeat(64))
    private fun read(title:String,artist:String)=MetadataFieldReadReport(
        MetadataPhysicalContainer.WAV,MetadataReadFieldCatalog.PARSER_VERSION,
        MetadataReadFieldCatalog.primary.associateWith { field ->
            when(field){
                MetadataField.TITLE -> MetadataFieldObservation.Present(listOf(title))
                MetadataField.ARTIST -> MetadataFieldObservation.Present(listOf(artist))
                else -> MetadataFieldObservation.Unverifiable(MetadataObservationIssue.FIELD_NOT_SUPPORTED)
            }
        })
    @Test fun exactWavIntentVerifiesSupportedFieldsButNeverGrantsSourceWrite(){
        val receipt=receipt()
        assertTrue(MetadataCommitIntentManifest.valid(receipt))
        val result=MetadataCandidateSemanticPolicy.compare(receipt,
            read("Old","Singer"),read("New","Singer"))
        assertTrue(result is MetadataCandidateSemanticPolicy.Verdict.ObservedFieldsMatch)
        assertFalse((result as MetadataCandidateSemanticPolicy.Verdict.ObservedFieldsMatch)
            .sourceWriteCertified)
    }
    @Test fun rejectsUneditedWavArtistDrift(){
        val result=MetadataCandidateSemanticPolicy.compare(receipt(),
            read("Old","Singer"),read("New","Imposter"))
        assertEquals(MetadataCandidateSemanticPolicy.Refusal.UNEDITED_FIELD_CHANGED,
            (result as MetadataCandidateSemanticPolicy.Verdict.Refused).reason)
    }
    @Test fun separatelyChecksColdPostSourceReadback(){
        val compared=MetadataPostSourceFieldPolicy.compare(receipt(),read("New","Singer"))
        assertEquals(1,(compared as MetadataPostSourceFieldPolicy.Verdict.Compared).verifiedChanges)
        assertFalse(compared.indexPublicationAllowed)
        val mismatched=MetadataPostSourceFieldPolicy.compare(receipt(),read("Wrong","Singer"))
        assertEquals(MetadataPostSourceFieldPolicy.Refusal.CHANGED_FIELD_MISMATCH,
            (mismatched as MetadataPostSourceFieldPolicy.Verdict.Blocked).reason)
    }
}
