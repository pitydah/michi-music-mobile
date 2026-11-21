package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataSafRenameRecoveryPolicyTest {
    private val hash = "a".repeat(64)
    private val tx = "123e4567-e89b-42d3-a456-426614174000"
    private val oldUri = "content://local/doc/old"
    private val newUri = "content://local/doc/new"
    private val parent = "content://local/tree/parent"
    private val intent = MetadataSafRenameRecoveryPolicy.Intent(
        tx, "", "track", oldUri, "doc-old", parent, "Old.flac", "New.flac", hash, 100)
    private val unchanged = MetadataSafRenameRecoveryPolicy.Child(
        "doc-old", oldUri, "Old.flac", 100, hash, true)
    private val renamed = MetadataSafRenameRecoveryPolicy.Child(
        "doc-new", newUri, "New.flac", 100, hash, true)
    private fun observed(vararg children: MetadataSafRenameRecoveryPolicy.Child) =
        MetadataSafRenameRecoveryPolicy.Observation("", "track", parent, true, true, true, true,
            children.toList(), returnedDocumentUri = newUri)
    private fun uncertain(reason: MetadataSafRenameRecoveryPolicy.Reason) =
        MetadataSafRenameRecoveryPolicy.Verdict.Uncertain(reason)

    @Test fun unchangedIsNotAClaimOfRenameSuccess() {
        assertEquals(MetadataSafRenameRecoveryPolicy.Verdict.OriginalVerified(oldUri, "doc-old"),
            MetadataSafRenameRecoveryPolicy.reconcile(intent, observed(unchanged)))
    }
    @Test fun changedIdAndUriWithFullPostreadbackIsAccepted() {
        assertEquals(MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified(newUri, "doc-new"),
            MetadataSafRenameRecoveryPolicy.reconcile(intent, observed(renamed)))
    }
    @Test fun missingDurableProviderResultCannotAdoptUnrelatedSameHashSibling() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.PROVIDER_RESULT_MISSING),
            MetadataSafRenameRecoveryPolicy.reconcile(intent,
                observed(renamed).copy(returnedDocumentUri = null)))
    }
    @Test fun stableIdMayBeRenamedWithDifferentDisplayName() {
        val sameId = renamed.copy(documentId = "doc-old", uri = oldUri)
        assertEquals(MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified(oldUri, "doc-old"),
            MetadataSafRenameRecoveryPolicy.reconcile(intent, observed(sameId)))
    }
    @Test fun twoNamesAreAmbiguousEvenWhenHashesMatch() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.BOTH_NAMES_PRESENT),
            MetadataSafRenameRecoveryPolicy.reconcile(intent, observed(unchanged, renamed)))
    }
    @Test fun missingAfterFailedProviderCallStaysUnknown() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.NO_MATCH),
            MetadataSafRenameRecoveryPolicy.reconcile(intent, observed()))
    }
    @Test fun staleHashAndSizeAreRejected() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.SOURCE_CHANGED),
            MetadataSafRenameRecoveryPolicy.reconcile(intent,
                observed(renamed.copy(sha256 = "b".repeat(64)))))
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.SOURCE_CHANGED),
            MetadataSafRenameRecoveryPolicy.reconcile(intent, observed(renamed.copy(size = 99))))
    }
    @Test fun noHashMeansNoPostcheck() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.POSTREADBACK_MISSING),
            MetadataSafRenameRecoveryPolicy.reconcile(intent,
                observed(renamed.copy(coldReadVerified = false))))
    }
    @Test fun ownerMismatchFailsClosed() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.WRONG_OWNER),
            MetadataSafRenameRecoveryPolicy.reconcile(intent,
                observed(renamed).copy(sourceId = "peer")))
    }
    @Test fun incompleteListingFailsClosed() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.PARENT_UNVERIFIED),
            MetadataSafRenameRecoveryPolicy.reconcile(intent,
                observed(renamed).copy(completeParentListing = false)))
    }
    @Test fun revokedGrantFailsClosed() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.GRANT_UNVERIFIED),
            MetadataSafRenameRecoveryPolicy.reconcile(intent,
                observed(renamed).copy(exactDocumentGrantStillCurrent = false)))
    }
    @Test fun playbackBlocksReconciliation() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.PLAYBACK_ACTIVE),
            MetadataSafRenameRecoveryPolicy.reconcile(intent,
                observed(renamed).copy(playbackClosed = false)))
    }
    @Test fun providerNameAdjustmentIsNotSuccess() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.NO_MATCH),
            MetadataSafRenameRecoveryPolicy.reconcile(intent,
                observed(renamed.copy(displayName = "New (1).flac"))))
    }
    @Test fun duplicateDocumentsAndCrossAuthorityFail() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.AMBIGUOUS_LISTING),
            MetadataSafRenameRecoveryPolicy.reconcile(intent, observed(renamed, renamed)))
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.URI_AUTHORITY_CHANGED),
            MetadataSafRenameRecoveryPolicy.reconcile(intent,
                observed(renamed.copy(uri = "content://other/doc/new"))))
    }
    @Test fun staleDocumentIdLeftUnderThirdNameFailsClosed() {
        val reused = unchanged.copy(displayName = "Other.flac")
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.DOCUMENT_ID_CONFLICT),
            MetadataSafRenameRecoveryPolicy.reconcile(intent, observed(renamed, reused)))
    }
    @Test fun changedOriginalIdWithoutRenameIsNotSafe() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.DOCUMENT_ID_CONFLICT),
            MetadataSafRenameRecoveryPolicy.reconcile(intent,
                observed(unchanged.copy(documentId = "reused-id"))))
    }
    @Test fun caseOnlyOrUnicodeOnlyRenameIsInvalidWithoutExplicitProviderCertification() {
        assertEquals(uncertain(MetadataSafRenameRecoveryPolicy.Reason.INVALID_INTENT),
            MetadataSafRenameRecoveryPolicy.reconcile(
                intent.copy(requestedName = "old.flac"), observed(unchanged)))
    }
}
