package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LibraryHeaderLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun responsivePolicyStacksAtOneHundredFiftyAndTwoHundredPercent() {
        assertFalse(libraryHeaderStacks(1f))
        assertTrue(libraryHeaderStacks(1.5f))
        assertTrue(libraryHeaderStacks(2f))
    }

    @Test
    fun largeFontStacksActionsBelowFullWidthTitle() {
        compose.setContent {
            MichiTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalDensity provides Density(density = 1f, fontScale = 2f),
                ) {
                    LibraryHeaderLayout(
                        modifier = Modifier.size(width = 384.dp, height = 220.dp),
                        titleContent = { titleModifier ->
                            Box(
                                modifier = titleModifier
                                    .size(width = 300.dp, height = 80.dp)
                                    .testTag("title"),
                            )
                        },
                        actionsContent = {
                            Box(
                                modifier = Modifier
                                    .size(width = 260.dp, height = 56.dp)
                                    .testTag("actions"),
                            )
                        },
                    )
                }
            }
        }

        val title = compose.onNodeWithTag("title").getUnclippedBoundsInRoot()
        val actions = compose.onNodeWithTag("actions").getUnclippedBoundsInRoot()

        assertTrue(actions.top >= title.bottom)
    }
}
