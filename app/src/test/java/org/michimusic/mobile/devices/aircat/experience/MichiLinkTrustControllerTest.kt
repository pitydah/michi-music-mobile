package org.michimusic.mobile.devices.aircat.experience

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeAuthority
import org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeSource
import org.michimusic.mobile.devices.aircat.AirCatHomeAnchor
import org.michimusic.mobile.devices.aircat.AirCatHomeTrustAdmin

/**
 * A71-09: the trust controller must never turn a `false` from the store into a
 * generic sentence, and must never claim a revocation it cannot prove. These
 * tests drive the real controller against a scripted store so each of the five
 * realities — committed, changed, already gone, failed commit, unreadable
 * Keystore — is observed as a distinct outcome.
 */
class MichiLinkTrustControllerTest {

    private class FakeStore(
        var homes: List<AirCatEnrolledHomeAuthority> = listOf(homeOf(listOf(SOURCE_A))),
        var sources: List<AirCatEnrolledHomeSource> = listOf(sourceOf(SOURCE_A)),
    ) : AirCatHomeTrustAdmin {

        var failReads = false
        var revokeSourceResult = true
        var revokeHomeResult = true
        var lastRevokedSource: AirCatEnrolledHomeSource? = null
        var lastRevokedHome: AirCatEnrolledHomeAuthority? = null
        var sourceRevocations = 0
        var homeRevocations = 0

        override fun enrolledHomes(): List<AirCatEnrolledHomeAuthority> =
            if (failReads) error("keystore unavailable") else homes

        override fun enrolledSources(): List<AirCatEnrolledHomeSource> =
            if (failReads) error("keystore unavailable") else sources

        override fun anchorFor(homeId: String): AirCatHomeAnchor? {
            if (failReads) error("keystore unavailable")
            return homes.firstOrNull { it.homeId == homeId }?.let { home ->
                AirCatHomeAnchor(
                    homeId = home.homeId,
                    authorityMichiId = home.authorityMichiId,
                    authorityPublicKeyBase64Url = home.authorityPublicKeyBase64Url,
                    sourceMichiId = home.homeId,
                    tlsSpkiSha256 = "",
                    pinnedBaseUrl = "",
                )
            }
        }

        override fun anchorForSource(sourceMichiId: String): AirCatHomeAnchor? {
            if (failReads) error("keystore unavailable")
            val record = sources.firstOrNull { it.sourceMichiId == sourceMichiId } ?: return null
            val home = homes.firstOrNull { it.homeId == record.homeId } ?: return null
            return AirCatHomeAnchor(
                homeId = record.homeId,
                authorityMichiId = home.authorityMichiId,
                authorityPublicKeyBase64Url = home.authorityPublicKeyBase64Url,
                sourceMichiId = record.sourceMichiId,
                tlsSpkiSha256 = record.tlsSpkiSha256,
                pinnedBaseUrl = record.pinnedBaseUrl,
            )
        }

        override fun revokeSourceIfUnchanged(expected: AirCatEnrolledHomeSource): Boolean {
            sourceRevocations += 1
            lastRevokedSource = expected
            if (!revokeSourceResult) return false
            sources = sources.filterNot { it.sourceMichiId == expected.sourceMichiId }
            return true
        }

        override fun revokeHomeIfUnchanged(expected: AirCatEnrolledHomeAuthority): Boolean {
            homeRevocations += 1
            lastRevokedHome = expected
            if (!revokeHomeResult) return false
            homes = homes.filterNot { it.homeId == expected.homeId }
            sources = sources.filterNot { it.homeId == expected.homeId }
            return true
        }
    }

    private companion object {
        private const val HOME_A = "home-a"
        private const val SOURCE_A = "source-a"
        private const val SOURCE_B = "source-b"
        private const val PINNED_URL = "https://home.example:8443"

        private fun sourceOf(
            id: String,
            home: String = HOME_A,
            url: String = PINNED_URL,
        ) = AirCatEnrolledHomeSource(
            sourceMichiId = id,
            homeId = home,
            pinnedBaseUrl = url,
            tlsSpkiSha256 = "b".repeat(64),
        )

        private fun homeOf(sourceIds: List<String>, id: String = HOME_A) =
            AirCatEnrolledHomeAuthority(
                homeId = id,
                authorityMichiId = id,
                authorityPublicKeyBase64Url = "authority-key-$id",
                sourceMichiIds = sourceIds,
                sourceBindingsSha256 = "c".repeat(64),
            )
    }

    private fun TestScope.controllerFor(store: FakeStore): MichiLinkTrustController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MichiLinkTrustController(
            scope = CoroutineScope(dispatcher),
            admin = MichiLinkTrustAdmin(store, dispatcher),
        )
    }

    private fun TestScope.load(controller: MichiLinkTrustController) {
        controller.dispatch(MichiLinkTrustIntent.Refresh)
        advanceUntilIdle()
    }

    @Test
    fun theInitialReadExposesTheExactInventory() = runTest {
        val store = FakeStore(sources = listOf(sourceOf(SOURCE_A), sourceOf(SOURCE_B)))
        val controller = controllerFor(store)

        load(controller)

        val state = controller.state.value
        assertEquals(false, state.loading)
        assertNull(state.loadFailure)
        assertEquals(store.homes, state.homes)
        assertEquals(store.sources, state.sources)
    }

    @Test
    fun anUnreadableKeystoreIsNotDrawnAsAnEmptyAuthorisedInventory() = runTest {
        val store = FakeStore().apply { failReads = true }
        val controller = controllerFor(store)

        load(controller)

        val state = controller.state.value
        assertEquals(MichiLinkTrustLoadFailure.KEYSTORE_UNAVAILABLE, state.loadFailure)
        assertTrue(state.homes.isEmpty())
        assertTrue(state.sources.isEmpty())
        assertNull(state.notice)
    }

    @Test
    fun aCommittedSourceRevocationRefreshesTheInventoryAndClaimsSuccess() = runTest {
        val store = FakeStore()
        val controller = controllerFor(store)
        load(controller)
        val expected = store.sources.single()

        controller.dispatch(MichiLinkTrustIntent.RequestSourceRevocation(expected))
        advanceUntilIdle()
        assertEquals(MichiLinkTrustPending.Source(expected), controller.state.value.pending)

        controller.dispatch(MichiLinkTrustIntent.ConfirmRevocation)
        advanceUntilIdle()

        val state = controller.state.value
        assertNull(state.pending)
        assertEquals(MichiLinkTrustOutcome.REVOKED, state.notice?.outcome)
        assertEquals(MichiLinkTrustSubject.SOURCE, state.notice?.subject)
        assertEquals(expected, store.lastRevokedSource)
        assertTrue(state.sources.isEmpty())
    }

    @Test
    fun aRecordThatChangedUnderTheScreenIsAChangeAndNotASuccess() = runTest {
        val store = FakeStore()
        val controller = controllerFor(store)
        load(controller)
        val displayed = store.sources.single()
        // Someone re-enrolled the same source with a different pinned endpoint.
        store.sources = listOf(sourceOf(SOURCE_A, url = "https://changed.example"))

        controller.dispatch(MichiLinkTrustIntent.RequestSourceRevocation(displayed))
        advanceUntilIdle()
        store.revokeSourceResult = false
        controller.dispatch(MichiLinkTrustIntent.ConfirmRevocation)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(MichiLinkTrustOutcome.HOME_CHANGED, state.notice?.outcome)
        assertEquals(store.sources, state.sources)
    }

    @Test
    fun aRecordThatWasAlreadyGoneIsReportedAsAlreadyRemoved() = runTest {
        val store = FakeStore()
        val controller = controllerFor(store)
        load(controller)
        val displayed = store.sources.single()
        store.sources = emptyList()

        controller.dispatch(MichiLinkTrustIntent.RequestSourceRevocation(displayed))
        advanceUntilIdle()
        store.revokeSourceResult = false
        controller.dispatch(MichiLinkTrustIntent.ConfirmRevocation)
        advanceUntilIdle()

        assertEquals(MichiLinkTrustOutcome.ALREADY_REMOVED, controller.state.value.notice?.outcome)
    }

    @Test
    fun aCommitThatDidNotLandIsNotReportedAsRevoked() = runTest {
        val store = FakeStore()
        val controller = controllerFor(store)
        load(controller)
        val displayed = store.sources.single()
        store.revokeSourceResult = false

        controller.dispatch(MichiLinkTrustIntent.RequestSourceRevocation(displayed))
        advanceUntilIdle()
        controller.dispatch(MichiLinkTrustIntent.ConfirmRevocation)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(MichiLinkTrustOutcome.PERSISTENCE_ERROR, state.notice?.outcome)
        assertEquals(store.sources, state.sources)
    }

    @Test
    fun revokingAHomeUsesTheExactSnapshotAndItsFullSourceCount() = runTest {
        val store = FakeStore(
            homes = listOf(homeOf(listOf(SOURCE_A, SOURCE_B))),
            sources = listOf(sourceOf(SOURCE_A), sourceOf(SOURCE_B)),
        )
        val controller = controllerFor(store)
        load(controller)
        val expected = store.homes.single()

        controller.dispatch(MichiLinkTrustIntent.RequestHomeRevocation(expected))
        advanceUntilIdle()
        val pending = controller.state.value.pending
        assertTrue(pending is MichiLinkTrustPending.Home)
        assertTrue(pending!!.impact.contains("2 orígenes registrados"))

        controller.dispatch(MichiLinkTrustIntent.ConfirmRevocation)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(MichiLinkTrustOutcome.REVOKED, state.notice?.outcome)
        assertEquals(expected, store.lastRevokedHome)
        assertTrue(state.homes.isEmpty())
        assertTrue(state.sources.isEmpty())
    }

    @Test
    fun aChangedHomeSnapshotIsNotRevoked() = runTest {
        val store = FakeStore()
        val controller = controllerFor(store)
        load(controller)
        val expected = store.homes.single()
        store.revokeHomeResult = false
        store.homes = listOf(homeOf(listOf(SOURCE_A)).copy(sourceBindingsSha256 = "d".repeat(64)))

        controller.dispatch(MichiLinkTrustIntent.RequestHomeRevocation(expected))
        advanceUntilIdle()
        controller.dispatch(MichiLinkTrustIntent.ConfirmRevocation)
        advanceUntilIdle()

        assertEquals(MichiLinkTrustOutcome.HOME_CHANGED, controller.state.value.notice?.outcome)
    }

    @Test
    fun aSecondConfirmationWhileOneIsInFlightIsIgnored() = runTest {
        val store = FakeStore()
        val controller = controllerFor(store)
        load(controller)
        val displayed = store.sources.single()

        controller.dispatch(MichiLinkTrustIntent.RequestSourceRevocation(displayed))
        advanceUntilIdle()
        controller.dispatch(MichiLinkTrustIntent.ConfirmRevocation)
        controller.dispatch(MichiLinkTrustIntent.ConfirmRevocation)
        advanceUntilIdle()

        assertEquals(1, store.sourceRevocations)
    }

    @Test
    fun aFailedReReadAfterCommitKeepsTheOutcomeButDrawsNoStaleRows() = runTest {
        val store = FakeStore()
        val controller = controllerFor(store)
        load(controller)
        val displayed = store.sources.single()
        store.failReads = true

        controller.dispatch(MichiLinkTrustIntent.RequestSourceRevocation(displayed))
        advanceUntilIdle()
        controller.dispatch(MichiLinkTrustIntent.ConfirmRevocation)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(MichiLinkTrustOutcome.REVOKED, state.notice?.outcome)
        assertEquals(MichiLinkTrustLoadFailure.KEYSTORE_UNAVAILABLE, state.loadFailure)
        assertTrue(state.homes.isEmpty())
        assertTrue(state.sources.isEmpty())
    }

    @Test
    fun cancellingTheSecondStepRevokesNothing() = runTest {
        val store = FakeStore()
        val controller = controllerFor(store)
        load(controller)

        controller.dispatch(MichiLinkTrustIntent.RequestSourceRevocation(store.sources.single()))
        advanceUntilIdle()
        controller.dispatch(MichiLinkTrustIntent.CancelRevocation)

        assertNull(controller.state.value.pending)
        assertEquals(0, store.sourceRevocations)
    }
}
