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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.koin.android.ext.android.inject
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.mobile.navigation.MichiNavHost
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.VisualPreferencesRepository

class MainActivity : ComponentActivity() {

    private val visualPreferencesRepository: VisualPreferencesRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Michi renders a dark canvas on every screen. Explicit light system glyphs
        // avoid black status icons over the obsidian background (K-06).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            val ready by visualPreferencesRepository.ready.collectAsStateWithLifecycle()
            val visualPrefs by visualPreferencesRepository.preferences.collectAsStateWithLifecycle()
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
