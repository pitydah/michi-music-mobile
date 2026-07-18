package org.michimusic.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.michimusic.mobile.di.appDao as michiAppDao
import org.michimusic.mobile.di.replayGainDao as michiReplayGainDao
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
            PlayerDependencies.replayGainDao = michiReplayGainDao
            PlayerDependencies.appDao = michiAppDao
        } catch (e: Exception) {
            Log.w("MichiApp", "No se pudieron resolver DAOs para PlayerDependencies: ${e.message}")
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
