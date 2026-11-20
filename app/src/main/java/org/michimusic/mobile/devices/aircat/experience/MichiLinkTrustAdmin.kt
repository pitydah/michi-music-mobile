package org.michimusic.mobile.devices.aircat.experience

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeAuthority
import org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeSource
import org.michimusic.mobile.devices.aircat.AirCatHomeTrustAdmin

/**
 * A71-09: the only bridge between the trust screen and the encrypted inventory.
 *
 * It adds no authority of its own: reads and compare-and-revoke calls go to the
 * same [AirCatHomeTrustAdmin] the pairing flow already uses. What it does add is
 * *classification*. The store answers `false` for several different realities —
 * the snapshot changed, the record was already gone, the encrypted commit did
 * not land — and a screen that reports "no se pudo" for all of them hides the
 * one fact the operator needs. Here each case is separated, and every branch is
 * proven by re-reading the store rather than assumed from the boolean.
 */
internal class MichiLinkTrustAdmin(
    private val store: AirCatHomeTrustAdmin,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    internal data class Inventory(
        val homes: List<AirCatEnrolledHomeAuthority>,
        val sources: List<AirCatEnrolledHomeSource>,
    )

    internal data class Revocation(
        val outcome: MichiLinkTrustOutcome,
        /** `null` when the post-mutation re-read failed; callers must not guess. */
        val inventory: Inventory?,
    )

    /**
     * Reads the inventory. Any Keystore-backed failure is reported as a failed
     * [Result] so the UI can say "no se pudo leer" instead of drawing an empty
     * trust store, which would falsely read as "nothing is authorised".
     */
    suspend fun load(): Result<Inventory> = withContext(io) {
        readStore { readInventory() }
    }

    suspend fun revokeSource(expected: AirCatEnrolledHomeSource): Revocation = withContext(io) {
        val persisted = readStore { store.revokeSourceIfUnchanged(expected) }
        val current = readStore { store.anchorForSource(expected.sourceMichiId) }
        val outcome = when {
            persisted.isFailure -> MichiLinkTrustOutcome.PERSISTENCE_ERROR
            persisted.getOrThrow() -> MichiLinkTrustOutcome.REVOKED
            current.isFailure -> MichiLinkTrustOutcome.PERSISTENCE_ERROR
            else -> {
                val anchor = current.getOrThrow()
                when {
                    anchor == null -> MichiLinkTrustOutcome.ALREADY_REMOVED
                    anchor.homeId != expected.homeId ||
                        anchor.pinnedBaseUrl != expected.pinnedBaseUrl ||
                        anchor.tlsSpkiSha256 != expected.tlsSpkiSha256 ->
                        MichiLinkTrustOutcome.HOME_CHANGED
                    // The record still matches, yet the commit did not land.
                    else -> MichiLinkTrustOutcome.PERSISTENCE_ERROR
                }
            }
        }
        Revocation(outcome, readInventoryOrNull())
    }

    suspend fun revokeHome(expected: AirCatEnrolledHomeAuthority): Revocation = withContext(io) {
        val persisted = readStore { store.revokeHomeIfUnchanged(expected) }
        val root = readStore { store.anchorFor(expected.homeId) }
        val snapshot = readStore { store.enrolledHomes().firstOrNull { it.homeId == expected.homeId } }
        val outcome = when {
            persisted.isFailure -> MichiLinkTrustOutcome.PERSISTENCE_ERROR
            persisted.getOrThrow() -> MichiLinkTrustOutcome.REVOKED
            root.isFailure || snapshot.isFailure -> MichiLinkTrustOutcome.PERSISTENCE_ERROR
            root.getOrThrow() == null -> MichiLinkTrustOutcome.ALREADY_REMOVED
            // A root that exists but can no longer form a complete snapshot, or a
            // snapshot that differs from the one the user confirmed, is a change.
            snapshot.getOrThrow() == expected -> MichiLinkTrustOutcome.PERSISTENCE_ERROR
            else -> MichiLinkTrustOutcome.HOME_CHANGED
        }
        Revocation(outcome, readInventoryOrNull())
    }

    private fun readInventory(): Inventory =
        Inventory(homes = store.enrolledHomes(), sources = store.enrolledSources())

    private fun readInventoryOrNull(): Inventory? = readStore { readInventory() }.getOrNull()

    /**
     * Cancellation must propagate: a screen that leaves mid-read must not turn a
     * cancelled coroutine into a Keystore error or a "could not confirm" claim.
     */
    private inline fun <T> readStore(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
