package org.michimusic.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.michimusic.data.repository.ExistingGenreVerificationReport

/** Ephemeral, root-scoped UI cursor; never serialized to media tags or a worker. */
internal class SettingsGenreVerificationSession {
    var report by mutableStateOf<ExistingGenreVerificationReport?>(null)
        private set
    var offset by mutableIntStateOf(0)
        private set
    var snapshotToken by mutableStateOf<String?>(null)
        private set

    fun accept(value: ExistingGenreVerificationReport?) {
        report = value
        offset = value?.nextOffset ?: 0
        snapshotToken = value?.snapshotToken
    }

    fun clear() = accept(null)
}

@Composable
internal fun rememberGenreVerificationSession(owner: String?, treeUri: String?): SettingsGenreVerificationSession =
    remember(owner, treeUri) { SettingsGenreVerificationSession() }
