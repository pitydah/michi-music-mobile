package org.michimusic.mobile.onboarding

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The persisted wizard is independent from user identities, playback and library databases. */
enum class LibraryChoice { PHONE, IMPORT_FILES, LATER }
enum class AudioChoice { ANDROID, USB_REMINDER }

/** Trusted routes only. Navigation runs after the completion transaction succeeds. */
enum class OnboardingDestination(val route: String) {
    HOME("home"),
    DEVICES("devices"),
    LINK_SETTINGS("settings/privacy"),
    AUDIO_ROUTE("audio-route"),
}

data class OnboardingSnapshot(
    val completed: Boolean = false,
    val step: Int = 0,
    val library: LibraryChoice = LibraryChoice.PHONE,
    val discoverLater: Boolean = false,
    val linkLater: Boolean = false,
    val audio: AudioChoice = AudioChoice.ANDROID,
)

/** All SharedPreferences access is routed to IO to avoid first-frame disk access. */
class OnboardingStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fileName: String = "michi_first_run",
    private val installTimes: (Context) -> Pair<Long, Long> = { app ->
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.firstInstallTime to info.lastUpdateTime
    },
) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences by lazy {
        appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    suspend fun load(): OnboardingSnapshot = withContext(ioDispatcher) {
        // A legacy installation had no wizard marker. Do not unexpectedly block
        // returning users on an app upgrade. First installs still see the wizard.
        // A draft from a prior wizard version must NEVER be auto-completed.
        val keys = preferences.all
        if ("completed_v1" !in keys && "step_v1" !in keys) {
            val times = runCatching { installTimes(appContext) }.getOrNull()
            if (times != null && shouldSkipWizardForLegacyUpgrade(times.first, times.second)) {
                if (!preferences.edit().putBoolean("completed_v1", true).commit()) {
                    throw IOException("Unable to persist legacy onboarding migration")
                }
            }
        }
        val values = preferences.all // fresh snapshot after a possible migration
        OnboardingSnapshot(
            completed = values["completed_v1"] as? Boolean ?: false,
            step = (values["step_v1"] as? Int ?: 0).coerceIn(0, 4),
            library = parseEnum(values["library_v1"] as? String, LibraryChoice.PHONE),
            discoverLater = values["discover_later_v1"] as? Boolean ?: false,
            linkLater = values["link_later_v1"] as? Boolean ?: false,
            audio = parseEnum(values["audio_v1"] as? String, AudioChoice.ANDROID),
        )
    }

    suspend fun saveDraft(snapshot: OnboardingSnapshot): Boolean = withContext(ioDispatcher) {
        preferences.edit().apply {
            putInt("step_v1", snapshot.step.coerceIn(0, 4))
            putString("library_v1", snapshot.library.name)
            putBoolean("discover_later_v1", snapshot.discoverLater)
            putBoolean("link_later_v1", snapshot.linkLater)
            putString("audio_v1", snapshot.audio.name)
        }.commit()
    }

    /** Completion is a single durable transaction, never set before the rest of the fields. */
    suspend fun complete(snapshot: OnboardingSnapshot): Boolean = withContext(ioDispatcher) {
        preferences.edit().apply {
            putInt("step_v1", 4)
            putString("library_v1", snapshot.library.name)
            putBoolean("discover_later_v1", snapshot.discoverLater)
            putBoolean("link_later_v1", snapshot.linkLater)
            putString("audio_v1", snapshot.audio.name)
            putBoolean("completed_v1", true)
        }.commit()
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}
