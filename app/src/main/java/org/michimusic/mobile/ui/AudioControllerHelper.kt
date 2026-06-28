package org.michimusic.mobile.ui

import org.koin.java.KoinJavaComponent
import org.michimusic.player.AudioController

fun getAudioController(): AudioController? = runCatching {
    KoinJavaComponent.get<AudioController>(AudioController::class.java)
}.getOrNull()
