package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.ErrorColor
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * One low-profile Aero instrument field for dialogs, device credentials and settings.
 * Callers still own value, validation, IME, disabled state and their semantic accent.
 * No extra card/blur/border is drawn around Material3's single outlined stroke.
 */
internal val AeroDialogFieldShape = RoundedCornerShape(12.dp)

@Composable
internal fun aeroDialogFieldColors(accent: Color = TertiaryCyan): TextFieldColors {
    val contrast = LocalVisualPreferences.current.highContrast
    val outline = if (contrast) PureWhite.copy(alpha = 0.92f) else AeroHairline
    val focus = if (contrast) PureWhite else accent
    // The dark, nearly opaque inset field is deliberate: never tint editable text
    // with a glass shader or reapply the material grain to every keystroke.
    val well = if (contrast) Color(0xFF0C0F14) else Color(0xFF141923)
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = PureWhite,
        unfocusedTextColor = PureWhite,
        disabledTextColor = TextSecondary,
        errorTextColor = PureWhite,
        cursorColor = focus,
        errorCursorColor = ErrorColor,
        focusedBorderColor = focus,
        unfocusedBorderColor = outline,
        disabledBorderColor = outline.copy(alpha = if (contrast) 0.72f else 0.40f),
        errorBorderColor = ErrorColor,
        focusedLabelColor = focus,
        unfocusedLabelColor = TextSecondary,
        focusedPlaceholderColor = TextSecondary,
        unfocusedPlaceholderColor = TextSecondary,
        focusedContainerColor = well,
        unfocusedContainerColor = well,
        disabledContainerColor = well,
        errorContainerColor = well,
    )
}

/**
 * Opt-in viewport for small keyboard/form dialogs. The input and its confirm
 * action stay in the same scroll container at large font scales or with IME.
 * Never use this on dialogs already containing a LazyColumn or scrollable grid.
 */
@Composable
internal fun Modifier.aeroModalFormViewport(): Modifier {
    // LocalWindowInfo reports the window this dialog actually occupies, so a
    // freeform or split-screen window does not size the form against the whole
    // display the way Configuration.screenHeightDp does.
    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val maxHeight = (windowHeight * 0.78f).coerceAtLeast(240.dp)
    return this.heightIn(max = maxHeight).verticalScroll(rememberScrollState())
}
