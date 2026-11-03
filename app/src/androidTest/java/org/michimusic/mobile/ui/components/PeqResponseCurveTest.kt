package org.michimusic.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.michimusic.player.PeqBand

/** One semantic contract; visual baselines and device layouts are OpenCode gates. */
class PeqResponseCurveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun calculatedCurveNeverClaimsThatRealHardwareWasMeasured() {
        compose.setContent {
            PeqResponseCurve(bands = listOf(PeqBand(1_000f, 4f, 1f)), referenceSampleRate = 48_000)
        }
        compose.onNodeWithTag("peq_calculated_curve").assertExists()
        compose.onNode(hasContentDescription("Curva teórica calculada de seis filtros paramétricos, frecuencia de referencia 48000 Hz. No mide el sonido ni confirma el efecto activo"))
            .assertExists()
    }
}
