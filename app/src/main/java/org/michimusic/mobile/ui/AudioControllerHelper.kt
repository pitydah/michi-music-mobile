package org.michimusic.mobile.ui

import androidx.compose.runtime.Composable
import org.koin.compose.koinInject
import org.michimusic.player.AudioController

@Composable
fun rememberAudioController(): AudioController = koinInject()
