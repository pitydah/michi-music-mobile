package org.michimusic.mobile.ui.screens

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Library counters are quantity strings now. The defect these guard against is
 * silent: "${n} pistas" renders "1 pistas", which no layout test would catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LibraryPluralStringsTest {

    private val resources = RuntimeEnvironment.getApplication().resources

    @Test
    fun `a single track reads as one pista`() {
        assertEquals("1 pista", resources.getQuantityString(R.plurals.track_count, 1, 1))
        assertEquals("2 pistas", resources.getQuantityString(R.plurals.track_count, 2, 2))
        assertEquals("0 pistas", resources.getQuantityString(R.plurals.track_count, 0, 0))
    }

    @Test
    fun `a single album reads as one album`() {
        assertEquals("1 álbum", resources.getQuantityString(R.plurals.album_count, 1, 1))
        assertEquals("3 álbumes", resources.getQuantityString(R.plurals.album_count, 3, 3))
    }

    @Test
    fun `a single song reads as one cancion`() {
        assertEquals("1 canción", resources.getQuantityString(R.plurals.song_count, 1, 1))
        assertEquals("5 canciones", resources.getQuantityString(R.plurals.song_count, 5, 5))
    }

    @Test
    fun `the playlist prompt agrees with its own count`() {
        assertEquals(
            "Selecciona una playlist para «Michi» (1 pista)",
            resources.getQuantityString(R.plurals.select_playlist_for_album, 1, "Michi", 1),
        )
        assertEquals(
            "Selecciona una playlist para «Michi» (4 pistas)",
            resources.getQuantityString(R.plurals.select_playlist_for_album, 4, "Michi", 4),
        )
    }

    @Test
    fun `transfer progress agrees with its total`() {
        assertEquals("1 de 1 pista", resources.getQuantityString(R.plurals.tracks_progress, 1, 1, 1))
        assertEquals("2 de 5 pistas", resources.getQuantityString(R.plurals.tracks_progress, 5, 2, 5))
        assertEquals(
            "1 pista procesada · total desconocido",
            resources.getQuantityString(R.plurals.tracks_processed_unknown_total, 1, 1),
        )
        assertEquals(
            "3 pistas procesadas · total desconocido",
            resources.getQuantityString(R.plurals.tracks_processed_unknown_total, 3, 3),
        )
    }
}
