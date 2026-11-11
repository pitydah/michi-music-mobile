package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.AeroSeekerStyle
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.VisualPreferences

/** Draw-only examples must render all five choices without audio or images.
 * Drafted for Android instrumented execution; not run in Google Drive phase.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSeekerPreviewComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun allFiveDemonstrationsArePresentInReducedMotion() {
        rule.setContent {
            MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                Column { AeroSeekerStyle.entries.forEach { SettingsSeekerPreview(it) } }
            }
        }
        AeroSeekerStyle.entries.forEach { style ->
            rule.onNodeWithTag("settings_seeker_demo_${style.name.lowercase()}").assertExists()
        }
    }
}
