package org.michimusic.mobile

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.ReplayGainDao
import org.michimusic.player.MichiPlaybackService

class MichiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MichiApp)
            modules(appModule)
        }
        MichiPlaybackService.companionReplayGainDao = get(ReplayGainDao::class.java)
        MichiPlaybackService.companionAppDao = get(AppDao::class.java)
    }
}
