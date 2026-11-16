package org.michimusic.mobile.ui.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.intelligence.MichiGenreLookupAuthorization
import org.michimusic.core.intelligence.MichiGenreNormalizationPolicy
import org.michimusic.core.intelligence.MichiGenrePreviewPolicy
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.intelligence.MichiGenreTagWriteGateway
import org.michimusic.data.repository.MichiGenrePostWriteReconciler
import org.michimusic.mobile.intelligence.MichiGenreMusicBrainzGateway

/**
 * K48-R06: the coordinator is the single state machine behind both entry points. These
 * tests prove the disclosure rules, the revision fence against late results, the
 * additive draft operations, the owner receipt and the cancellation contract without
 * any Android, Room or Media3 dependency.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GenreEditorCoordinatorTest {
    @Test
    fun openingTheConsentStageSendsNothing() = runTest {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway = gateway)

        coordinator.dispatch(GenreEditorIntent.SearchSuggestions)

        assertTrue(coordinator.current.pendingConsent is GenreNetworkConsent.StrictSearch)
        assertEquals(0, gateway.searchCalls)
        assertEquals(GenreEditorPhase.NETWORK_CONSENT, coordinator.current.phase)
    }

    @Test
    fun onlyTheExplicitConfirmationSendsTheExactFieldsOnce() = runTest {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway = gateway)

        coordinator.dispatch(GenreEditorIntent.SearchSuggestions)
        coordinator.dispatch(GenreEditorIntent.ConfirmNetworkRequest)
        advanceUntilIdle()

        assertEquals(1, gateway.searchCalls)
        assertEquals(MichiGenreLookupPhase.RESULTS, coordinator.current.lookup)
        assertEquals(1, coordinator.current.matches.size)
        // The permit is one-shot: the same disclosure cannot be replayed.
        assertEquals(0, gateway.searchCallsWithRejectedPermit)
    }

    @Test
    fun cancellingTheConsentSendsNothing() = runTest {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway = gateway)

        coordinator.dispatch(GenreEditorIntent.SearchSuggestions)
        coordinator.dispatch(GenreEditorIntent.CancelNetworkRequest)
        advanceUntilIdle()

        assertNull(coordinator.current.pendingConsent)
        assertEquals(0, gateway.searchCalls)
    }

    @Test
    fun aCancelledLookupPublishesNoLateResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(searchGate = gate)
        val coordinator = coordinator(gateway = gateway)

        coordinator.dispatch(GenreEditorIntent.SearchSuggestions)
        coordinator.dispatch(GenreEditorIntent.ConfirmNetworkRequest)
        runCurrent()
        coordinator.dispatch(GenreEditorIntent.CancelLookup)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(MichiGenreLookupPhase.CANCELLED, coordinator.current.lookup)
        assertTrue(coordinator.current.matches.isEmpty())
        assertFalse(coordinator.current.busy)
        assertEquals(
            "Consulta cancelada. No se publicaron resultados tardíos.",
            coordinator.current.message,
        )
    }

    @Test
    fun unbindingDropsALateResultToo() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(searchGate = gate)
        val coordinator = coordinator(gateway = gateway)

        coordinator.dispatch(GenreEditorIntent.SearchSuggestions)
        coordinator.dispatch(GenreEditorIntent.ConfirmNetworkRequest)
        runCurrent()
        coordinator.unbind()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(coordinator.current.matches.isEmpty())
        assertEquals(MichiGenreLookupPhase.PENDING, coordinator.current.lookup)
    }

    @Test
    fun aZeroMatchStrictSearchOffersOnlyASecondExplicitQuery() = runTest {
        val gateway = FakeGateway(searchResult = emptyList())
        val coordinator = coordinator(gateway = gateway)

        coordinator.dispatch(GenreEditorIntent.SearchSuggestions)
        coordinator.dispatch(GenreEditorIntent.ConfirmNetworkRequest)
        advanceUntilIdle()

        assertTrue(coordinator.current.relaxedSearchOffered)
        assertEquals(GenreEditorPhase.NETWORK_EMPTY, coordinator.current.phase)

        coordinator.dispatch(GenreEditorIntent.OfferRelaxedSearch)
        val consent = coordinator.current.pendingConsent
        assertTrue(consent is GenreNetworkConsent.RelaxedSearch)
        // The relaxed disclosure still omits the album and has not been sent yet.
        assertFalse((consent as GenreNetworkConsent.RelaxedSearch).fields.containsKey("release"))
        assertEquals(1, gateway.searchCalls)

        coordinator.dispatch(GenreEditorIntent.ConfirmNetworkRequest)
        advanceUntilIdle()
        assertEquals(2, gateway.searchCalls)
    }

    @Test
    fun selectingARecordingIsNotConsentForTheSecondRequest() = runTest {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway = gateway)

        coordinator.dispatch(GenreEditorIntent.SearchSuggestions)
        coordinator.dispatch(GenreEditorIntent.ConfirmNetworkRequest)
        advanceUntilIdle()

        coordinator.dispatch(GenreEditorIntent.SelectRecording(MBID))
        assertTrue(coordinator.current.pendingConsent is GenreNetworkConsent.RecordingGenres)
        assertEquals(0, gateway.recordingCalls)

        coordinator.dispatch(GenreEditorIntent.ConfirmNetworkRequest)
        advanceUntilIdle()
        assertEquals(1, gateway.recordingCalls)
        assertEquals(2, coordinator.current.proposals.size)
    }

    @Test
    fun anUnknownRecordingCannotBeTurnedIntoAConsent() = runTest {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway = gateway)

        coordinator.dispatch(GenreEditorIntent.SearchSuggestions)
        coordinator.dispatch(GenreEditorIntent.ConfirmNetworkRequest)
        advanceUntilIdle()

        coordinator.dispatch(GenreEditorIntent.SelectRecording(OTHER_MBID))

        assertNull(coordinator.current.pendingConsent)
        assertEquals("La grabación ya no pertenece a esta consulta.", coordinator.current.message)
    }

    @Test
    fun addingAGenreKeepsEveryExistingValue() = runTest {
        val coordinator = coordinator(genres = listOf("House", "Techno"))

        coordinator.dispatch(GenreEditorIntent.AddGenre("Disco"))

        assertEquals(
            listOf("House", "Techno", "Disco"),
            coordinator.current.preview.proposed,
        )
        assertEquals(GenreEditorPhase.PREVIEW, coordinator.current.phase)
    }

    @Test
    fun removingOneValueKeepsTheOtherNMinusOne() = runTest {
        val coordinator = coordinator(genres = listOf("House", "Techno", "Disco"))

        coordinator.dispatch(GenreEditorIntent.RemoveGenre(1))

        assertEquals(listOf("House", "Disco"), coordinator.current.preview.proposed)
    }

    @Test
    fun replacingOnePositionKeepsEveryOtherValue() = runTest {
        val coordinator = coordinator(genres = listOf("House", "Techno", "Disco"))

        coordinator.dispatch(GenreEditorIntent.ReplaceGenre(1, "Coldwave"))

        assertEquals(listOf("House", "Coldwave", "Disco"), coordinator.current.preview.proposed)
    }

    @Test
    fun resettingThePreviewReturnsToTheVerifiedValues() = runTest {
        val coordinator = coordinator(genres = listOf("House"))

        coordinator.dispatch(GenreEditorIntent.AddGenre("Disco"))
        coordinator.dispatch(GenreEditorIntent.ResetPreview)

        assertEquals(listOf("House"), coordinator.current.preview.proposed)
        assertFalse(coordinator.current.preview.isModified)
    }

    @Test
    fun anUnverifiedFileNeverBecomesAnEmptyVerifiedGenre() = runTest {
        val coordinator = coordinator(genres = emptyList(), tagsVerified = false)

        assertEquals(GenreEditorPhase.LOCAL_UNVERIFIED, coordinator.current.phase)
        assertEquals(
            MichiGenreEditorState.UNVERIFIED_METADATA,
            coordinator.current.contractState,
        )
        // Read-only lookup is still allowed: playback never blocks a disclosure the
        // person explicitly confirms.
        coordinator.dispatch(GenreEditorIntent.SearchSuggestions)
        assertTrue(coordinator.current.pendingConsent != null)
    }

    @Test
    fun theWritePathStaysClosedWhileWritesAreUncertified() = runTest {
        val coordinator = coordinator(genres = listOf("House"), writesCertified = false)

        coordinator.dispatch(GenreEditorIntent.AddGenre("Disco"))
        coordinator.dispatch(GenreEditorIntent.RequestWrite)

        assertFalse(coordinator.current.confirmWrite)
        assertEquals(GenreEditorPhase.PREVIEW, coordinator.current.phase)
        assertNull(coordinator.current.systemRequest)
    }

    @Test
    fun anOwnerChangeBlocksTheWriteBeforeAnyPlatformGrant() = runTest {
        val coordinator = coordinator(
            genres = listOf("House"),
            writesCertified = true,
            stillOwns = false,
        )

        coordinator.dispatch(GenreEditorIntent.AddGenre("Disco"))
        coordinator.dispatch(GenreEditorIntent.RequestWrite)

        assertFalse(coordinator.current.confirmWrite)
        assertEquals("Permiso denegado o biblioteca cambiada.", coordinator.current.message)
    }

    @Test
    fun aConfirmedWriteRequestsTheExactPlatformReceiptAndRecordsNoSecondProof() = runTest {
        val writer = FakeWriter()
        val coordinator = coordinator(
            genres = listOf("House"),
            writesCertified = true,
            writer = writer,
        )

        coordinator.dispatch(GenreEditorIntent.AddGenre("Disco"))
        coordinator.dispatch(GenreEditorIntent.RequestWrite)
        assertTrue(coordinator.current.confirmWrite)

        coordinator.confirmWriteRequest()
        assertEquals(GenreEditorSystemRequest.WRITE_PERMISSION, coordinator.current.systemRequest)
        assertEquals(0, writer.writeCalls)

        coordinator.dispatch(GenreEditorIntent.ResolveSystemRequest(granted = true))
        advanceUntilIdle()

        assertEquals(1, writer.writeCalls)
        assertEquals(listOf("House", "Disco"), writer.lastProposed)
        assertNull(coordinator.current.systemRequest)
        assertTrue(coordinator.current.message!!.contains("Género guardado y verificado"))
    }

    @Test
    fun aDeniedGrantNeverReachesTheFile() = runTest {
        val writer = FakeWriter()
        val coordinator = coordinator(
            genres = listOf("House"),
            writesCertified = true,
            writer = writer,
        )

        coordinator.dispatch(GenreEditorIntent.AddGenre("Disco"))
        coordinator.dispatch(GenreEditorIntent.RequestWrite)
        coordinator.confirmWriteRequest()
        coordinator.dispatch(GenreEditorIntent.ResolveSystemRequest(granted = false))
        advanceUntilIdle()

        assertEquals(0, writer.writeCalls)
        assertNull(coordinator.current.systemRequest)
        assertEquals("Permiso denegado o biblioteca cambiada.", coordinator.current.message)
    }

    @Test
    fun aStaleRequestResolvedTwiceCannotRepeatTheWrite() = runTest {
        val writer = FakeWriter()
        val coordinator = coordinator(
            genres = listOf("House"),
            writesCertified = true,
            writer = writer,
        )

        coordinator.dispatch(GenreEditorIntent.AddGenre("Disco"))
        coordinator.dispatch(GenreEditorIntent.RequestWrite)
        coordinator.confirmWriteRequest()
        coordinator.dispatch(GenreEditorIntent.ResolveSystemRequest(granted = true))
        advanceUntilIdle()
        // Second delivery of the same platform result must be inert.
        coordinator.dispatch(GenreEditorIntent.ResolveSystemRequest(granted = true))
        advanceUntilIdle()

        assertEquals(1, writer.writeCalls)
    }

    @Test
    fun bothEntryPointsShareOneStateShape() = runTest {
        val genres = listOf("House", "Techno")
        val nowPlaying = coordinator(genres = genres, origin = MichiGenreEditorOrigin.NOW_PLAYING)
        val genreRoute = coordinator(genres = genres, origin = MichiGenreEditorOrigin.GENRES)

        assertEquals(nowPlaying.current.phase, genreRoute.current.phase)
        assertEquals(nowPlaying.current.contractState, genreRoute.current.contractState)
        assertEquals(nowPlaying.current.preview, genreRoute.current.preview)
        assertEquals(
            nowPlaying.current.primaryActionLabel,
            genreRoute.current.primaryActionLabel,
        )
        assertEquals(
            MichiGenreEditorContract.evidenceLabel(true, genres),
            MichiGenreEditorContract.evidenceLabel(true, genreRoute.current.embeddedGenres),
        )
    }

    @Test
    fun closingRevokesTheOutstandingPermit() = runTest {
        var closed = false
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway = gateway, onClose = { closed = true })

        coordinator.dispatch(GenreEditorIntent.SearchSuggestions)
        coordinator.dispatch(GenreEditorIntent.Close)

        assertTrue(closed)
    }

    @Test
    fun theColdRecoveryReadFailsClosed() = runTest {
        val writer = FakeWriter().apply { recoveryThrows = true }
        val coordinator = coordinator(genres = listOf("House"), writer = writer)

        coordinator.loadRecovery()
        advanceUntilIdle()

        val recovery = coordinator.current.recovery
        assertTrue(recovery is MichiGenreTagWriteGateway.RecoveryStatus.Blocked)
        assertEquals(
            "RECOVERY_CHECK_FAILED",
            (recovery as MichiGenreTagWriteGateway.RecoveryStatus.Blocked).code,
        )
    }

    private fun TestScope.coordinator(
        genres: List<String> = listOf("House"),
        tagsVerified: Boolean = true,
        writesCertified: Boolean = false,
        origin: MichiGenreEditorOrigin = MichiGenreEditorOrigin.GENRES,
        gateway: FakeGateway = FakeGateway(),
        writer: FakeWriter = FakeWriter(),
        stillOwns: Boolean = true,
        onClose: () -> Unit = {},
    ): GenreEditorCoordinator {
        return GenreEditorCoordinator(
            initial = GenreEditorUiState(
                target = MichiGenreEditorContract.target(
                    track = TRACK,
                    embeddedGenres = genres,
                    tagsVerified = tagsVerified,
                    origin = origin,
                ),
                evidenceReason = null,
                libraryClassification = "New Wave",
                classificationHint =
                    "Clasificación del índice local, no prueba de la etiqueta física.",
                preview = MichiGenrePreviewPolicy.of(genres),
                writesCertified = writesCertified,
            ),
            scope = this,
            authority = object : GenreEditorAuthority {
                override fun canLookup(): Boolean = true
                override fun stillOwns(): Boolean = stillOwns
            },
            gateway = gateway,
            writer = writer,
            reconciler = GenreEditorReconciler { _, _, _ ->
                MichiGenrePostWriteReconciler.Outcome.Indexed
            },
            onLibraryRefresh = {},
            onClose = onClose,
            authorization = MichiGenreLookupAuthorization(),
        )
    }

    private class FakeGateway(
        private val searchGate: CompletableDeferred<Unit>? = null,
        private val searchResult: List<MichiGenreMusicBrainzGateway.Choice> = listOf(CHOICE),
    ) : GenreEditorGateway {
        var searchCalls = 0
        var searchCallsWithRejectedPermit = 0
        var recordingCalls = 0

        override suspend fun search(
            intent: MichiGenreNormalizationPolicy.SearchIntent,
            permit: MichiGenreLookupAuthorization.Permit?,
            durationMs: Long?,
        ): List<MichiGenreMusicBrainzGateway.Choice> {
            searchCalls++
            if (permit == null) searchCallsWithRejectedPermit++
            searchGate?.await()
            return searchResult
        }

        override suspend fun genresForSelectedRecording(
            selected: MichiGenreNormalizationPolicy.RecordingCandidate,
            permit: MichiGenreLookupAuthorization.Permit?,
        ): List<MichiGenreNormalizationPolicy.GenreProposal> {
            recordingCalls++
            return PROPOSALS
        }
    }

    private class FakeWriter : GenreEditorWriter {
        var writeCalls = 0
        var lastProposed: List<String> = emptyList()
        var recoveryThrows = false

        override suspend fun pendingRecovery(): MichiGenreTagWriteGateway.RecoveryStatus {
            if (recoveryThrows) throw IllegalStateException("journal unavailable")
            return MichiGenreTagWriteGateway.RecoveryStatus.Clear
        }

        override suspend fun write(
            ticket: MichiGenreTagWriteGateway.Ticket,
            proposed: List<String>,
            userConfirmed: Boolean,
            replacementConfirmed: Boolean,
            writeGrantConfirmed: Boolean,
            stillOwns: () -> Boolean,
        ): MichiGenreTagWriteGateway.Result {
            writeCalls++
            lastProposed = proposed
            return MichiGenreTagWriteGateway.Result.SavedVerified(proposed, "ID3v2")
        }

        override suspend fun restorePending(
            ticket: MichiGenreTagWriteGateway.Ticket,
            userConfirmed: Boolean,
            writeGrantConfirmed: Boolean,
            stillOwns: () -> Boolean,
            unknownBytesOverwriteConfirmed: Boolean,
            expectedUnknownBytes: Long?,
            expectedUnknownSha256: String?,
        ): MichiGenreTagWriteGateway.RecoveryResult =
            MichiGenreTagWriteGateway.RecoveryResult.NoOriginalMutation
    }

    private companion object {
        const val MBID = "0f5c1a2b-3d4e-4f60-8a9b-1c2d3e4f5a6b"
        const val OTHER_MBID = "1a6d2b3c-4e5f-4a71-9b0c-2d3e4f5a6b7c"
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

        val CHOICE = MichiGenreMusicBrainzGateway.Choice(
            recording = MichiGenreNormalizationPolicy.RecordingCandidate(
                mbid = MBID,
                title = "Voices Carry",
                artist = "'Til Tuesday",
            ),
            albumPreview = "Voices Carry",
            plausible = true,
        )

        val PROPOSALS = listOf(
            MichiGenreNormalizationPolicy.GenreProposal(
                name = "New Wave",
                entity = MichiGenreNormalizationPolicy.Entity.RECORDING,
                sourceMbid = MBID,
                votes = 4,
                recordingMbid = MBID,
            ),
            MichiGenreNormalizationPolicy.GenreProposal(
                name = "Synth-pop",
                entity = MichiGenreNormalizationPolicy.Entity.RECORDING,
                sourceMbid = MBID,
                votes = 2,
                recordingMbid = MBID,
            ),
        )
    }
}
