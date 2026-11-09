package org.michimusic.mobile.ui.components.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.aeroMaterialStyle
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.PureWhite

/**
 * Standardized premium modal bottom sheet.
 *
 * Rules:
 * - 24dp top rounded corners by default.
 * - Minimal, accessible drag handle with subtle contrast.
 * - Seamless support for high contrast theme mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MichiBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val visualPrefs = LocalVisualPreferences.current
    val highContrast = visualPrefs.highContrast

    val topShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val material = aeroMaterialStyle(AeroSurfaceRole.Floating, highContrast)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        // A border on ModalBottomSheet's full-window root draws outside the sheet and
        // leaves a displaced rounded rectangle while the modal animates. Shape and
        // opaque material fill still define the visible sheet surface.
        modifier = modifier,
        sheetState = sheetState,
        shape = topShape,
        // Fully opaque graphite: Material3 tonal elevation must not repaint Aero's token.
        containerColor = material.fill,
        contentColor = PureWhite,
        tonalElevation = 0.dp,
        dragHandle = {
            // The specular line is painted inside the sheet's own drag-handle slot,
            // not on the full-window root, so it moves with the sheet and cannot
            // leave the historical displaced rectangle during open/close.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithCache {
                        val line = 0.65.dp.toPx()
                        val light = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.12f to PureWhite.copy(alpha = 0.22f),
                            0.50f to PureWhite.copy(alpha = 0.10f),
                            0.88f to PureWhite.copy(alpha = 0.22f),
                            1f to Color.Transparent,
                        )
                        onDrawBehind {
                            if (!highContrast) drawRect(brush = light, size = Size(size.width, line))
                        }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Spacer(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (highContrast) PureWhite else PureWhite.copy(alpha = 0.25f)),
                )
            }
        },
        content = content,
    )
}
