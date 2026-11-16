package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.core.intelligence.MichiGenrePreviewPolicy
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.intelligence.MichiGenreTagWriteGateway

/**
 * K48-R06: the visible stage is a pure function of observed evidence. These tests pin
 * both halves of the compatibility gate — the eleven visible phases and the twelve
 * contract states — so a refactor cannot make an unverified file look empty or turn a
 * provider failure into "no match".
 */
class GenreEditorUiStateTest {
    @Test
    fun verifiedFileWithUncertifiedWritesIsWriteDisabled() {
        val state = state(embeddedGenres = listOf("House"))

        assertEquals(GenreEditorPhase.WRITE_DISABLED, state.phase)
        assertEquals(MichiGenreEditorState.WRITE_DISABLED_UNCERTIFIED, state.contractState)
        assertEquals(
            "No disponible: escritura sin certificar",
            state.primaryActionLabel,
        )
    }

    @Test
    fun unverifiedTagsAreNeverPresentedAsAnEmptyGenre() {
        val state = state(tagsVerified = false, embeddedGenres = emptyList())

        assertEquals(GenreEditorPhase.LOCAL_UNVERIFIED, state.phase)
        assertEquals(MichiGenreEditorState.UNVERIFIED_METADATA, state.contractState)
    }

    @Test
    fun aModifiedPreviewOutranksTheRestingLocalStage() {
        val state = state(
            embeddedGenres = listOf("House"),
            preview = MichiGenrePreviewPolicy.Preview(
                original = listOf("House"),
                proposed = listOf("House", "Disco"),
            ),
        )

        assertEquals(GenreEditorPhase.PREVIEW, state.phase)
        assertEquals(MichiGenreEditorState.PREVIEW_CHANGED, state.contractState)
    }

    @Test
    fun aStructurallyInvalidPreviewIsItsOwnStage() {
        val state = state(
            embeddedGenres = listOf("House"),
            preview = MichiGenrePreviewPolicy.Preview(
                original = listOf("House"),
                proposed = (0 until 40).map { "Genre $it" },
            ),
        )

        assertEquals(GenreEditorPhase.PREVIEW_INVALID, state.phase)
        assertEquals("MAX_GENRES", state.preview.structuralError)
    }

    @Test
    fun theConsentStageIsTheOnlyStageThatShowsTheDisclosure() {
        val state = state(
            embeddedGenres = listOf("House"),
            pendingConsent = GenreNetworkConsent.StrictSearch(
                mapOf("title" to "Voices Carry", "artist" to "'Til Tuesday"),
            ),
        )

        assertEquals(GenreEditorPhase.NETWORK_CONSENT, state.phase)
    }

    @Test
    fun aPendingLookupIsDistinctFromResultsAndFromFailures() {
        val pending = state(
            embeddedGenres = listOf("House"),
            lookup = MichiGenreLookupPhase.PENDING,
        )
        assertEquals(GenreEditorPhase.NETWORK_LOADING, pending.phase)
        assertEquals(MichiGenreEditorState.LOOKUP_PENDING, pending.contractState)

        val failed = state(
            embeddedGenres = listOf("House"),
            lookup = MichiGenreLookupPhase.ERROR,
        )
        assertEquals(GenreEditorPhase.NETWORK_ERROR, failed.phase)
        assertEquals(MichiGenreEditorState.LOOKUP_ERROR, failed.contractState)

        val empty = state(
            embeddedGenres = listOf("House"),
            lookup = MichiGenreLookupPhase.RESULTS,
        )
        assertEquals(GenreEditorPhase.NETWORK_EMPTY, empty.phase)

        val cancelled = state(
            embeddedGenres = listOf("House"),
            lookup = MichiGenreLookupPhase.CANCELLED,
        )
        assertEquals(GenreEditorPhase.NETWORK_EMPTY, cancelled.phase)
        assertEquals(MichiGenreEditorState.LOOKUP_CANCELLED, cancelled.contractState)
    }

    @Test
    fun aPendingRecoveryReceiptBlocksTheEditorWithoutHidingTheLocalTruth() {
        val state = state(
            embeddedGenres = listOf("House"),
            recovery = MichiGenreTagWriteGateway.RecoveryStatus.Pending(
                sourceId = "local",
                trackId = "track-1",
                uri = URI,
                phase = "STAGED",
            ),
        )

        // The write facet is what blocks this editor; the local truth is unchanged.
        assertEquals(GenreEditorPhase.WRITE_DISABLED, state.phase)
        assertEquals(MichiGenreEditorState.WRITE_FAILED_RECOVERY_PENDING, state.contractState)
        assertNotNull(state.pendingRecoveryForThisTrack)
    }

    @Test
    fun aRecoveryReceiptFromAnotherTrackNeverBelongsToThisOne() {
        val state = state(
            embeddedGenres = listOf("House"),
            recovery = MichiGenreTagWriteGateway.RecoveryStatus.Pending(
                sourceId = "local",
                trackId = "track-2",
                uri = URI,
                phase = "STAGED",
            ),
        )

        assertNull(state.pendingRecoveryForThisTrack)
    }

    @Test
    fun aColdLocalReadIsItsOwnLoadingStage() {
        val state = state(embeddedGenres = emptyList(), localReadPending = true)

        assertEquals(GenreEditorPhase.LOCAL_LOADING, state.phase)
    }

    @Test
    fun aCertifiedVerifiedEmptyFileIsNotEmptyInTheContractSense() {
        val state = state(
            embeddedGenres = emptyList(),
            writesCertified = true,
        )

        assertEquals(MichiGenreEditorState.VERIFIED_EMPTY_GENRE, state.contractState)
        assertEquals(GenreEditorPhase.LOCAL_VERIFIED, state.phase)
    }

    @Test
    fun everyContractStateIsReachableFromTheNewState() {
        val reached = setOf(
            state(embeddedGenres = listOf("House")).contractState,
            state(tagsVerified = false, embeddedGenres = emptyList()).contractState,
            state(embeddedGenres = emptyList(), writesCertified = true).contractState,
            state(
                embeddedGenres = listOf("House"),
                preview = MichiGenrePreviewPolicy.Preview(listOf("House"), listOf("House", "Disco")),
            ).contractState,
            state(embeddedGenres = listOf("House"), lookup = MichiGenreLookupPhase.PENDING)
                .contractState,
            state(embeddedGenres = listOf("House"), lookup = MichiGenreLookupPhase.CANCELLED)
                .contractState,
            state(embeddedGenres = listOf("House"), lookup = MichiGenreLookupPhase.ERROR)
                .contractState,
            state(
                embeddedGenres = listOf("House"),
                recovery = MichiGenreTagWriteGateway.RecoveryStatus.Pending(
                    "local", "track-1", URI, "STAGED",
                ),
            ).contractState,
            state(embeddedGenres = listOf("House"), writesCertified = true, descriptorOpen = true)
                .contractState,
            state(embeddedGenres = listOf("House"), writesCertified = true).contractState,
        )

        // The write-verified / awaiting-permission states are owned by the write gateway
        // receipt and are asserted directly against the contract in the next test.
        assertTrue(reached.contains(MichiGenreEditorState.WRITE_DISABLED_UNCERTIFIED))
        assertTrue(reached.contains(MichiGenreEditorState.UNVERIFIED_METADATA))
        assertTrue(reached.contains(MichiGenreEditorState.VERIFIED_EMPTY_GENRE))
        assertTrue(reached.contains(MichiGenreEditorState.PREVIEW_CHANGED))
        assertTrue(reached.contains(MichiGenreEditorState.LOOKUP_PENDING))
        assertTrue(reached.contains(MichiGenreEditorState.LOOKUP_CANCELLED))
        assertTrue(reached.contains(MichiGenreEditorState.LOOKUP_ERROR))
        assertTrue(reached.contains(MichiGenreEditorState.WRITE_FAILED_RECOVERY_PENDING))
        assertTrue(reached.contains(MichiGenreEditorState.WRITE_BLOCKED_ACTIVE_DESCRIPTOR))
        assertTrue(reached.contains(MichiGenreEditorState.VERIFIED_GENRES))
    }

    @Test
    fun theWriteFacetsStayOwnedByTheSharedContract() {
        // WRITES_CERTIFIED=false must win over every other reason.
        val uncertified = MichiGenreEditorContract.status(
            tagsVerified = true,
            verifiedGenres = listOf("House"),
            previewModified = false,
            lookup = MichiGenreLookupPhase.IDLE,
            writesCertified = false,
            descriptorOpenInPlayer = true,
        )
        assertEquals(MichiGenreWriteAccess.DISABLED_UNCERTIFIED, uncertified.write)

        val verified = MichiGenreEditorContract.status(
            tagsVerified = true,
            verifiedGenres = listOf("House"),
            previewModified = false,
            lookup = MichiGenreLookupPhase.IDLE,
            writesCertified = true,
            descriptorOpenInPlayer = false,
            writeVerified = true,
        )
        assertEquals(MichiGenreEditorState.WRITE_VERIFIED, verified.state)

        val awaiting = MichiGenreEditorContract.status(
            tagsVerified = true,
            verifiedGenres = listOf("House"),
            previewModified = false,
            lookup = MichiGenreLookupPhase.IDLE,
            writesCertified = true,
            descriptorOpenInPlayer = false,
            awaitingAndroidPermission = true,
        )
        assertEquals(MichiGenreEditorState.WRITE_AWAITING_ANDROID_PERMISSION, awaiting.state)
    }

    @Test
    fun thePreparedSearchCarriesNoPathOrLibraryField() {
        val state = state(embeddedGenres = listOf("House"))
        val intent = state.searchIntent
        assertNotNull(intent)
        val fields = intent!!.outgoingFields()
        assertTrue(fields.keys.any { it.equals("recording", ignoreCase = true) })
        assertTrue(fields.keys.any { it.equals("artist", ignoreCase = true) })
        fields.forEach { (key, value) ->
            assertTrue(
                "field $key must not contain a path",
                !value.contains("/") && !value.contains("content:"),
            )
            assertTrue(
                "field $key must not be a library scope",
                !key.contains("scope", ignoreCase = true) &&
                    !key.contains("library", ignoreCase = true),
            )
        }
    }

    @Test
    fun theStrictSearchIsPreparedForThisExactTrack() {
        val state = state(embeddedGenres = listOf("House"))
        val intent = state.searchIntent!!
        assertTrue(MichiGenreNormalizationPolicy.isPreparedSearch(intent))
        assertEquals("Voices Carry", intent.title)
        assertEquals("'Til Tuesday", intent.artist)
        assertEquals("Voices Carry", intent.album)
    }

    private fun state(
        embeddedGenres: List<String>,
        tagsVerified: Boolean = true,
        preview: MichiGenrePreviewPolicy.Preview = MichiGenrePreviewPolicy.of(embeddedGenres),
        lookup: MichiGenreLookupPhase = MichiGenreLookupPhase.IDLE,
        pendingConsent: GenreNetworkConsent? = null,
        recovery: MichiGenreTagWriteGateway.RecoveryStatus? = null,
        writesCertified: Boolean = false,
        descriptorOpen: Boolean = false,
        localReadPending: Boolean = false,
    ) = GenreEditorUiState(
        target = MichiGenreEditorContract.target(
            track = TRACK,
            embeddedGenres = embeddedGenres,
            tagsVerified = tagsVerified,
            origin = MichiGenreEditorOrigin.NOW_PLAYING,
        ),
        evidenceReason = null,
        libraryClassification = "New Wave",
        classificationHint = "Clasificación del índice local, no prueba de la etiqueta física.",
        localReadPending = localReadPending,
        lookup = lookup,
        preview = preview,
        pendingConsent = pendingConsent,
        recovery = recovery,
        writesCertified = writesCertified,
        descriptorOpenInPlayer = descriptorOpen,
    )

    private companion object {
        const val URI = "content://media/external/audio/media/1"

        val TRACK = Track(
            id = "track-1",
            title = "Voices Carry",
            artist = "'Til Tuesday",
            album = "Voices Carry",
            duration = 240_000,
            size = 8_192,
            filepath = URI,
            source = TrackSource.LOCAL,
            sourceId = "local",
            genre = "New Wave",
        )
    }
}
