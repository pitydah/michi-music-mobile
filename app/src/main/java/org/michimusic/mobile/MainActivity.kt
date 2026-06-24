package org.michimusic.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.michimusic.mobile.navigation.MichiNavHost
import org.michimusic.mobile.ui.theme.MichiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MichiTheme {
                MichiNavHost()
            }
        }
    }
}
