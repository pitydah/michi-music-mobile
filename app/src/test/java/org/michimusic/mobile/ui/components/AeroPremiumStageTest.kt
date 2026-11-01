package org.michimusic.mobile.ui.components

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AeroPremiumStageTest {
    @get:Rule val compose = createComposeRule()
    @Test fun heroActionsStackForNarrowAndLargeFontScreens() {
        org.junit.Assert.assertTrue(aeroHeroActionsVertical(360, 1f))
        org.junit.Assert.assertTrue(aeroHeroActionsVertical(420, 1.6f))
        org.junit.Assert.assertFalse(aeroHeroActionsVertical(440, 1f))
    }

    @Test fun editorialHeroReflectsRealLibraryAndExecutesOriginalActions() {
        var playCount = 0
        var shuffleCount = 0
        compose.setContent {
            AeroEditorialHero(
                trackCount = 27,
                onPlayAll = { playCount++ },
                onShuffle = { shuffleCount++ },
            )
        }
        compose.onNodeWithText("27 canciones en tu biblioteca").assertIsDisplayed()
        compose.onNodeWithTag("quick_play_button").performClick()
        compose.onNodeWithTag("shuffle_button").performClick()
        assertEquals(1, playCount)
        assertEquals(1, shuffleCount)
    }

    @Test fun emptyStateExplainsItsPurposeWithoutFakeRecommendations() {
        compose.setContent {
            AeroEmptyEditorial(
                eyebrow = "EXPLORACIÓN PERSONAL",
                title = "Toda tu música, a una búsqueda.",
                description = "Busca por título.",
                icon = Icons.Filled.Search,
            )
        }
        compose.onNodeWithText("Toda tu música, a una búsqueda.").assertIsDisplayed()
        compose.onNodeWithText("Busca por título.").assertIsDisplayed()
    }
}
