package org.michimusic.mobile.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.AeroTheme
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.aeroMaterialStyle
import org.michimusic.mobile.ui.theme.aeroContrastRatio
import org.michimusic.mobile.ui.theme.VisualPreferences
import org.michimusic.mobile.ui.components.premium.MichiSurface
import org.michimusic.mobile.ui.components.premium.MichiSurfaceLevel
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.graphics.Color
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Aero canonicalization contract. Execute in OpenCode; do not infer PASS from source alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AeroCanonicalSurfacesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun canonicalCardHasOneClickTarget() {
        var clicked = 0
        compose.setContent {
            AeroTheme {
                AeroCard(modifier = Modifier.testTag("aero_card"), onClick = { clicked++ }) {
                    Text("Colección")
                }
            }
        }
        compose.onNodeWithTag("aero_card").assertExists().assertHasClickAction().performClick()
        assertEquals(1, clicked)
    }

    @Test fun canonicalSurfaceProvidesAAInkForUnstyledText() {
        val ink = AtomicReference<Color>()
        compose.setContent {
            AeroTheme {
                AeroCard {
                    ink.set(LocalContentColor.current)
                    Text("Álbum sin color explícito")
                }
            }
        }
        compose.waitForIdle()
        val observed = ink.get() ?: error("No composition recorded")
        val raised = aeroMaterialStyle(AeroSurfaceRole.Raised, false).fill
        assertTrue(aeroContrastRatio(observed, raised) >= 4.5)
    }

    @Test fun legacyMichiSurfaceUsesSameCanonicalRoleWithHighContrast() {
        compose.setContent {
            AeroTheme(visualPreferences = VisualPreferences(highContrast = true)) {
                MichiSurface(level = MichiSurfaceLevel.Raised,
                    modifier = Modifier.testTag("legacy_michi_surface")) { Text("Álbum") }
            }
        }
        compose.onNodeWithTag("legacy_michi_surface").assertExists()
    }
}
