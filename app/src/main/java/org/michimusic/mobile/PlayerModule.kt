package org.michimusic.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.michimusic.player.AudioController
import org.michimusic.player.ReplayGainConfig

val playerModule = module {
    single {
        ReplayGainConfig.init(androidContext())
        AudioController(androidContext(), CoroutineScope(Dispatchers.Main + SupervisorJob()))
    }
}
