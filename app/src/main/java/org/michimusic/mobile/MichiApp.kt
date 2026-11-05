package org.michimusic.mobile

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.os.StrictMode
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.michimusic.data.dataModule
import org.michimusic.mobile.network.MICHI_HTTP_USER_AGENT
import org.michimusic.mobile.di.appDao as michiAppDao
import org.michimusic.mobile.di.replayGainDao as michiReplayGainDao
import org.michimusic.mobile.sync.SyncWorker
import org.michimusic.mobile.ui.components.EmbeddedCoverCache
import org.michimusic.player.PlayerDependencies

class MichiApp : Application() {

    /**
     * Application-scoped work that must outlive a single Activity, such as loading the
     * visual preferences before the first frame is themed.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (org.koin.core.context.GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(this@MichiApp)
                modules(appModule, dataModule, playerModule, remoteModule, syncModule)
            }
        }
        try {
            PlayerDependencies.replayGainDao = michiReplayGainDao
            PlayerDependencies.appDao = michiAppDao
            val koin = org.koin.core.context.GlobalContext.get()
            PlayerDependencies.trackDao = koin.getOrNull()
            PlayerDependencies.audioEffects = koin.getOrNull()
            PlayerDependencies.usbDacManager = koin.getOrNull()
            PlayerDependencies.replayGainProcessor = koin.getOrNull()
            PlayerDependencies.headphoneProfileStore = koin.getOrNull()
            PlayerDependencies.headphonePeqProcessor = koin.getOrNull()
            PlayerDependencies.audioModeManager = koin.getOrNull()
            PlayerDependencies.signalTruthTracker = koin.getOrNull()
        } catch (e: Exception) {
            Log.w("MichiApp", "No se pudieron resolver dependencias para PlayerDependencies: ${e.message}")
        }
        createNotificationChannels()
        configureImageLoader()
        configureStrictMode()
        startVisualPreferencesLoad()
        startIdentityResolution()
    }

    /**
     * The stored theme is disk state. Loading it here, on an application scope, keeps the
     * read off the main thread; [org.michimusic.mobile.ui.theme.VisualPreferencesRepository.ready]
     * holds the first frame until it lands so the app never flashes the default theme.
     */
    /**
     * Resolves the device identity on IO before the user can reach pairing.
     * Resolving it used to happen inside the Koin graph while the first frame was
     * composed, which opened the Keystore on the main thread.
     */
    private fun startIdentityResolution() {
        try {
            org.koin.core.context.GlobalContext.get()
                .get<org.michimusic.link.identity.MichiIdentityAuthority>()
                .start(applicationScope)
        } catch (e: Exception) {
            Log.w("MichiApp", "No se pudo resolver la identidad del dispositivo: ${e.message}")
        }
    }

    private fun startVisualPreferencesLoad() {
        try {
            org.koin.core.context.GlobalContext.get()
                .get<org.michimusic.mobile.ui.theme.VisualPreferencesRepository>()
                .start(applicationScope)
        } catch (e: Exception) {
            Log.w("MichiApp", "No se pudieron cargar las preferencias visuales: ${e.message}")
        }
    }

    /**
     * Debug-only StrictMode. Disk and network reads on the main thread are the class of
     * regression this project keeps reintroducing (the search filter that touched files
     * from composition), and they are invisible without a policy that reports them.
     * Release builds pay nothing: a non-debuggable package returns before installing it.
     */
    private fun configureStrictMode() {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build(),
        )
    }

    @Suppress("DEPRECATION") // the RUNNING_* levels still arrive on API 31..33 devices
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        EmbeddedCoverCache.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            SingletonImageLoader.get(this).memoryCache?.clear()
        }
    }

    private fun configureImageLoader() {
        val activityManager = getSystemService(ActivityManager::class.java)
        val lowRam = activityManager?.isLowRamDevice == true
        val memoryClass = activityManager?.memoryClass ?: 128
        EmbeddedCoverCache.configure(memoryClassMb = memoryClass, isLowRamDevice = lowRam)

        // One shared budget instead of two generous ones: Coil keeps 20 % of the heap
        // (10 % on low-RAM) and the embedded-cover LRU takes its own small share, so
        // scrolling a library does not hold the same cover twice at full size.
        val memoryPercent = if (lowRam) 0.10 else 0.20
        val diskBytes = (if (lowRam) 32L else 50L) * 1024 * 1024
        val imageLoader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(this, memoryPercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil").toOkioPath())
                    .maxSizeBytes(diskBytes)
                    .build()
            }
            .components {
                // Wikimedia rejects Coil's default `okhttp/*` user agent with
                // HTTP 403, so every image request carries Michi's identity.
                add(OkHttpNetworkFetcherFactory(callFactory = {
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder()
                                    .header("User-Agent", MICHI_HTTP_USER_AGENT)
                                    .build(),
                            )
                        }
                        .build()
                }))
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
