package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * ONE adaptive modal container for genre editing (NORM-P0-02).
 *
 * A single bottom-anchored modal surface — not a cascade of nested dialogs — with a
 * fixed header (title, real track identity, honest mode badge), a single scroll
 * body and a fixed footer that keeps `Cerrar` and the contextual action reachable
 * while the IME is open. The height cap derives from the safe screen space instead
 * of the historical fixed `heightIn(max = 520.dp)` that squeezed the action row.
 *
 * It follows the same visual language as the premium Michi bottom sheet (graphite
 * surface, 24dp top corners) and is used by BOTH entry points: the Genres detail
 * host and the Now Playing metadata block.
 */
@Composable
internal fun MichiGenreEditorSheet(
    onDismissRequest: () -> Unit,
    trackTitle: String,
    trackArtist: String,
    state: MichiGenreEditorState,
    writesCertified: Boolean,
    footerAction: @Composable () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    // Safe cap: never a fixed 520dp for every form, never the whole screen.
    val maxSheetHeight = (configuration.screenHeightDp * 0.88f).dp
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier.fillMaxSize().imePadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // A plain styled Box, NOT a Material3 [Surface]. `Surface` installs an
            // unconditional `Modifier.pointerInput(Unit) {}` shim to swallow taps
            // aimed behind it, and inside this dialog window that shim also
            // consumed the taps aimed at the sheet's own controls (verified with a
            // tap probe: the enclosing clickable received the event while every
            // child button inside the Surface received none). The modal window
            // already blocks input to the app behind it, so the shim is redundant
            // and only breaks the editor. Same obsidian surfaces + 24dp top corners,
            // same explicit content colour.
            CompositionLocalProvider(LocalContentColor provides PureWhite) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                        .heightIn(max = maxSheetHeight)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(SurfaceObsidian)
                        .testTag("mi26_genre_editor_sheet"),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    ) {
                        // Fixed header: cannot scroll under the status bar and never
                        // duplicates the baked-in artwork title.
                        Column(
                            Modifier.fillMaxWidth().testTag("mi26_genre_editor_header"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "Normalizar géneros",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = PureWhite,
                                modifier = Modifier.testTag("mi26_genre_editor_title"),
                            )
                            Text(
                                listOf(trackTitle, trackArtist).filter(String::isNotBlank)
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOf(
                                    MichiGenreEditorContract.modeBadge(writesCertified),
                                    genreEditorStateLabel(state),
                                ).distinct().joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted,
                                modifier = Modifier.testTag("mi26_genre_editor_mode")
                                    .semantics { contentDescription = genreEditorStateLabel(state) },
                            )
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp), color = GlassBorderHigh)
                        // The only scroll owner; the footer stays pinned while the IME is open.
                        Column(
                            Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp)
                                .testTag("mi26_genre_editor_body"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            content = body,
                        )
                        HorizontalDivider(color = GlassBorderHigh)
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp)
                                .testTag("mi26_genre_editor_footer"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            footerAction()
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Short Spanish label for the contract state. The badge never replaces the
 * evidence line, it only names what the editor is currently doing.
 */
internal fun genreEditorStateLabel(state: MichiGenreEditorState): String = when (state) {
    MichiGenreEditorState.UNVERIFIED_METADATA -> "Metadatos sin verificar"
    MichiGenreEditorState.VERIFIED_EMPTY_GENRE -> "Género verificado: sin etiquetas"
    MichiGenreEditorState.VERIFIED_GENRES -> "Género verificado"
    MichiGenreEditorState.PREVIEW_CHANGED -> "Cambios sin guardar"
    MichiGenreEditorState.LOOKUP_PENDING -> "Consulta en curso"
    MichiGenreEditorState.LOOKUP_CANCELLED -> "Consulta cancelada"
    MichiGenreEditorState.LOOKUP_ERROR -> "Error de consulta"
    MichiGenreEditorState.WRITE_DISABLED_UNCERTIFIED -> "Solo previsualización"
    MichiGenreEditorState.WRITE_BLOCKED_ACTIVE_DESCRIPTOR ->
        "Lectura permitida · escritura bloqueada"
    MichiGenreEditorState.WRITE_AWAITING_ANDROID_PERMISSION -> "Esperando permiso de Android"
    MichiGenreEditorState.WRITE_VERIFIED -> "Guardado verificado"
    MichiGenreEditorState.WRITE_FAILED_RECOVERY_PENDING -> "Recuperación pendiente"
}
