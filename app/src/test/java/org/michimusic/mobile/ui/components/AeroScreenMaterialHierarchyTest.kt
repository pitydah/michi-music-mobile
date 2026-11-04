package org.michimusic.mobile.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

/** AMR-06/07: test the reusable material contracts without app DI or real devices.
 * These tests must run via Gradle before the accumulated patch is delivered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AeroScreenMaterialHierarchyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun raisedAnodizedDevicePlateRetainsOneClick() {
        var clicks = 0
        compose.setContent {
            AeroTheme {
                AeroSurface(
                    role = AeroSurfaceRole.Raised,
                    finish = AeroFinish.Anodized,
                    modifier = Modifier.testTag("equipment_panel"),
                    onClick = { clicks++ },
                ) { Text("Dispositivo conectado") }
            }
        }
        compose.onNodeWithTag("equipment_panel").assertExists()
            .assertHasClickAction().performClick()
        assertEquals(1, clicks)
    }

    @Test fun insetSearchMaterialDoesNotStealChildInteraction() {
        var taps = 0
        compose.setContent {
            AeroTheme {
                AeroSurface(
                    role = AeroSurfaceRole.Content,
                    finish = AeroFinish.Inset,
                    modifier = Modifier.testTag("search_shell"),
                ) {
                    AeroCard(
                        modifier = Modifier.testTag("result_row").size(120.dp),
                        onClick = { taps++ },
                    ) { Text("Álbum") }
                }
            }
        }
        compose.onNodeWithTag("search_shell").assertExists()
        compose.onNodeWithTag("result_row").assertHasClickAction().performClick()
        assertEquals(1, taps)
    }

    @Test fun highContrastKeepsTextVisibleInBothMaterials() {
        compose.setContent {
            AeroTheme(visualPreferences = VisualPreferences(highContrast = true)) {
                Column {
                    AeroSurface(role = AeroSurfaceRole.Content, finish = AeroFinish.Inset) {
                        Box(Modifier.testTag("hc_inset")) { Text("Buscar") }
                    }
                    AeroSurface(role = AeroSurfaceRole.Raised, finish = AeroFinish.Anodized) {
                        Box(Modifier.testTag("hc_anodized")) { Text("Equipo") }
                    }
                }
            }
        }
        compose.onNodeWithTag("hc_inset").assertExists()
        compose.onNodeWithTag("hc_anodized").assertExists()
    }
}
