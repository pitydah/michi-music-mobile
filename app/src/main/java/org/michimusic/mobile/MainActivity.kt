package org.michimusic.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.android.ext.android.inject
import org.michimusic.mobile.library.MusicRootAuthority
import org.michimusic.mobile.navigation.MichiNavHost
import org.michimusic.mobile.onboarding.FirstRunWizard
import org.michimusic.mobile.onboarding.OnboardingSnapshot
import org.michimusic.mobile.onboarding.OnboardingDestination
import org.michimusic.mobile.onboarding.OnboardingStore
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.VisualPreferencesRepository

class MainActivity : ComponentActivity() {

    private val visualPreferencesRepository: VisualPreferencesRepository by inject()
    private val musicRootAuthority: MusicRootAuthority by inject()

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
            val onboardingStore = remember { OnboardingStore(applicationContext) }
            var onboarding by remember { mutableStateOf<OnboardingSnapshot?>(null) }
            var showReplay by rememberSaveable { mutableStateOf(false) }
            var completionRoute by rememberSaveable { mutableStateOf<String?>(null) }
            var completionRequestId by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
            var loadError by remember { mutableStateOf(false) }
            var reloadAttempt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            LaunchedEffect(onboardingStore, reloadAttempt) {
                try {
                    onboarding = onboardingStore.load()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Never silently overwrite an upgrade/completed state on a read failure.
                    loadError = true
                }
            }
            if (loadError) {
                androidx.compose.material3.Surface(color = SurfaceObsidian) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        androidx.compose.material3.Text("No se pudo abrir la configuración local.", color = androidx.compose.ui.graphics.Color.White)
                        androidx.compose.material3.TextButton(onClick = {
                            loadError = false
                            onboarding = null
                            // A reload is requested through the explicit retry state.
                            reloadAttempt++
                        }) { androidx.compose.material3.Text("Reintentar") }
                    }
                }
            } else if (!ready || onboarding == null) {
                // The stored theme arrives a frame or two later. Painting the obsidian base
                // instead of the default theme keeps the launch visually continuous: no
                // flash of the wrong contrast settings, and nothing blocks the main thread.
                Box(modifier = Modifier.fillMaxSize().background(SurfaceObsidian))
            } else {
                MichiTheme(visualPreferences = visualPrefs) {
                    val stored = requireNotNull(onboarding)
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (stored.completed) {
                            // Preserve the existing navigation graph during a settings replay.
                            MichiNavHost(
                                onReopenOnboarding = { showReplay = true },
                                onboardingDestination = completionRoute,
                                completionRequestId = completionRequestId,
                                onboardingSnapshot = stored,
                            )
                        }
                        if (!stored.completed || showReplay) {
                            FirstRunWizard(
                                initial = stored,
                                replay = showReplay,
                                saveDraft = { draft ->
                                    // Replaying is transactional: abandoning preserves saved preferences.
                                    if (showReplay) true else {
                                        val saved = onboardingStore.saveDraft(draft)
                                        if (saved) onboarding = draft
                                        saved
                                    }
                                },
                                finish = { draft, rootSelection ->
                                    val rootChange = rootSelection?.let { selection ->
                                        musicRootAuthority.apply(selection, clearPlayback = false)
                                    }
                                    if (rootChange != null && !rootChange.success) {
                                        false
                                    } else {
                                        val saved = onboardingStore.complete(draft)
                                        if (saved) {
                                            onboarding = draft.copy(completed = true)
                                            rootChange?.let { change ->
                                                try {
                                                    musicRootAuthority.activate(change)
                                                } catch (e: kotlinx.coroutines.CancellationException) {
                                                    throw e
                                                } catch (_: Exception) {
                                                    // The root is already confirmed; a later cache read
                                                    // recovers from a failed post-commit notification.
                                                }
                                            }
                                            true
                                        } else {
                                            rootChange?.let { change ->
                                                musicRootAuthority.rollback(change)
                                            }
                                            false
                                        }
                                    }
                                },
                                onCompleted = { destination ->
                                    completionRoute = destination.route
                                    completionRequestId++
                                    showReplay = false
                                },
                                onDismissReplay = { showReplay = false },
                            )
                        }
                    }
                }
            }
        }
    }
}
