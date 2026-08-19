package org.michimusic.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.mobile.navigation.MichiNavHost
import org.michimusic.mobile.ui.theme.MichiTheme

class MainActivity : ComponentActivity() {

    private val localMediaRepository: LocalMediaRepository by inject()

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
        enableEdgeToEdge()

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
            MichiTheme {
                MichiNavHost()
            }
        }
    }
}
