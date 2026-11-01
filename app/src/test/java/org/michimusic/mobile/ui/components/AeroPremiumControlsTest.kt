package org.michimusic.mobile.ui.components

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AeroPremiumControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actionsPreserveCallbackAndTagAcrossMaterialKinds() {
        var taps = 0
        compose.setContent {
            AeroActionButton("Escuchar", { taps++ }, icon = Icons.Filled.PlayArrow,
                kind = AeroActionKind.Primary, testTag = "m54_action")
        }
        compose.onNodeWithTag("m54_action").performClick()
        assertEquals(1, taps)
    }

    @Test fun disabledActionsCannotTriggerCallbacks() {
        var taps = 0
        compose.setContent {
            AeroActionButton("Buscando", { taps++ }, enabled = false,
                kind = AeroActionKind.Secondary, testTag = "m54_disabled")
        }
        compose.onNodeWithTag("m54_disabled").assertIsNotEnabled()
        assertEquals(0, taps)
    }

    @Test fun touchSurfaceRespondsToFontScale() {
        assertEquals(52, aeroActionMinimumHeightDp(1f))
        assertEquals(56, aeroActionMinimumHeightDp(1.5f))
        assertEquals(64, aeroActionMinimumHeightDp(2f))
    }
}
