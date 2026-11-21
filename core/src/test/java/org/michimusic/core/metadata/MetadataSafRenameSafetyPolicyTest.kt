package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MetadataSafRenameSafetyPolicyTest {
    private val snapshot=MetadataSnapshot(MetadataTarget("","track","content://saf/document/track","token"),true,
        mapOf(MetadataField.TITLE to listOf("Alpha")),verifiedFields=setOf(MetadataField.TITLE))
    private val template="%title%"
    private val proposal=(MetadataFilenameToolkit.proposeRename(snapshot,"Old.flac",template) as
        MetadataFilenameToolkit.Result.Success).value
    private val sha="a".repeat(64)
    private fun input()=MetadataSafRenameSafetyPolicy.Input(
        snapshot,template,proposal,"document-track",listOf(
            MetadataSafRenameSafetyPolicy.Child("document-track","Old.flac"),
            MetadataSafRenameSafetyPolicy.Child("other","Other.flac")),
        sha,sha,100L,MetadataDocumentProvider.SAF,
        listingComplete=true,sameParentVerified=true,exactDocumentGrant=true,
        providerSupportsRename=true,playbackClosed=true,ownerCurrent=true,
        journalClear=true,explicitlyConfirmed=true)
    @Test fun validCurrentListingIsNotRenamePermission(){
        val out=MetadataSafRenameSafetyPolicy.assess(input()) as
            MetadataSafRenameSafetyPolicy.Result.ReadyForProviderSpecificTrial
        assertEquals("Alpha.flac",out.proposedName)
        assertFalse(out.mayRenameSource)
    }
    @Test fun collisionIgnoresCaseAndUnicodeCompatibility(){
        val x=input().copy(children=input().children+
            MetadataSafRenameSafetyPolicy.Child("collision","ALPHA.FLAC"))
        assertEquals(MetadataSafRenameSafetyPolicy.Result.Blocked(
            MetadataSafRenameSafetyPolicy.Refusal.COLLISION),MetadataSafRenameSafetyPolicy.assess(x))
    }
    @Test fun unknownParentOrOwnerOrUnconfirmedBlocks(){
        assertEquals(MetadataSafRenameSafetyPolicy.Result.Blocked(
            MetadataSafRenameSafetyPolicy.Refusal.PARENT_NOT_PROVEN),
            MetadataSafRenameSafetyPolicy.assess(input().copy(listingComplete=false)))
        assertEquals(MetadataSafRenameSafetyPolicy.Result.Blocked(
            MetadataSafRenameSafetyPolicy.Refusal.SOURCE_CHANGED),
            MetadataSafRenameSafetyPolicy.assess(input().copy(currentSourceSha256="b".repeat(64))))
        assertEquals(MetadataSafRenameSafetyPolicy.Result.Blocked(
            MetadataSafRenameSafetyPolicy.Refusal.ACTION_REQUIRED),
            MetadataSafRenameSafetyPolicy.assess(input().copy(explicitlyConfirmed=false)))
    }
    @Test fun staleNameAndDuplicatedDocumentCannotProceed(){
        assertEquals(MetadataSafRenameSafetyPolicy.Result.Blocked(
            MetadataSafRenameSafetyPolicy.Refusal.ORIGINAL_NAME_CHANGED),
            MetadataSafRenameSafetyPolicy.assess(input().copy(children=listOf(
                MetadataSafRenameSafetyPolicy.Child("document-track","Changed.flac")))))
        assertEquals(MetadataSafRenameSafetyPolicy.Result.Blocked(
            MetadataSafRenameSafetyPolicy.Refusal.AMBIGUOUS_LISTING),
            MetadataSafRenameSafetyPolicy.assess(input().copy(children=listOf(
                MetadataSafRenameSafetyPolicy.Child("document-track","Old.flac"),
                MetadataSafRenameSafetyPolicy.Child("document-track","Old.flac")))))
    }
}
