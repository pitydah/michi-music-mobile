package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.mobile.playback.settingsReplayGainMode
import org.michimusic.player.ReplayGainMode

class SettingsReplayGainPolicyTest {
    @Test fun validModeNameParses() = assertEquals(ReplayGainMode.ALBUM,
        settingsReplayGainMode("ALBUM"))
    @Test fun unknownModeFallsBackToOff() = assertEquals(ReplayGainMode.OFF,
        settingsReplayGainMode("UNRECOGNIZED"))
    @Test fun nullModeFallsBackToOff() = assertEquals(ReplayGainMode.OFF,
        settingsReplayGainMode(null))
    @Test fun blankModeFallsBackToOff() = assertEquals(ReplayGainMode.OFF,
        settingsReplayGainMode(""))

    @Test fun legacyIntegerOrdinalParsesWithoutMigration() = assertEquals(ReplayGainMode.ALBUM,
        settingsReplayGainMode(ReplayGainMode.ALBUM.ordinal))
    @Test fun invalidIntegerOrdinalFailsClosedWithoutException() = assertEquals(ReplayGainMode.OFF,
        settingsReplayGainMode(Int.MAX_VALUE))
    @Test fun invalidTypeFailsClosedWithoutException() = assertEquals(ReplayGainMode.OFF,
        settingsReplayGainMode(42L))
    @Test fun legacyNegativeOrdinalFailsClosed() = assertEquals(ReplayGainMode.OFF,
        settingsReplayGainMode(-1))
}
