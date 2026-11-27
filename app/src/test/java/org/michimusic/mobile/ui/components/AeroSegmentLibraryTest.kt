package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.michimusic.mobile.ui.theme.AeroTheme

/**
 * UI8-BIB-01: the library variant enlarges only the selected capsule. The rail and
 * the 48dp touch target keep their geometry so no surrounding content moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi", application = android.app.Application::class)
class AeroSegmentLibraryTest {
    @get:Rule val compose = createComposeRule()

    private val labels = listOf("Canciones", "Álbumes", "Artistas", "Géneros")

    @Test fun selectedCapsuleGrowsInsideAnUnchangedRailTarget() {
        var selected by mutableIntStateOf(0)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            AeroTheme {
                AeroSegmentRail(compact = true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        labels.forEachIndexed { index, label ->
                            AeroSegmentedChoice(
                                label = label,
                                selected = selected == index,
                                onClick = { selected = index },
                                modifier = Modifier.weight(1f),
                                compact = true,
                                librarySection = true,
                                testTag = "library_tab_${label.lowercase()}",
                            )
                        }
                    }
                }
            }
        }
        labels.forEachIndexed { index, label ->
            compose.onNodeWithTag("library_tab_${label.lowercase()}", useUnmergedTree = true).performClick()
            val host = compose.onNodeWithTag("library_tab_${label.lowercase()}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val capsule = compose.onNodeWithTag("library_tab_${label.lowercase()}_selected", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val hostDp = host.height / density
            val capsuleDp = capsule.height / density
            check(hostDp in 47.5f..48.5f) {
                "The library touch target must stay 48dp for $label; was ${hostDp}dp"
            }
            check(capsuleDp in 43.5f..44.5f) {
                "The selected capsule must grow to 44dp for $label; was ${capsuleDp}dp"
            }
            check(capsuleDp < hostDp) {
                "The capsule must retain an inset inside the rail for $label"
            }
        }
    }
    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun stackedLibraryKeepsTheSameCapsuleGeometryAt320dp() {
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            AeroTheme {
                AeroSegmentStack(compact = true) {
                    labels.chunked(2).forEach { pair ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            pair.forEach { label ->
                                AeroSegmentedChoice(
                                    label = label,
                                    selected = label == "Canciones",
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                    compact = true,
                                    librarySection = true,
                                    testTag = "narrow_${label.lowercase()}",
                                )
                            }
                        }
                    }
                }
            }
        }
        val host = compose.onNodeWithTag("narrow_canciones", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag("narrow_canciones_selected", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        check(host.height / density in 47.5f..48.5f)
        check(capsule.height / density in 43.5f..44.5f)
    }
    private fun checkHorizontalRailCapsule() {
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            AeroTheme {
                AeroSegmentRail(compact = true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        labels.forEachIndexed { index, label ->
                            AeroSegmentedChoice(
                                label = label,
                                selected = index == 0,
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                compact = true,
                                librarySection = true,
                                testTag = "width_tab_${label.lowercase()}",
                            )
                        }
                    }
                }
            }
        }
        val host = compose.onNodeWithTag("width_tab_canciones", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag("width_tab_canciones_selected", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        check(host.height / density in 47.5f..48.5f)
        check(capsule.height / density in 43.5f..44.5f)
    }

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun horizontalRailAt360KeepsTheCapsuleGeometry() = checkHorizontalRailCapsule()

    @Test @Config(qualifiers = "w393dp-h851dp-xhdpi")
    fun horizontalRailAt393KeepsTheCapsuleGeometry() = checkHorizontalRailCapsule()

    @Test @Config(qualifiers = "w430dp-h932dp-xhdpi")
    fun horizontalRailAt430KeepsTheCapsuleGeometry() = checkHorizontalRailCapsule()

    @Test @Config(qualifiers = "w600dp-h960dp-xhdpi")
    fun horizontalRailAt600KeepsTheCapsuleGeometry() = checkHorizontalRailCapsule()
}
