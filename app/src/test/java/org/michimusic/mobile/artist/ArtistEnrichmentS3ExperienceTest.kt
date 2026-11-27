package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure UX contract tests: not Android, Compose, provider, or Room instrumentation. */
class ArtistEnrichmentS3ExperienceTest {
    private fun overview(
        identified: Boolean = true, canSave: Boolean = true,
        mosaic: Boolean = false, hero: Boolean = false,
        lookup: Boolean = false, providerError: Boolean = false,
        storageError: Boolean = false, diagnosed: Boolean = false,
    ) = artistS3Overview(ArtistS3JourneyFacts(
        identified, canSave, mosaic, hero, lookup, providerError, storageError, diagnosed,
    ))

    private fun picker(
        canSearch: Boolean = true, consent: Boolean = false,
        searching: Boolean = false, busy: Boolean = false,
        total: Int = 0, visible: Int = 0, preview: Boolean = false,
        replacement: Boolean = false, accepted: Boolean = false,
        identity: Boolean = false, destination: Boolean = false,
        focus: Boolean = false, message: Boolean = false,
    ) = artistS3Picker(ArtistS3PickerFacts(
        canSearch, consent, searching, busy, total, visible, preview,
        replacement, accepted, identity, destination, focus, message,
    ))

    @Test fun identityAlwaysWinsOverSavedPhotos() {
        assertEquals(ArtistS3JourneyPhase.IDENTIFY, overview(identified=false,mosaic=true,hero=true).phase)
    }
    @Test fun scopeReadOnlyPrecedesSearch() {
        assertEquals(ArtistS3JourneyPhase.READ_ONLY, overview(canSave=false,lookup=true).phase)
    }
    @Test fun lookupStatusDoesNotClaimAnySaving() {
        val status = overview(lookup=true)
        assertEquals(ArtistS3JourneyPhase.LOOKUP_RUNNING,status.phase)
        assertTrue(status.explanation.contains("no se ha guardado"))
    }
    @Test fun completeOnlyWhenBothIndependentSlotsSaved() {
        assertEquals(ArtistS3JourneyPhase.PHOTOS_READY,overview(mosaic=true,hero=true).phase)
        assertEquals(ArtistS3JourneyPhase.READY_TO_SEARCH,overview(mosaic=true).phase)
        assertEquals(ArtistS3JourneyPhase.READY_TO_SEARCH,overview(hero=true).phase)
    }
    @Test fun storageErrorIsNotCalledProviderError() {
        assertEquals(ArtistS3JourneyPhase.STORAGE_PROBLEM,overview(storageError=true).phase)
        assertEquals(ArtistS3JourneyPhase.PROVIDER_PROBLEM,overview(providerError=true).phase)
    }
    @Test fun errorDoesNotDisplaceIdentityReview() {
        assertEquals(ArtistS3JourneyPhase.IDENTIFY,overview(identified=false,storageError=true).phase)
    }
    @Test fun diagnosedDoesNotClaimSavedImage() {
        val status = overview(diagnosed=true)
        assertEquals(ArtistS3JourneyPhase.READY_TO_SEARCH,status.phase)
        assertTrue(status.explanation.contains("no guarda"))
    }
    @Test fun idlePickerExplainsConsent() {
        assertEquals(ArtistS3JourneyPhase.READY_TO_SEARCH,picker().phase)
    }
    @Test fun unavailableSearchCannotTriggerNetwork() {
        assertEquals(ArtistS3JourneyPhase.READ_ONLY,picker(canSearch=false).phase)
    }
    @Test fun consentBeforeSearchHasUniqueCopy() {
        assertEquals(ArtistS3JourneyPhase.LOOKUP_CONSENT,picker(consent=true).phase)
    }
    @Test fun savingDominatesEveryOtherPickerStatus() {
        assertEquals(ArtistS3JourneyPhase.SAVING,picker(busy=true,consent=true,preview=true).phase)
    }
    @Test fun cropPreviewDoesNotImplyCommit() {
        val status = picker(focus=true)
        assertEquals(ArtistS3JourneyPhase.EDIT_CROP,status.phase)
        assertTrue(status.explanation.contains("no modifica"))
    }
    @Test fun previewNeedsExplicitConfirmation() {
        assertEquals(ArtistS3JourneyPhase.PREVIEW,picker(preview=true,identity=false,destination=false).phase)
    }
    @Test fun replacementHasOwnReviewStep() {
        assertEquals(ArtistS3JourneyPhase.REPLACEMENT_REVIEW,picker(preview=true,replacement=true).phase)
        assertEquals(ArtistS3JourneyPhase.PREVIEW,picker(preview=true,replacement=true,accepted=true).phase)
    }
    @Test fun visibleCandidateCounterNeverClaimsMoreThanTotal() {
        val status=picker(total=3,visible=15)
        assertTrue(status.explanation.contains("3 de 3"))
        assertEquals(ArtistS3JourneyPhase.REVIEW_CANDIDATES,status.phase)
    }
    @Test fun galleryDoesNotClaimCandidatesAreSaved() {
        assertTrue(picker(total=4,visible=4).title.contains("todavía no guardadas"))
    }
    @Test fun genericMessageNeverImpliesNoMatchOrNoWrites() {
        val status=picker(message=true)
        assertEquals(ArtistS3JourneyPhase.OPERATION_RESULT,status.phase)
        assertFalse(status.explanation.contains("no se modificaron"))
    }
    @Test fun savingCropTakesPrecedenceOverEditing() {
        assertEquals(ArtistS3JourneyPhase.SAVING,picker(focus=true,busy=true).phase)
    }
    @Test fun noEmptyMessageIsNotUnverifiedFailure() {
        assertEquals(ArtistS3JourneyPhase.READY_TO_SEARCH,picker().phase)
    }
    @Test fun purposeKeepsSlotsIndependent() {
        assertTrue(ArtistS3SlotRole.MOSAIC.accessibility.contains("Independiente"))
        assertTrue(ArtistS3SlotRole.HERO.accessibility.contains("Independiente"))
    }
    @Test fun previewNeedsCandidateDestinationAndIdentity() {
        val requirements=ArtistS3PreviewRequirements(false,false,false,true,false,false)
        assertEquals(4,requirements.unmet.size)
        assertTrue(requirements.description.contains("Confirma la identidad"))
    }
    @Test fun previewReadyNeverAuthorizesWriteByItself() {
        val requirements=ArtistS3PreviewRequirements(true,true,true,false,false,false)
        assertTrue(requirements.unmet.isEmpty())
        assertTrue(requirements.description.contains("Guardar"))
    }
    @Test fun replacementRequiresAcknowledgment() {
        assertEquals(1,ArtistS3PreviewRequirements(true,true,true,true,false,false).unmet.size)
        assertTrue(ArtistS3PreviewRequirements(true,true,true,true,true,false).unmet.isEmpty())
    }
    @Test fun busyPreviewIsNotReadiness() {
        val r=ArtistS3PreviewRequirements(true,true,true,false,false,true)
        assertFalse(r.unmet.isEmpty())
    }
    @Test fun singleColumnFor320AndLargeType() {
        assertEquals(ArtistS3LayoutMode.SINGLE_COLUMN,artistS3Layout(320,1f))
        assertEquals(ArtistS3LayoutMode.SINGLE_COLUMN,artistS3Layout(390,2f))
        assertEquals(ArtistS3LayoutMode.SINGLE_COLUMN,artistS3Layout(600,2f))
    }
    @Test fun twoColumnsAt390And412NormalType() {
        assertEquals(ArtistS3LayoutMode.SINGLE_COLUMN,artistS3Layout(360,1f))
        assertEquals(ArtistS3LayoutMode.TWO_COLUMNS,artistS3Layout(390,1f))
        assertEquals(ArtistS3LayoutMode.TWO_COLUMNS,artistS3Layout(412,1f))
        assertEquals(ArtistS3LayoutMode.TWO_COLUMNS,artistS3Layout(600,1.5f))
        assertEquals(ArtistS3LayoutMode.SINGLE_COLUMN,artistS3Layout(600,1.6f))
    }
    @Test fun invalidScaleFailsConservatively() {
        assertEquals(ArtistS3LayoutMode.SINGLE_COLUMN,artistS3Layout(600,Float.NaN))
        assertEquals(ArtistS3LayoutMode.SINGLE_COLUMN,artistS3Layout(600,Float.POSITIVE_INFINITY))
    }
    @Test fun candidateProfileIsNeverConfirmedByMbidAlone() {
        val id = "bd6d4f63-5cd0-4d78-a292-167997794c21"
        assertFalse(artistS3IdentityIsConfirmed(id, null, false, false))
        assertFalse(artistS3IdentityIsConfirmed(id, id, false, false))
    }
    @Test fun persistedIdentityMustMatchExactCanonicalMbid() {
        val id = "bd6d4f63-5cd0-4d78-a292-167997794c21"
        assertTrue(artistS3IdentityIsConfirmed(id, id.uppercase(), true, false))
        assertFalse(artistS3IdentityIsConfirmed(id, "5f0f1b1a-0000-4000-8000-000000000001", true, false))
    }
    @Test fun incompleteMembershipCannotShowConfirmedIdentity() {
        val id = "bd6d4f63-5cd0-4d78-a292-167997794c21"
        assertFalse(artistS3IdentityIsConfirmed(id, id, true, true))
        assertFalse(artistS3IdentityIsConfirmed("bad-id", "bad-id", true, false))
    }
    @Test fun providerNamesAreNotInternalEnums() {
        assertEquals("Wikimedia Commons", artistS3ProviderLabel("COMMONS_P18"))
        assertEquals("Wikimedia Commons", artistS3ProviderLabel("COMMONS_GALLERY"))
        assertEquals("Wikipedia", artistS3ProviderLabel("WIKIPEDIA_PAGEIMAGE"))
        assertEquals("fanart.tv", artistS3ProviderLabel("FANART_TV"))
        assertEquals("Fuente no identificada", artistS3ProviderLabel("UNKNOWN"))
    }
    @Test fun touchTargetRemainsAtLeast48dp() {assertTrue(ARTIST_S3_MIN_TOUCH_TARGET_DP>=48)}
    @Test fun everyPhaseCanProvideAccessibleDescription() {
        val states=listOf(overview(identified=false), overview(), picker(),picker(preview=true))
        assertTrue(states.all { it.accessibilityText.contains(it.title) && it.accessibilityText.contains(it.step) })
    }

    @Test fun everyMissingCoverageStateHasOneTypedRecoveryAndNoRemoteAction() {
        val states = org.michimusic.mobile.artist.artwork.ArtistArtworkCoverageState.entries
        for (state in states) {
            val recovery = artistS3Recovery(state)
            if (state.name == "READY" || state.name == "MANUAL_PROTECTED") assertEquals(null, recovery)
            else {
                requireNotNull(recovery)
                assertTrue(recovery.explanation.isNotBlank())
                assertTrue(recovery.actionLabel.isNotBlank())
                assertEquals(if (state.name == "IDENTITY_UNVERIFIED") ArtistS3RecoveryRoute.REVIEW_IDENTITY
                    else ArtistS3RecoveryRoute.OPEN_PICKER, recovery.route)
            }
        }
    }
    @Test fun networkStorageLicenseAndQidRecoveryAreDistinct() {
        val states = listOf("NETWORK_FAILED", "STORAGE_FAILED", "LICENSE_REJECTED", "QID_MISSING")
        val copy = states.map { requireNotNull(artistS3Recovery(
            org.michimusic.mobile.artist.artwork.ArtistArtworkCoverageState.valueOf(it))) }
        assertEquals(4, copy.map { it.actionLabel }.distinct().size)
        assertEquals(4, copy.map { it.explanation }.distinct().size)
        assertTrue(copy[3].explanation.contains("consentimiento"))
    }
}
