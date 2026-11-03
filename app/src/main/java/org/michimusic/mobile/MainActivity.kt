package org.michimusic.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.koin.android.ext.android.inject
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.mobile.navigation.MichiNavHost
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.VisualPreferencesRepository

class MainActivity : ComponentActivity() {

    private val localMediaRepository: LocalMediaRepository by inject()
    private val visualPreferencesRepository: VisualPreferencesRepository by inject()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // The first loadTracks() call (triggered by Home's LaunchedEffect right after
            // setContent) can race ahead of this permission grant and cache an empty result
            // via a failed MediaStore query. Invalidate that before recreate() reloads the UI,
            // otherwise the 30s-TTL cache would keep serving the stale empty list.
            lifecycleScope.launch {
                localMediaRepository.invalidateCache()
                recreate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Michi renders a dark canvas on every screen. Explicit light system glyphs
        // avoid black status icons over the obsidian background (K-06).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(audioPermission)
        }

        setContent {
            val ready by visualPreferencesRepository.ready.collectAsState()
            val visualPrefs by visualPreferencesRepository.preferences.collectAsState()
            if (!ready) {
                // The stored theme arrives a frame or two later. Painting the obsidian base
                // instead of the default theme keeps the launch visually continuous: no
                // flash of the wrong contrast settings, and nothing blocks the main thread.
                Box(modifier = Modifier.fillMaxSize().background(SurfaceObsidian))
            } else {
                MichiTheme(visualPreferences = visualPrefs) {
                    MichiNavHost()
                }
            }
        }
    }
}
