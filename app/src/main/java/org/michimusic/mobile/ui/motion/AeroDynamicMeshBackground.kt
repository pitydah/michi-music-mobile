package org.michimusic.mobile.ui.motion

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.AeroGradientRole
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion
import org.michimusic.mobile.ui.theme.aeroGradientStops
import kotlin.math.sin

/**
 * Compose Canvas alternative to external OpenGL surfaces. Does not sample/alter
 * the actual cover bitmap. Four compositing passes, no offscreen blur or shaders.
 * The mesh is ambient artwork only, never Signal Truth evidence or an audio meter.
 */
@Composable
fun AeroDynamicMeshBackground(
    track: Track?,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val prefs = LocalVisualPreferences.current
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var inForeground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            inForeground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val powerManager = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val memoryManager = remember(context) { context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }
    var powerSaving by remember(powerManager) { mutableStateOf(powerManager?.isPowerSaveMode == true) }
    DisposableEffect(context, powerManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                    powerSaving = powerManager?.isPowerSaveMode == true
                }
            }
        }
        // Some OEM builds refuse dynamic registration even for system-only
        // broadcasts. Rendering must survive that failure; the initial value
        // from PowerManager remains authoritative until the screen is rebuilt.
        val registered = runCatching {
            context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            true
        }.getOrDefault(false)
        onDispose { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }

    // Re-sample PowerManager on every foreground return as a fallback if an
    // OEM rejected the broadcast registration while the screen was active.
    LaunchedEffect(inForeground, powerManager) {
        if (inForeground) powerSaving = powerManager?.isPowerSaveMode == true
    }

    // Keep the last CONFIRMED cover palette visible while the next local artwork
    // decodes. Resetting produceState(initialValue = default) on every track was
    // flashing plum between successive songs even on a warm media cache.
    var palette by remember { mutableStateOf(AeroDefaultDynamicPalette) }
    // Only a LOCAL cover ID or a SYNCED file that is actually local is eligible.
    // This prevents a remote album ID accidentally resolving against MediaStore.
    val localPaletteSource = track?.let { aeroLocalPaletteSource(it.source, it.coverId, it.filepath) }
    LaunchedEffect(context, track?.id, localPaletteSource,
        prefs.highContrast, memoryManager?.isLowRamDevice, inForeground, powerSaving) {
        if (localPaletteSource == null || prefs.highContrast ||
            memoryManager?.isLowRamDevice == true) {
            palette = AeroDefaultDynamicPalette
        } else if (aeroShouldLoadDynamicPalette(
                foreground = inForeground,
                highContrast = prefs.highContrast,
                lowRamDevice = memoryManager?.isLowRamDevice == true,
                coverId = localPaletteSource.coverId,
                mediaUri = localPaletteSource.mediaUri,
                powerSaveMode = powerSaving,
            )) {
            // Stopping the activity cancels decode and preserves the last
            // palette. Returning to STARTED fetches the current song, not an
            // old track that arrived during background playback.
            palette = AeroArtworkPaletteLoader.resolve(
                context, localPaletteSource.coverId, localPaletteSource.mediaUri,
            )
        }
    }
    val colorSpec = if (prefs.reducedMotion || prefs.highContrast) snap<Color>() else
        tween(durationMillis = 850, easing = MichiMotion.easingDecelerate)
    val upper by animateColorAsState(palette.upper, colorSpec, label = "mesh_upper")
    val center by animateColorAsState(palette.center, colorSpec, label = "mesh_center")
    val lower by animateColorAsState(palette.lower, colorSpec, label = "mesh_lower")

    val animate = aeroDynamicMeshShouldMove(
        playing = playing,
        foreground = inForeground,
        reducedMotion = prefs.reducedMotion,
        highContrast = prefs.highContrast,
        powerSaveMode = powerSaving,
        lowRamDevice = memoryManager?.isLowRamDevice == true,
    )
    // ~8fps ambient drift. When paused/hidden, preserve the last mesh position:
    // resetting phase to zero caused an unwanted visible jump on Play/Pause.
    var drift by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        while (animate) {
            delay(125L)
            drift = aeroDynamicMeshNextPhase(drift, enabled = true)
        }
    }
    val contrastGradient = if (prefs.highContrast)
        aeroGradientStops(AeroGradientRole.HeroToCanvas, true) else emptyList()
    Canvas(modifier = modifier.fillMaxSize().testTag("aero_dynamic_mesh_background")) {
        if (prefs.highContrast) {
            // High-contrast policy intentionally collapses decorative gradients
            // to one solid semantic surface. Brush.verticalGradient requires at
            // least two colors, so render that authoritative solid directly.
            drawRect(color = contrastGradient.single())
            return@Canvas
        }
        drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF16111F), Color(0xFF0E0D16))))
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val swing = sin(drift) * 0.045f
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(upper.copy(alpha = 0.88f), upper.copy(alpha = 0f)),
                center = Offset(w * (0.23f + swing), h * 0.20f),
                radius = maxOf(w, h) * 0.77f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(center.copy(alpha = 0.85f), center.copy(alpha = 0f)),
                center = Offset(w * (0.82f - swing), h * (0.55f + swing * 0.5f)),
                radius = maxOf(w, h) * 0.78f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(lower.copy(alpha = 0.72f), lower.copy(alpha = 0f)),
                center = Offset(w * 0.33f, h * (0.97f - swing)),
                radius = maxOf(w, h) * 0.62f,
            ),
        )
        // Opaque enough around metadata and transport controls without touching
        // album art; avoids simulated bloom, blur, texture grain and flash effects.
        drawRect(brush = Brush.verticalGradient(listOf(
            Color(0x08000000), Color(0x30080A12), Color(0x8B080A13),
        )))
    }
}
