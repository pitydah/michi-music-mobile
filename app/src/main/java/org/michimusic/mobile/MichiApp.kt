package org.michimusic.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.ReplayGainDao
import org.michimusic.mobile.sync.SyncWorker
import org.michimusic.player.PlayerDependencies

class MichiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MichiApp)
            modules(appModule)
        }
        try {
            PlayerDependencies.replayGainDao = get(ReplayGainDao::class.java)
            PlayerDependencies.appDao = get(AppDao::class.java)
        } catch (_: Exception) {
            // Koin may not resolve DAOs until first DB access
        }
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            SyncWorker.CHANNEL_ID,
            "Sincronización de música",
            NotificationManager.IMPORTANCE_LOW,
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
