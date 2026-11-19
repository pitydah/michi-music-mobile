package org.michimusic.mobile.devices.aircat.experience

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.LinkClient
import org.michimusic.mobile.devices.aircat.AirCatProgressEvent
import org.michimusic.mobile.devices.aircat.AirCatTransferState
import org.michimusic.mobile.devices.aircat.CatalogTrack
import org.michimusic.mobile.devices.aircat.CataloguePreview
import org.michimusic.mobile.devices.aircat.SelectionEntry

/**
 * A71: the experience decides what the user asked for while the engine decides
 * whether it is allowed. These tests only exercise the first half, with a double
 * that records exactly what the screen handed over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AirCatExperienceControllerTest {

    private class FakeEngine : AirCatExperienceEngine {
        val progressFlow = MutableStateFlow<AirCatProgressEvent?>(null)
        override val progress = progressFlow

        var preview: Result<CataloguePreview> = Result.success(catalogue())
        var gate: CompletableDeferred<Unit>? = null
        val explored = mutableListOf<String>()
        var starts = 0
        var lastSelection: List<SelectionEntry>? = null
        var lastResult: ((Boolean, String) -> Unit)? = null
        private val paused = mutableSetOf<String>()

        override suspend fun explore(targetDeviceId: String, currentClient: LinkClient?): Result<CataloguePreview> {
            explored += targetDeviceId
            gate?.await()
            return preview
        }

        override fun start(
            targetDeviceId: String,
            scope: CoroutineScope,
            currentClient: LinkClient?,
            onResult: (Boolean, String) -> Unit,
            selection: List<SelectionEntry>?,
            onTransferIdentified: (String) -> Unit,
        ) {
            starts += 1
            lastSelection = selection
            lastResult = onResult
            onTransferIdentified("xfer-1")
        }

        override fun pause(transferId: String): Boolean {
            paused += transferId
            return true
        }

        override fun resume(transferId: String) {
            paused -= transferId
        }

        override fun cancel(transferId: String) = Unit
        override fun isPaused(transferId: String) = transferId in paused
    }

    companion object {
        private val DIGEST_A = "a".repeat(64)
        private val DIGEST_B = "b".repeat(64)

        private fun track(id: String, album: String): CatalogTrack = CatalogTrack(
            remoteTrackId = id,
            logicalTrackId = "logical-$id",
            title = id,
            artist = "Artist",
            album = album,
            format = "flac",
            size = 100L,
            sha256 = if (album == "Album A") DIGEST_A else DIGEST_B,
        )

        private fun catalogue(): CataloguePreview = CataloguePreview(
            sourceDeviceId = "dev-1",
            deviceName = "Micro Server",
            tracks = listOf(track("rem-1", "Album A"), track("rem-2", "Album A"), track("rem-3", "Album B")),
        )

        private fun source(id: String, canExplore: Boolean = true): AirCatSourceUi = AirCatSourceUi(
            deviceId = id,
            name = "Source $id",
            kindLabel = "Servidor de música",
            discovered = false,
            known = true,
            connected = canExplore,
            homeEnrolled = canExplore,
            airCatAdvertised = canExplore,
        )
    }

    private fun TestScopeController(
        engine: FakeEngine,
        free: Long = 200L * 1024 * 1024,
        sources: List<AirCatSourceUi> = listOf(source("dev-1"), source("dev-2")),
    ) = Triple(engine, free, sources)

    @Test
    fun exploringACatalogueGroupsTheTracksByTheirAlbum() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(UnconfinedTestDispatcher(testScheduler).let { CoroutineScope(it) }, engine, { listOf(source("dev-1")) }, { 200L * 1024 * 1024 })

        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(AirCatPhase.CATALOGUE, state.phase)
        assertEquals(3, state.catalogue?.tracks?.size)
        assertEquals(listOf("Album A", "Album B"), state.visibleAlbums.map { it.album })
        assertEquals(2, state.visibleAlbums.first().trackCount)
    }

    @Test
    fun aSourceThatCannotExploreKeepsTheUserOnThePicker() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(UnconfinedTestDispatcher(testScheduler).let { CoroutineScope(it) }, engine, { listOf(source("dev-1", canExplore = false)) }, { 200L * 1024 * 1024 })

        controller.dispatch(AirCatIntent.OpenSources)
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()

        assertEquals(AirCatPhase.SOURCES, controller.state.value.phase)
        assertTrue(engine.explored.isEmpty())
        assertNotNull("refusing has to say why", controller.state.value.message)
    }

    @Test
    fun theSelectionCarriesTheDigestTheCatalogueShowed() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(UnconfinedTestDispatcher(testScheduler).let { CoroutineScope(it) }, engine, { listOf(source("dev-1")) }, { 200L * 1024 * 1024 })

        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.Review)
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.Confirm)
        advanceUntilIdle()

        val batch = engine.lastSelection
        assertNotNull(batch)
        assertEquals(listOf("rem-1"), batch!!.map { it.remoteTrackId })
        assertEquals("the batch must carry the digest the user saw", DIGEST_A, batch.first().sha256)
        assertEquals("logical-rem-1", batch.first().logicalTrackId)
    }

    @Test
    fun anAlbumTogglesAllOfItsTracksAndTogglesThemBack() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(UnconfinedTestDispatcher(testScheduler).let { CoroutineScope(it) }, engine, { listOf(source("dev-1")) }, { 200L * 1024 * 1024 })
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()

        controller.dispatch(AirCatIntent.ToggleAlbum("Album A"))
        assertEquals(setOf("rem-1", "rem-2"), controller.state.value.selection)

        controller.dispatch(AirCatIntent.ToggleAlbum("Album A"))
        assertTrue(controller.state.value.selection.isEmpty())
    }

    @Test
    fun selectAllOnlyMarksTheTracksTheFilterLeavesVisible() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(UnconfinedTestDispatcher(testScheduler).let { CoroutineScope(it) }, engine, { listOf(source("dev-1")) }, { 200L * 1024 * 1024 })
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()

        controller.dispatch(AirCatIntent.QueryChanged("rem-3"))
        controller.dispatch(AirCatIntent.SelectAll)

        assertEquals(setOf("rem-3"), controller.state.value.selection)
    }

    @Test
    fun anEmptySelectionNeverReachesTheEngine() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(UnconfinedTestDispatcher(testScheduler).let { CoroutineScope(it) }, engine, { listOf(source("dev-1")) }, { 200L * 1024 * 1024 })
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()

        controller.dispatch(AirCatIntent.Review)
        controller.dispatch(AirCatIntent.Confirm)
        advanceUntilIdle()

        assertEquals("zero selected tracks is the user's decision, not a transfer", 0, engine.starts)
        assertEquals(AirCatPhase.CATALOGUE, controller.state.value.phase)
    }

    @Test
    fun withoutRoomForTheBatchConfirmationIsRefused() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(UnconfinedTestDispatcher(testScheduler).let { CoroutineScope(it) }, engine, { listOf(source("dev-1")) }, { 50L })
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.Review)
        advanceUntilIdle()

        assertNotNull("the review has to say there is no room", controller.state.value.storageRefusal)
        controller.dispatch(AirCatIntent.Confirm)
        advanceUntilIdle()
        assertEquals(0, engine.starts)
    }

    @Test
    fun aCatalogueThatArrivesAfterAnotherChoiceIsDiscarded() = runTest {
        val engine = FakeEngine()
        val gate = CompletableDeferred<Unit>()
        engine.gate = gate
        val controller = AirCatExperienceController(UnconfinedTestDispatcher(testScheduler).let { CoroutineScope(it) }, engine, { listOf(source("dev-1"), source("dev-2")) }, { 200L * 1024 * 1024 })

        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        // The user changes their mind while the first catalogue is still in flight.
        engine.gate = null
        controller.dispatch(AirCatIntent.ChooseSource("dev-2"))
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("dev-1", "dev-2"), engine.explored)
        assertEquals("dev-2", controller.state.value.selectedSourceId)
        assertEquals(AirCatPhase.CATALOGUE, controller.state.value.phase)
    }

    @Test
    fun aCompletedTransferBecomesAnHonestResult() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(UnconfinedTestDispatcher(testScheduler).let { CoroutineScope(it) }, engine, { listOf(source("dev-1")) }, { 200L * 1024 * 1024 })
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.Review)
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.Confirm)
        advanceUntilIdle()

        engine.progressFlow.value = AirCatProgressEvent(
            transferId = "xfer-1",
            currentTrackIndex = 1,
            totalTracks = 1,
            bytesTransferred = 100L,
            totalBytes = 100L,
            state = AirCatTransferState.COMPLETED,
            checksumVerified = true,
            libraryCommitted = true,
        )
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(AirCatPhase.RESULT, state.phase)
        assertEquals(AirCatResultUi.Kind.COMPLETED, state.result?.kind)
        assertTrue(state.result!!.detail.contains("1"))
    }

    @Test
    fun noFilesIsNeverReportedAsACompletedTransfer() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(UnconfinedTestDispatcher(testScheduler).let { CoroutineScope(it) }, engine, { listOf(source("dev-1")) }, { 200L * 1024 * 1024 })
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.Review)
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.Confirm)
        advanceUntilIdle()

        engine.progressFlow.value = AirCatProgressEvent(
            transferId = "xfer-1",
            currentTrackIndex = 0,
            totalTracks = 0,
            bytesTransferred = 0L,
            totalBytes = 0L,
            state = AirCatTransferState.NO_FILES,
        )
        advanceUntilIdle()

        assertEquals(AirCatResultUi.Kind.NO_FILES, controller.state.value.result?.kind)
    }

    @Test
    fun aCancelledTransferIsNotAnIntegrityFailure() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(UnconfinedTestDispatcher(testScheduler).let { CoroutineScope(it) }, engine, { listOf(source("dev-1")) }, { 200L * 1024 * 1024 })
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.Review)
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.Confirm)
        advanceUntilIdle()

        engine.progressFlow.value = AirCatProgressEvent(
            transferId = "xfer-1",
            currentTrackIndex = 0,
            totalTracks = 1,
            bytesTransferred = 0L,
            totalBytes = 100L,
            state = AirCatTransferState.CANCELLED,
        )
        advanceUntilIdle()

        assertEquals(AirCatResultUi.Kind.CANCELLED, controller.state.value.result?.kind)
    }

    @Test
    fun dismissingTheResultReturnsToTheHub() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(UnconfinedTestDispatcher(testScheduler).let { CoroutineScope(it) }, engine, { listOf(source("dev-1")) }, { 200L * 1024 * 1024 })
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.Review)
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.Confirm)
        advanceUntilIdle()
        engine.progressFlow.value = AirCatProgressEvent(
            transferId = "xfer-1",
            currentTrackIndex = 1,
            totalTracks = 1,
            bytesTransferred = 100L,
            totalBytes = 100L,
            state = AirCatTransferState.COMPLETED,
            checksumVerified = true,
            libraryCommitted = true,
        )
        advanceUntilIdle()

        controller.dispatch(AirCatIntent.DismissResult)

        assertEquals(AirCatPhase.HUB, controller.state.value.phase)
        assertNull(controller.state.value.result)
        assertNull(controller.state.value.transfer)
    }

    @Test
    fun selectingVisibleTracksPreservesHiddenSelections() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)), engine,
            { listOf(source("dev-1")) }, { 200L * 1024 * 1024 },
        )
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.QueryChanged("rem-3"))
        controller.dispatch(AirCatIntent.SelectAll)
        assertEquals(setOf("rem-1", "rem-3"), controller.state.value.selection)
    }

    @Test
    fun aForeignTransferEventCannotClaimThisScreen() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)), engine,
            { listOf(source("dev-1")) }, { 200L * 1024 * 1024 },
        )
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.Review)
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.Confirm)
        engine.progressFlow.value = AirCatProgressEvent(
            transferId = "xfer-foreign", currentTrackIndex = 1,
            totalTracks = 1, bytesTransferred = 100L, totalBytes = 100L,
            state = AirCatTransferState.COMPLETED,
            checksumVerified = true, libraryCommitted = true,
        )
        advanceUntilIdle()
        assertNull(controller.state.value.result)
        assertEquals(AirCatPhase.TRANSFER, controller.state.value.phase)
    }

    @Test
    fun aBooleanSuccessWithoutStrongReceiptIsNotCompletion() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)), engine,
            { listOf(source("dev-1")) }, { 200L * 1024 * 1024 },
        )
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.Review)
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.Confirm)
        engine.lastResult?.invoke(true, "completada")
        advanceUntilIdle()
        assertEquals(AirCatResultUi.Kind.UNCERTIFIED, controller.state.value.result?.kind)
        assertEquals(AirCatPhase.RESULT, controller.state.value.phase)
    }

    @Test
    fun anUncertifiedCallbackCanBeSupersededByTheExactVerifiedReceipt() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)), engine,
            { listOf(source("dev-1")) }, { 200L * 1024 * 1024 },
        )
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.Review)
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.Confirm)
        engine.lastResult?.invoke(true, "term")
        assertEquals(AirCatResultUi.Kind.UNCERTIFIED, controller.state.value.result?.kind)
        engine.progressFlow.value = AirCatProgressEvent(
            transferId = "xfer-1", currentTrackIndex = 1, totalTracks = 1,
            bytesTransferred = 100L, totalBytes = 100L,
            state = AirCatTransferState.COMPLETED,
            checksumVerified = true, libraryCommitted = true,
        )
        advanceUntilIdle()
        assertEquals(AirCatResultUi.Kind.COMPLETED, controller.state.value.result?.kind)
    }

    @Test
    fun aForeignLastEventIsNotAutomaticallyAdoptedAsOurTransfer() = runTest {
        val engine = FakeEngine()
        engine.progressFlow.value = AirCatProgressEvent(
            transferId = "other-session", currentTrackIndex = 1, totalTracks = 2,
            bytesTransferred = 100L, totalBytes = 200L,
            state = AirCatTransferState.TRANSFERRING,
        )
        val controller = AirCatExperienceController(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)), engine,
            { listOf(source("dev-1")) }, { 200L * 1024 * 1024 },
        )
        assertNull(controller.state.value.transfer)
        assertEquals(AirCatPhase.HUB, controller.state.value.phase)
    }

    @Test
    fun closingDetailsKeepsObservingTheExactRunningTransfer() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)), engine,
            { listOf(source("dev-1")) }, { 200L * 1024 * 1024 },
        )
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.Review)
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.Confirm)
        controller.dispatch(AirCatIntent.Back)
        assertEquals(AirCatPhase.HUB, controller.state.value.phase)
        controller.dispatch(AirCatIntent.OpenActiveTransfer)
        assertEquals(AirCatPhase.TRANSFER, controller.state.value.phase)
        engine.progressFlow.value = AirCatProgressEvent(
            transferId = "xfer-1", currentTrackIndex = 1, totalTracks = 1,
            bytesTransferred = 100L, totalBytes = 100L,
            state = AirCatTransferState.COMPLETED,
            checksumVerified = true, libraryCommitted = true,
        )
        advanceUntilIdle()
        assertEquals(AirCatResultUi.Kind.COMPLETED, controller.state.value.result?.kind)
    }

    @Test
    fun anExactReceiptMayReadOnlyTheSameSourceAndTransferEvidence() = runTest {
        val engine = FakeEngine()
        val requests = mutableListOf<Pair<String, String>>()
        val controller = AirCatExperienceController(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)), engine,
            { listOf(source("dev-1")) }, { 200L * 1024 * 1024 },
            checkpointEvidence = { sourceId, transferId ->
                requests += sourceId to transferId
                emptyList()
            },
        )
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.Review)
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.Confirm)
        engine.progressFlow.value = AirCatProgressEvent(
            transferId = "xfer-1", currentTrackIndex = 1, totalTracks = 1,
            bytesTransferred = 100L, totalBytes = 100L,
            state = AirCatTransferState.COMPLETED,
            checksumVerified = true, libraryCommitted = true,
        )
        advanceUntilIdle()
        assertEquals(listOf("dev-1" to "xfer-1"), requests)
    }

    @Test
    fun anUnknownStorageProbeNeverAllowsConfirmation() = runTest {
        val engine = FakeEngine()
        val controller = AirCatExperienceController(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)), engine,
            { listOf(source("dev-1")) }, { throw IllegalStateException("statfs unavailable") },
        )
        controller.dispatch(AirCatIntent.ChooseSource("dev-1"))
        advanceUntilIdle()
        controller.dispatch(AirCatIntent.ToggleTrack("rem-1"))
        controller.dispatch(AirCatIntent.Review)
        advanceUntilIdle()
        assertEquals(false, controller.state.value.canConfirm)
        controller.dispatch(AirCatIntent.Confirm)
        assertEquals(0, engine.starts)
    }
}
