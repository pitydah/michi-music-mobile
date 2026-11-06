package org.michimusic.mobile.artist.artwork

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in evidence against the real Commons MediaInfo API. Run with
 * `-e michiLiveProviders true`; never part of CI.
 */
class CommonsMediaInfoLiveTest {
    private fun enabled(): Boolean =
        InstrumentationRegistry.getArguments().getString("michiLiveProviders") == "true"

    @Test
    fun commonsMediaInfoConfirmsP180OnlyForTheRealArtistQid() = runBlocking {
        assumeTrue(enabled())
        val commons = CommonsImageInfo(ArtworkHttp())
        val file = "Festival des Vieilles Charrues 2022 - Midnight Oil - 082.jpg"
        assertTrue("Commons MediaInfo must contain P180=Q48284", commons.depicts(file, "Q48284"))
        assertFalse("A different artist QID must not match", commons.depicts(file, "Q300409"))
        println("LIVE_COMMONS_P180 file=$file qid=Q48284 verified=true")
    }
}
