package org.michimusic.mobile.playback

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.michimusic.player.PlayerDependencies
import org.michimusic.player.ReplayGainMode
import org.michimusic.player.ReplayGainPreAmp

/** The original michi_settings preferences remain the only persistence authority.
 * No service, polling loop or DSP engine is created by opening Settings.
 */
internal class SettingsReplayGainGateway(context: Context) {
    companion object {
        @Volatile private var instance: SettingsReplayGainGateway? = null
        fun shared(context: Context): SettingsReplayGainGateway =
            instance ?: synchronized(this) {
                instance ?: SettingsReplayGainGateway(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.applicationContext.getSharedPreferences("michi_settings", Context.MODE_PRIVATE)
    private val keys = setOf("replaygain_mode", "replaygain_preamp_with", "replaygain_preamp_without")

    private val adapter = object : SettingsReplayGainTransactionPort {
        override suspend fun read(): SettingsReplayGainSnapshot = withContext(Dispatchers.IO) {
            readSnapshot()
        }

        override suspend fun persist(edit: SettingsReplayGainEdit): Boolean = withContext(Dispatchers.IO) {
            val editor = prefs.edit()
            when (edit) {
                is SettingsReplayGainEdit.Mode -> editor.putString("replaygain_mode", edit.value.name)
                is SettingsReplayGainEdit.WithTags -> editor.putFloat("replaygain_preamp_with", edit.value)
                is SettingsReplayGainEdit.WithoutTags -> editor.putFloat("replaygain_preamp_without", edit.value)
            }
            // commit confirms the on-disk write; never block the Compose/UI thread.
            editor.commit()
        }

        override suspend fun configure(value: SettingsReplayGainSnapshot): Boolean? =
            withContext(Dispatchers.Main.immediate) {
                val authority = PlayerDependencies.outputAuthority.value
                if (!authority.isLocal || authority.endpointId != "local_phone" ||
                    PlayerDependencies.replayGainProcessor == null) return@withContext null
                PlayerDependencies.configureReplayGain(value.mode,
                    ReplayGainPreAmp(with = value.withTagsDb, without = value.withoutTagsDb))
                val observed = PlayerDependencies.replayGainPreAmp
                val configured = PlayerDependencies.replayGainMode == value.mode &&
                    kotlin.math.abs(observed.with - value.withTagsDb) < .01f &&
                    kotlin.math.abs(observed.without - value.withoutTagsDb) < .01f
                if (configured) PlayerDependencies.signalTruthTracker?.recompute()
                configured
            }
    }
    private val transaction = SettingsReplayGainTransaction(adapter)

    /** Notifications are coalesced; file reads happen on IO while view is visible. */
    fun observe(): Flow<SettingsReplayGainSnapshot> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed in keys) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { readSnapshot() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun readSnapshot() = SettingsReplayGainSnapshot(
        mode = settingsReplayGainMode(prefs.all["replaygain_mode"]),
        withTagsDb = prefs.getFloat("replaygain_preamp_with", 0f),
        withoutTagsDb = prefs.getFloat("replaygain_preamp_without", 0f),
    )

    /** One bounded critical save. If the view is dismissed mid-commit, finish
     * the atomic update rather than leaving a preference ahead of the processor.
     */
    suspend fun save(edit: SettingsReplayGainEdit): SettingsReplayGainReceipt =
        withContext(NonCancellable) { transaction.save(edit) }
}
