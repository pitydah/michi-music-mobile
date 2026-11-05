package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.CommonsPortrait
import java.io.IOException

/** Source-grounded hero: missing enrichment renders a monogram, never fake photography. */
@RunWith(AndroidJUnit4::class)
class ArtistDetailHeroTest {
    @get:Rule val compose = createComposeRule()

    private val allowed = CommonsPortrait(
        imageUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/artist.jpg/320px-artist.jpg",
        commonsPageUrl = "https://commons.wikimedia.org/wiki/File:artist.jpg",
        license = "CC BY-SA 4.0",
        licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
        attribution = "Fotógrafo de prueba",
    )

    @Test fun unlicensedPortraitNeverRendersEvenWithAnHttpsImage() {
        val blocked = CommonsPortrait(
            imageUrl = "https://upload.wikimedia.org/wikipedia/commons/x/xx/photo.jpg",
            commonsPageUrl = "https://commons.wikimedia.org/wiki/File:photo.jpg",
            license = "All rights reserved",
            licenseUrl = "https://commons.wikimedia.org/wiki/File:photo.jpg",
            attribution = "Una persona",
        )
        compose.setContent { ArtistDetailHero("Midnight Oil", blocked, 1, 1) }
        compose.onNodeWithTag("artist_detail_hero_monogram").assertExists()
        compose.onNodeWithTag("artist_detail_hero_photo").assertDoesNotExist()
    }

    @Test fun unverifiedArtistStillHasAccessibleHeaderAndRealCounts() {
        compose.setContent {
            ArtistDetailHero(artistCredit = "Midnight Oil", portrait = null, albumCount = 1, trackCount = 1)
        }
        compose.onNodeWithTag("artist_detail_hero").assertExists()
        compose.onNodeWithText("Midnight Oil").assertExists()
        compose.onNodeWithText("1 álbum  ·  1 canción").assertExists()
        compose.onNodeWithTag("artist_detail_hero_photo").assertDoesNotExist()
        compose.onNodeWithTag("artist_detail_hero_monogram").assertExists()
    }

    @OptIn(DelicateCoilApi::class)
    @Test fun failedPortraitDownloadRestoresTheMonogram() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val failing = Interceptor { chain ->
            ErrorResult(image = null, request = chain.request, throwable = IOException("decode failed"))
        }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components { add(failing) }.build(),
        )
        try {
            compose.setContent { ArtistDetailHero("Midnight Oil", allowed, 1, 1) }
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("artist_detail_hero_monogram").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("artist_detail_hero_monogram").assertExists()
            compose.onNodeWithTag("artist_detail_hero_photo").assertExists()
        } finally {
            SingletonImageLoader.reset()
        }
    }
}
