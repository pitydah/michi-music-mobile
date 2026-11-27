package org.michimusic.mobile.artist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/** S3 closure: information only, never starts searches, writes, or navigates. */
@Composable
internal fun ArtistS3JourneyMessage(copy: ArtistS3JourneyCopy, tag: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
            .testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(copy.step, color = TertiaryCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(copy.title, color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(copy.explanation, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
internal fun ArtistS3OverviewStatus(state: ArtistEnrichmentUiState) {
    val copy = artistS3Overview(
        ArtistS3JourneyFacts(
            identityConfirmed = state.identityConfirmed,
            canPersist = state.canPersist,
            mosaicSaved = state.mosaic.isSatisfied,
            heroSaved = state.card.isSatisfied,
            lookupRunning = state.searchActive,
            providerProblem = state.providerFailed,
            storageProblem = state.persistence.failed || state.artworkStorageFailed,
            diagnosed = state.diagnosed,
        ),
    )
    ArtistS3JourneyMessage(copy, "artist_s3_overview_status")
}

@Composable
internal fun ArtistS3SlotExplanation(slot: ArtworkSlot) {
    val purpose = when (slot) {
        ArtworkSlot.ARTIST_THUMB -> ArtistS3SlotRole.MOSAIC
        ArtworkSlot.ARTIST_HERO -> ArtistS3SlotRole.HERO
    }
    Text(
        purpose.purpose, color = TextSecondary, fontSize = 12.sp,
        modifier = Modifier.testTag("artist_s3_slot_purpose_${slot.name}"),
    )
}

@Composable
internal fun ArtistS3PickerStatus(state: ArtistArtworkPickerUiState) {
    val facts = ArtistS3PickerFacts(
        canSearch = state.canSearch,
        consentOpen = state.consentOpen,
        searching = state.searching,
        busy = state.busy,
        totalCandidates = state.totalCandidates,
        visibleCandidates = state.candidates.size,
        previewOpen = state.previewCandidate != null,
        replacementRequired = state.previewReplacementRequired,
        replacementAcknowledged = state.previewReplacementAcknowledged,
        identityConfirmedForPreview = state.previewIdentityConfirmed,
        destinationSelected = state.previewDestination != null,
        focusEditorOpen = state.focusDraft != null,
        messagePresent = state.message != null,
    )
    ArtistS3JourneyMessage(artistS3Picker(facts), "artist_s3_picker_status")
}

@Composable
internal fun ArtistS3PreviewRequirementsContent(state: ArtistArtworkPickerUiState) {
    val requirements = ArtistS3PreviewRequirements(
        candidatePresent = state.previewCandidate != null,
        destinationSelected = state.previewDestination != null,
        identityConfirmed = state.previewIdentityConfirmed,
        replacementRequired = state.previewReplacementRequired,
        replacementAccepted = state.previewReplacementAcknowledged,
        operationBusy = state.busy,
    )
    Text(
        requirements.description,
        color = TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().testTag("artist_s3_preview_requirements")
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Restore keyboard focus and viewport to the original local destination after closing.
 * This is not a claim that TalkBack focus is restored on every device. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.artistS3ReturnFocus(requested: Boolean, consumed: () -> Unit): Modifier {
    val requester = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(requested) {
        if (requested) {
            withFrameNanos { }
            try {
                requester.requestFocus()
                bring.bringIntoView()
            } catch (_: IllegalStateException) {
                // The target can leave composition during a library transition.
                // The local task is already closed; never crash or reopen it.
            } finally {
                consumed()
            }
        }
    }
    return this.focusRequester(requester).bringIntoViewRequester(bring).focusable()
}
