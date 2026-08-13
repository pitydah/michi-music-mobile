package org.michimusic.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.michimusic.data.dataModule
import org.michimusic.mobile.di.appDao as michiAppDao
import org.michimusic.mobile.di.replayGainDao as michiReplayGainDao
import org.michimusic.mobile.sync.SyncWorker
import org.michimusic.player.PlayerDependencies

class MichiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MichiApp)
            modules(appModule, dataModule, playerModule, remoteModule, syncModule)
        }
        try {
            PlayerDependencies.replayGainDao = michiReplayGainDao
            PlayerDependencies.appDao = michiAppDao
        } catch (e: Exception) {
            Log.w("MichiApp", "No se pudieron resolver DAOs para PlayerDependencies: ${e.message}")
        }
        createNotificationChannels()
        configureImageLoader()
    }

    private fun configureImageLoader() {
        val imageLoader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(this, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil").toOkioPath())
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
        SingletonImageLoader.setSafe { imageLoader }
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
