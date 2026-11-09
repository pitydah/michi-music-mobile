package org.michimusic.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.player.PeqBand
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.aeroResolveMaterial
import org.michimusic.mobile.ui.theme.LocalVisualPreferences

/** Calculated biquad curve of SAVED bands, never a claim that DSP is currently applied. */
@Composable
fun PeqResponseCurve(
    bands: List<PeqBand>,
    modifier: Modifier = Modifier,
    referenceSampleRate: Int = 48_000,
) {
    val points = remember(bands, referenceSampleRate) {
        calculatedPeqResponse(bands, referenceSampleRate)
    }
    val visual = LocalVisualPreferences.current
    val material = aeroResolveMaterial(
        role = AeroSurfaceRole.Content,
        requestedFinish = AeroFinish.Inset,
        profile = visual.appearanceProfile,
        highContrast = visual.highContrast,
        microtexturesEnabled = visual.microtexturesEnabled,
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Text("Respuesta calculada · filtros PEQ guardados", color = OnSurfaceVariant, fontSize = 12.sp)
        // audioRateLabel keeps the Spanish decimal comma ("44,1 kHz") and drops a
        // trailing ",0" ("48 kHz"); a raw float would print "48.0 kHz" mid-sentence.
        Text("Frecuencia de referencia: ${audioRateLabel(referenceSampleRate)} · NO es una medición ni confirma PEQ aplicado.",
            color = OnSurfaceVariant, fontSize = 11.sp)
        Canvas(
            modifier = Modifier.fillMaxWidth().height(116.dp)
                .background(Color(0xFF121923), RoundedCornerShape(12.dp))
                .aeroFinishDrawing(
                    material.finish,
                    material.spec,
                    null,
                    RoundedCornerShape(12.dp),
                )
                .border(0.75.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .semantics {
                    contentDescription = "Curva teórica calculada de seis filtros paramétricos, " +
                        "frecuencia de referencia ${referenceSampleRate} Hz. No mide el sonido ni confirma el efecto activo"
                }
                .testTag("peq_calculated_curve")
                .padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
            val middle = size.height / 2f
            val magnitudeRangeDb = 18f
            val line = Path()
            drawLine(Color.White.copy(alpha = .26f), Offset(0f, middle),
                Offset(size.width, middle), strokeWidth = 1.dp.toPx())
            for (db in listOf(-12f, -6f, 6f, 12f)) {
                val y = middle - db / magnitudeRangeDb * middle
                drawLine(Color.White.copy(alpha = .10f), Offset(0f, y),
                    Offset(size.width, y), strokeWidth = .7.dp.toPx())
            }
            points.forEachIndexed { i, pt ->
                val x = i.toFloat() / (points.size - 1).coerceAtLeast(1) * size.width
                val y = middle - pt.gainDb.coerceIn(-magnitudeRangeDb, magnitudeRangeDb) /
                    magnitudeRangeDb * middle
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            if (points.isNotEmpty()) drawPath(line, TertiaryCyan,
                style = Stroke(width = 2.dp.toPx()))
        }
        val endHz = points.lastOrNull()?.frequencyHz?.toInt() ?: 0
        Text("20 Hz    ·    1 kHz    ·    hasta $endHz Hz según fs de referencia", color = OnSurfaceVariant,
            fontSize = 10.sp)
    }
}
