package org.michimusic.mobile.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.michimusic.core.models.TrackSource
import android.net.Uri
import java.io.File
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion

/** Snapshot everything rendered by the outgoing cover before the next track arrives. */
data class AeroTrackArtSnapshot(
    val trackId: String,
    val coverId: String,
    val filePath: String,
    val title: String,
    val source: TrackSource = TrackSource.LOCAL,
)

fun aeroTrackArtSnapshot(track: Track?): AeroTrackArtSnapshot? = track?.let {
    AeroTrackArtSnapshot(it.id, it.coverId, it.filepath, it.title, it.source)
}

/**
 * A remote library album ID is not the phone's MediaStore album ID. Preserve
 * actual remote artwork URLs but never resolve a numeric ID for streaming or
 * synced audio against an unrelated local album. For LOCAL only, MediaStore is
 * the authoritative source.
 */
fun aeroTrackArtworkCoverModel(source: TrackSource, coverId: String): String? {
    val value = coverId.trim().takeIf { it.isNotBlank() } ?: return null
    return if (source != TrackSource.LOCAL && value.toLongOrNull() != null) null else value
}

/** Embedded artwork is eligible only when we really hold an on-device file. */
fun aeroTrackArtworkLocalFallback(source: TrackSource, filePath: String): String? {
    if (source == TrackSource.STREAMING) return null
    val path = filePath.trim()
    return path.takeIf {
        it.startsWith("content://", ignoreCase = true) ||
            it.startsWith("file://", ignoreCase = true) ||
            (it.startsWith("/") && !it.startsWith("//"))
    }
}

/** No transition is scheduled for changes to transport position, route, or playback flags. */
fun aeroTrackArtworkDurationMs(reducedMotion: Boolean, highContrast: Boolean): Int =
    if (reducedMotion || highContrast) 0 else MichiMotion.durationStandard

/**
 * One source of artwork transition policy for MiniPlayer and Now Playing.
 * The outgoing cover is a captured immutable Track snapshot, never a mutable
 * closure that can repaint the *previous* layer using the *next* file's URI.
 * No bitmap tint, animated blur, or invalidation driven by playback position.
 */
@Composable
fun AeroTrackArtworkMotion(
    track: Track?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    borderWidth: Dp = 0.dp,
) {
    val preferences = LocalVisualPreferences.current
    val duration = aeroTrackArtworkDurationMs(preferences.reducedMotion, preferences.highContrast)
    Crossfade(
        targetState = aeroTrackArtSnapshot(track),
        modifier = modifier,
        animationSpec = tween(durationMillis = duration, easing = MichiMotion.easingDecelerate),
        label = "aero_track_artwork_change",
    ) { snapshot ->
        if (snapshot == null) {
            // Explicit absent-artwork placeholder: never reuse a previous track's cover.
            AlbumArtView(
                coverStyle = coverStyleFor(null),
                imageModel = null,
                fallbackMediaUri = null,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = cornerRadius,
                borderWidth = borderWidth,
            )
        } else {
            AlbumArtView(
                coverStyle = coverStyleFor(snapshot.coverId.ifBlank { snapshot.title }),
                imageModel = aeroTrackArtworkCoverModel(snapshot.source, snapshot.coverId),
                fallbackMediaUri = aeroTrackArtworkLocalFallback(snapshot.source, snapshot.filePath)?.let {
                    // MediaStore's original file path is often absolute rather than a
                    // content URI. Convert locally; never construct an HTTP request.
                    if (it.startsWith("/")) Uri.fromFile(File(it)).toString() else it
                },
                modifier = Modifier.fillMaxSize(),
                cornerRadius = cornerRadius,
                borderWidth = borderWidth,
            )
        }
    }
}

data class AeroTrackTitleSnapshot(val trackId: String?, val title: String)

/** Fade only a real track/title change. Layout and accessibility text remain truthful. */
@Composable
fun AeroTrackTitleMotion(
    trackId: String?,
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    color: Color,
    maxLines: Int = 1,
    textAlign: TextAlign = TextAlign.Start,
) {
    val preferences = LocalVisualPreferences.current
    val reduce = preferences.reducedMotion || preferences.highContrast
    AnimatedContent(
        targetState = AeroTrackTitleSnapshot(trackId, title),
        modifier = modifier,
        contentAlignment = Alignment.Center,
        contentKey = { it.trackId to it.title },
        transitionSpec = {
            if (reduce) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(tween(MichiMotion.durationStandard, easing = MichiMotion.easingDecelerate)) togetherWith
                    fadeOut(tween(MichiMotion.durationFast))
            }
        },
        label = "aero_track_title_change",
    ) { snapshot ->
        Text(
            text = snapshot.title,
            color = color,
            style = style,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
