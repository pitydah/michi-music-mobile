package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.michimusic.core.metadata.MetadataField
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

/** ME-11U: fields that open a numeric IME instead of a text keyboard. */
internal fun MetadataField.usesNumericKeyboard(): Boolean = this in setOf(
    MetadataField.TRACK_NUMBER,
    MetadataField.TRACK_TOTAL,
    MetadataField.DISC_NUMBER,
    MetadataField.DISC_TOTAL,
    MetadataField.MOVEMENT_NUMBER,
    MetadataField.MOVEMENT_TOTAL,
    MetadataField.BPM,
)

/** ME-11U: long-form tags need a real multiline editing surface. */
internal fun MetadataField.usesLongTextEditor(): Boolean = this in setOf(
    MetadataField.COMMENT,
    MetadataField.LYRICS,
    MetadataField.SYNCED_LYRICS,
)

private fun MetadataField.capitalization(): KeyboardCapitalization =
    if (this in setOf(MetadataField.TITLE, MetadataField.SUBTITLE, MetadataField.ALBUM,
            MetadataField.ALBUM_SUBTITLE, MetadataField.ARTIST, MetadataField.ALBUM_ARTIST,
            MetadataField.GENRE)) KeyboardCapitalization.Words
    else KeyboardCapitalization.None

/**
 * A single directly editable metadata value.
 *
 * It keeps a [TextFieldValue] locally so the IME composition and selection are
 * preserved across draft recompositions, while the committed text is still sent
 * through the certified [org.michimusic.core.metadata.MetadataEditorDraftSession].
 * It never calls a writer and never decides that a value is verified.
 */
@Composable
internal fun MetadataInlineValueField(
    field: MetadataField,
    value: String,
    index: Int,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    focusRequestKey: Int = 0,
    placeholder: String? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    onValueChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    var state by remember(field, index) {
        mutableStateOf(TextFieldValue(value, TextRange(0)))
    }
    val longText = field.usesLongTextEditor()
    val numeric = field.usesNumericKeyboard()
    // Any text tag may need two lines on a narrow capsule; only numeric values stay
    // single-line so their IME and alignment remain predictable.
    val wrapAllowed = !numeric && !longText
    val label = metadataFieldLabel(field)

    LaunchedEffect(value) {
        // Text equal to the emitted value means the IME owns the current selection and
        // composition; only an external change (undo, add, remove, reorder) resets it.
        if (state.text != value) {
            state = TextFieldValue(value, TextRange(0))
        }
    }
    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus()
    }
    LaunchedEffect(focusRequestKey) {
        if (focusRequestKey > 0) focusRequester.requestFocus()
    }

    BasicTextField(
        value = state,
        onValueChange = { next ->
            // The local value carries selection and composing state; the draft only
            // receives the committed text. No formatting, trimming or splitting here.
            if (wrapAllowed && next.text.contains('\n')) {
                val text = next.text.replace('\n', ' ')
                state = TextFieldValue(text, TextRange(next.selection.start.coerceAtMost(text.length)))
                onValueChange(text)
            } else {
                state = next
                onValueChange(next.text)
            }
        },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { focus ->
                onFocusChanged(focus.isFocused)
                if (focus.isFocused) scope.launch { bringIntoView.bringIntoView() }
            }
            .semantics {
                contentDescription = if (field.multiple) "$label, valor ${index + 1}" else label
            }
            .testTag(tag),
        textStyle = LocalTextStyle.current.copy(
            color = if (enabled) PureWhite else TextSecondary,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 18.sp,
        ),
        cursorBrush = SolidColor(PrimaryPinkContainer),
        singleLine = !longText && !wrapAllowed,
        minLines = 1,
        maxLines = if (longText) 12 else if (wrapAllowed) 2 else 1,
        keyboardOptions = KeyboardOptions(
            capitalization = field.capitalization(),
            keyboardType = when {
                field == MetadataField.BPM -> KeyboardType.Decimal
                numeric -> KeyboardType.Number
                else -> KeyboardType.Text
            },
            imeAction = if (longText) ImeAction.Default else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Next) },
        ),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxWidth()) {
                if (state.text.isEmpty()) {
                    Text(
                        text = placeholder ?: if (enabled) "Añadir valor" else "Sin valor",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = TextMuted,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * ME-11V: the single Aero editable capsule used by the single-track form and the
 * batch form. The label is always visible; the value is the dominant surface; the
 * contextual menu is caller-owned so no destructive action competes with typing.
 */
@Composable
internal fun MetadataFieldShell(
    field: MetadataField,
    statusLabel: String,
    statusColor: Color,
    focused: Boolean,
    modified: Boolean,
    errorText: String?,
    modifier: Modifier = Modifier,
    statusDetail: String? = null,
    hasActions: Boolean = false,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val shape = RoundedCornerShape(13.dp)
    val fill = if (highContrast) MaterialTheme.colorScheme.surfaceContainerHigh else AeroGraphite
    val neutralBorder = if (highContrast) MaterialTheme.colorScheme.outline
    else Color.White.copy(alpha = 0.10f)
    val borderColor = when {
        errorText != null -> MaterialTheme.colorScheme.error
        focused -> PrimaryPinkContainer
        modified -> PrimaryPinkContainer.copy(alpha = 0.34f)
        else -> neutralBorder
    }
    val borderWidth = if (focused || errorText != null) 1.5.dp else 1.dp
    Column(modifier.fillMaxWidth().testTag("metadata_field_${field.name.lowercase()}")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp).heightIn(min = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(6.dp)
                    .background(statusColor, RoundedCornerShape(50))
                    .semantics { contentDescription = statusLabel }
                    .testTag("metadata_field_status_${field.name.lowercase()}"),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                metadataFieldLabel(field),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                fontWeight = FontWeight.Medium,
                color = if (focused) PureWhite else TextSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (statusLabel != "Verificado" &&
                LocalDensity.current.fontScale <= 1.3f) {
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = statusColor,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
            }
            if (hasActions) {
                // 48 dp target with a 24 dp visual row: the extra touch area overflows
                // without inflating the field rhythm.
                Box(Modifier.requiredSize(48.dp), contentAlignment = Alignment.Center) {
                    actions()
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier.fillMaxWidth()
                .heightIn(min = 52.dp)
                .background(fill, shape)
                .border(borderWidth, borderColor, shape)
                .testTag("metadata_capsule_${field.name.lowercase()}")
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Column(Modifier.fillMaxWidth()
                .padding(end = if (hasActions) 40.dp else 0.dp)) {
                content()
            }
            if (statusDetail != null) {
                Text(
                    statusDetail,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            if (errorText != null) {
                Text(
                    errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("metadata_error_${field.name.lowercase()}"),
                )
            }
        }
    }
}

