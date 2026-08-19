package org.michimusic.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.data.local.ReplayGainReader.ReplayGainData

// Pure-function coverage for isReplayGainPersistable(), the guard that stops
// LocalMediaRepository.queryTracks() from ever writing a NaN into replaygain_cache's
// NOT NULL trackGain/albumGain columns. See ReplayGainDaoTest for proof, against a real
// Room/SQLite database, that persisting a NaN entity actually crashes without this guard.
class ReplayGainPersistableTest {

    @Test
    fun bothGainsPresent_isPersistable() {
        assertTrue(isReplayGainPersistable(ReplayGainData(trackGain = -6.5f, albumGain = -7.1f)))
    }

    @Test
    fun noTagsAtAll_isNotPersistable() {
        assertFalse(isReplayGainPersistable(ReplayGainData()))
    }

    @Test
    fun onlyTrackGainMissing_isNotPersistable() {
        assertFalse(isReplayGainPersistable(ReplayGainData(trackGain = Float.NaN, albumGain = -7.1f)))
    }

    @Test
    fun onlyAlbumGainMissing_isNotPersistable() {
        assertFalse(isReplayGainPersistable(ReplayGainData(trackGain = -6.5f, albumGain = Float.NaN)))
    }

    @Test
    fun infiniteGain_isNotPersistable() {
        // Defensive: isFinite() also rejects +/-Infinity, which SQLite can store but which is
        // never a meaningful ReplayGain value.
        assertFalse(isReplayGainPersistable(ReplayGainData(trackGain = Float.POSITIVE_INFINITY, albumGain = -7.1f)))
    }
}
