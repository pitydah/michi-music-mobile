package org.michimusic.mobile.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.AeroTheme
import org.michimusic.mobile.ui.theme.VisualPreferences
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Component tests designed for JVM/Robolectric; real Android execution is not claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AeroMaterialIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun smokedFloatingSurfaceKeepsOneClickAction() {
        var clicks = 0
        compose.setContent {
            AeroTheme {
                AeroSurface(
                    role = AeroSurfaceRole.Floating,
                    finish = AeroFinish.Smoked,
                    modifier = Modifier.testTag("smoked_surface"),
                    onClick = { clicks++ },
                ) { Text("Detalles") }
            }
        }
        compose.onNodeWithTag("smoked_surface").assertExists().assertHasClickAction().performClick()
        assertEquals(1, clicks)
    }

    @Test fun highContrastInsetSurfacePreservesChildren() {
        compose.setContent {
            AeroTheme(visualPreferences = VisualPreferences(highContrast = true)) {
                AeroSurface(AeroSurfaceRole.Content, finish = AeroFinish.Inset) {
                    Text("Valor técnico", modifier = Modifier.testTag("hc_content"))
                }
            }
        }
        compose.onNodeWithTag("hc_content").assertExists()
    }

    @Test fun framedArtworkRendersActualArtworkSlot() {
        compose.setContent {
            AeroTheme {
                AeroArtworkMat(modifier = Modifier.size(120.dp), cornerRadius = 18.dp, inset = 4.dp) {
                    Box(Modifier.testTag("real_artwork_slot")) { Text("Portada real") }
                }
            }
        }
        compose.onNodeWithTag("real_artwork_slot").assertExists()
    }
}
