package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.VisualPreferences

/**
 * A68-07: revoking a paired source is the only way out of a bad Home pairing, so the
 * entry point may not depend on the header happening to be compact. The overflow menu
 * exists at every width; the action has to be in it at every width.
 *
 * The header decides its layout from [LocalConfiguration], so both layouts are driven
 * directly instead of relying on whichever device happens to run the suite.
 */
@RunWith(AndroidJUnit4::class)
class DevicesTopBarHomeTrustTest {

    @get:Rule
    val rule = createComposeRule()

    private fun render(widthDp: Int, fontScale: Float, onHomeTrust: () -> Unit) {
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = widthDp
                this.fontScale = fontScale
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(2.625f, fontScale),
            ) {
                MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                    Box(Modifier.width(widthDp.dp)) {
                        DevicesTopBar(
                            isSearching = false,
                            onRefreshClick = {},
                            onStopClick = {},
                            onHomeTrustClick = onHomeTrust,
                            onQrClick = {},
                            onSettingsClick = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun theWideHeaderStillReachesHomeTrust() {
        var opened = 0
        render(widthDp = 480, fontScale = 1f) { opened += 1 }

        rule.onNodeWithTag("devices_more_button").performClick()
        rule.onNodeWithTag("menu_home_trust").assertIsDisplayed().performClick()

        assertEquals("the wide header must let the user manage Home trust", 1, opened)
    }

    @Test
    fun theCompactHeaderStillReachesHomeTrust() {
        var opened = 0
        render(widthDp = 360, fontScale = 1.3f) { opened += 1 }

        rule.onNodeWithTag("devices_more_button").performClick()
        rule.onNodeWithTag("menu_home_trust").assertIsDisplayed().performClick()

        assertEquals("the compact header must let the user manage Home trust", 1, opened)
    }
}
