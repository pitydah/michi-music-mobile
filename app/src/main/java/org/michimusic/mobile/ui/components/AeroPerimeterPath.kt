package org.michimusic.mobile.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path

/** Convert any Compose Outline into one reusable perimeter path. */
internal fun aeroPerimeterPath(outline: Outline): Path = Path().also { path ->
    when (outline) {
        is Outline.Rectangle -> path.addRect(outline.rect)
        is Outline.Rounded -> path.addRoundRect(outline.roundRect)
        is Outline.Generic -> path.addPath(outline.path)
    }
}

/** Straight bottom section only; rounded corners remain on the main perimeter path. */
internal fun aeroBottomEdgePath(outline: Outline): Path? = when (outline) {
    is Outline.Rectangle -> Path().apply {
        moveTo(outline.rect.left, outline.rect.bottom)
        lineTo(outline.rect.right, outline.rect.bottom)
    }
    is Outline.Rounded -> {
        val bounds = Rect(
            left = outline.roundRect.left,
            top = outline.roundRect.top,
            right = outline.roundRect.right,
            bottom = outline.roundRect.bottom,
        )
        val startInset = outline.roundRect.bottomLeftCornerRadius.x.coerceIn(0f, bounds.width / 2f)
        val endInset = outline.roundRect.bottomRightCornerRadius.x.coerceIn(0f, bounds.width / 2f)
        if (bounds.width - startInset - endInset <= 0f) null else Path().apply {
            moveTo(bounds.left + startInset, bounds.bottom)
            lineTo(bounds.right - endInset, bounds.bottom)
        }
    }
    is Outline.Generic -> null
}
