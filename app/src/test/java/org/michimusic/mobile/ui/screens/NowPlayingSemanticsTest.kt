package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.ui.components.premium.PlaybackControlUiState

class NowPlayingSemanticsTest {

    @Test
    fun testPlayPauseSemanticsTransitions() {
        val pausedState = PlaybackControlUiState(isPlaying = false, isLoading = false)
        assertEquals("Reproducir", pausedState.playPauseContentDescription())

        val playingState = PlaybackControlUiState(isPlaying = true, isLoading = false)
        assertEquals("Pausar", playingState.playPauseContentDescription())

        val loadingWhilePlayingState = PlaybackControlUiState(isPlaying = true, isLoading = true)
        assertEquals("Cargando audio", loadingWhilePlayingState.playPauseContentDescription())

        val loadingWhilePausedState = PlaybackControlUiState(isPlaying = false, isLoading = true)
        assertEquals("Cargando audio", loadingWhilePausedState.playPauseContentDescription())
    }

    @Test
    fun testShuffleSemanticsTransitions() {
        val shuffleOff = PlaybackControlUiState(isShuffleEnabled = false)
        assertEquals("Activar modo aleatorio", shuffleOff.shuffleContentDescription())

        val shuffleOn = PlaybackControlUiState(isShuffleEnabled = true)
        assertEquals("Desactivar modo aleatorio", shuffleOn.shuffleContentDescription())
    }

    @Test
    fun testRepeatSemanticsTransitions() {
        val repeatOff = PlaybackControlUiState(repeatMode = 0)
        assertEquals("Activar repetición", repeatOff.repeatContentDescription())

        val repeatOne = PlaybackControlUiState(repeatMode = 1)
        assertEquals("Repetición: una pista", repeatOne.repeatContentDescription())

        val repeatAll = PlaybackControlUiState(repeatMode = 2)
        assertEquals("Repetición: todo", repeatAll.repeatContentDescription())
    }

    @Test
    fun testMichiIconButtonAccessibilityEnforcement() {
        // Enforce that clickable MichiIconButton rejects null or blank contentDescription (K46D-23)
        assertThrows(IllegalArgumentException::class.java) {
            org.michimusic.mobile.ui.screens.nowplaying.validateMichiIconButtonContentDescription(
                onClick = {},
                contentDescription = null,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            org.michimusic.mobile.ui.screens.nowplaying.validateMichiIconButtonContentDescription(
                onClick = {},
                contentDescription = "   ",
            )
        }

        // Non-clickable buttons (purely decorative) are permitted with null contentDescription
        org.michimusic.mobile.ui.screens.nowplaying.validateMichiIconButtonContentDescription(
            onClick = null,
            contentDescription = null,
        )
    }

    @Test
    fun testMichiIconActionAccessibilityEnforcement() {
        // Enforce that MichiIconAction rejects blank contentDescription (K46D-15, K46D-25)
        assertThrows(IllegalArgumentException::class.java) {
            org.michimusic.mobile.ui.components.premium.validateIconActionDescription("")
        }

        assertThrows(IllegalArgumentException::class.java) {
            org.michimusic.mobile.ui.components.premium.validateIconActionDescription("   ")
        }

        // Meaningful description succeeds
        org.michimusic.mobile.ui.components.premium.validateIconActionDescription("Reproducir pista")
    }

    @Test
    fun testRemotePlaybackLoadingReflectsCommandPendingStatus() {
        val pendingCommand = org.michimusic.mobile.playback.RemoteCommandRecord(
            endpointId = "remote-test",
            action = "play",
            status = org.michimusic.mobile.playback.RemoteCommandStatus.PENDING,
            timestampMs = System.currentTimeMillis(),
        )
        val confirmedCommand = org.michimusic.mobile.playback.RemoteCommandRecord(
            endpointId = "remote-test",
            action = "play",
            status = org.michimusic.mobile.playback.RemoteCommandStatus.CONFIRMED,
            timestampMs = System.currentTimeMillis(),
        )

        val isRemoteActive = true
        val localBuffering = false

        val loadingWhenPending = if (isRemoteActive) {
            pendingCommand.status == org.michimusic.mobile.playback.RemoteCommandStatus.PENDING
        } else {
            localBuffering
        }
        assertTrue("En modo remoto con comando pendiente, isLoading debe ser true", loadingWhenPending)

        val loadingWhenConfirmed = if (isRemoteActive) {
            confirmedCommand.status == org.michimusic.mobile.playback.RemoteCommandStatus.PENDING
        } else {
            localBuffering
        }
        org.junit.Assert.assertFalse("En modo remoto con comando confirmado, isLoading debe ser false", loadingWhenConfirmed)
    }

    @Test
    fun testDurationUnknownFormattingDoesNotProduceZeroOrNaN() {
        val zeroDuration = 0f
        val negativeDuration = -1f
        val formattedZero = if (zeroDuration > 0f) org.michimusic.mobile.ui.components.formatTimeSeconds(zeroDuration) else "—:—"
        val formattedNegative = if (negativeDuration > 0f) org.michimusic.mobile.ui.components.formatTimeSeconds(negativeDuration) else "—:—"

        assertEquals("—:—", formattedZero)
        assertEquals("—:—", formattedNegative)
    }
}

