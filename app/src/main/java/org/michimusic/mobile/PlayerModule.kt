package org.michimusic.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.michimusic.player.AudioController

val playerModule = module {
    single { AudioController(androidContext(), CoroutineScope(Dispatchers.Main + SupervisorJob())) }
}
