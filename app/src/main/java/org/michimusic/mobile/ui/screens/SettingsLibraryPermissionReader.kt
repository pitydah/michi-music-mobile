package org.michimusic.mobile.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.mobile.library.MusicRootAuthority

/** A failed SAF permission read is UNKNOWN, never a grant or silent success. */
internal data class SettingsLibraryPermissionTruth(
    val permissionRevoked: Boolean = false,
    val canWrite: Boolean = false,
    val readFailed: Boolean = false,
    val checked: Boolean = false,
)

/** Truthful state from two possibly independent SAF provider checks. */
internal fun settingsLibraryPermissionTruth(
    revoked: Boolean?, writable: Boolean?,
): SettingsLibraryPermissionTruth = when {
    revoked == null || writable == null -> SettingsLibraryPermissionTruth(readFailed = true, checked = true)
    revoked -> SettingsLibraryPermissionTruth(permissionRevoked = true, checked = true)
    else -> SettingsLibraryPermissionTruth(canWrite = writable, readFailed = false, checked = true)
}

/** No polling, no write escalation. A single bounded read on library entry/resume. */
internal class SettingsLibraryPermissionReader(
    private val query: () -> Pair<Boolean, Boolean>,
) {
    constructor(authority: MusicRootAuthority) : this({
        val revoked = authority.permissionRevoked()
        // Do not request WRITE from a known-revoked tree.
        val writable = if (revoked) false else authority.canWriteCurrentRoot()
        revoked to writable
    })

    suspend fun read(): SettingsLibraryPermissionTruth = withContext(Dispatchers.IO) {
        try {
            val (revoked, writable) = query()
            settingsLibraryPermissionTruth(revoked, writable)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SettingsLibraryPermissionTruth(readFailed = true, checked = true)
        }
    }
}
