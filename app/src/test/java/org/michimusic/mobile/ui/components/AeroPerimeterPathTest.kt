package org.michimusic.mobile.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AeroPerimeterPathTest {
    private val density = Density(1f)

    @Test fun roundedPerimeterMatchesTheRealOuterBounds() {
        val outline = RoundedCornerShape(10.dp)
            .createOutline(Size(100f, 60f), LayoutDirection.Ltr, density)
        val path = aeroPerimeterPath(outline)
        assertFalse(path.isEmpty)
        val bounds = path.getBounds()
        assertEquals(0f, bounds.left, 0.01f)
        assertEquals(0f, bounds.top, 0.01f)
        assertEquals(100f, bounds.right, 0.01f)
        assertEquals(60f, bounds.bottom, 0.01f)
    }

    @Test fun bottomEdgeStopsBeforeBothRoundedCorners() {
        val outline = RoundedCornerShape(10.dp)
            .createOutline(Size(100f, 60f), LayoutDirection.Ltr, density)
        val bottom = aeroBottomEdgePath(outline)
        assertNotNull(bottom)
        val bounds = bottom!!.getBounds()
        assertEquals(10f, bounds.left, 0.01f)
        assertEquals(90f, bounds.right, 0.01f)
        assertEquals(60f, bounds.top, 0.01f)
        assertEquals(60f, bounds.bottom, 0.01f)
    }

    @Test fun rectanglePerimeterKeepsItsFullBottomEdge() {
        val outline = Outline.Rectangle(Rect(0f, 0f, 120f, 40f))
        val path = aeroPerimeterPath(outline)
        assertFalse(path.isEmpty)
        val bottom = aeroBottomEdgePath(outline)!!
        val bounds = bottom.getBounds()
        assertEquals(0f, bounds.left, 0.01f)
        assertEquals(120f, bounds.right, 0.01f)
        assertEquals(40f, bounds.bottom, 0.01f)
    }

    @Test fun oversizedCornersCannotProduceAnInvertedBottomLine() {
        val outline = Outline.Rounded(
            RoundRect(
                Rect(0f, 0f, 100f, 60f),
                CornerRadius(80f, 80f),
                CornerRadius(80f, 80f),
                CornerRadius(80f, 80f),
                CornerRadius(80f, 80f),
            ),
        )
        assertNull(aeroBottomEdgePath(outline))
    }

    @Test fun genericOutlineKeepsThePathButHasNoStraightBottomFallback() {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f)
            lineTo(100f, 0f)
            lineTo(100f, 60f)
            close()
        }
        val outline = Outline.Generic(path)
        assertFalse(aeroPerimeterPath(outline).isEmpty)
        assertNull(aeroBottomEdgePath(outline))
    }
}
