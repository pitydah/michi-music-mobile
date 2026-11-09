package org.michimusic.mobile.playback

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.michimusic.player.ReplayGainMode
import org.michimusic.core.models.AudioProcessingMode

class SignalTruthLocalAuthorityLeaseTest {
    private val local = SignalTruthLocalAuthorityLease("local_phone", true, 8)

    @Test fun sameOwnerAndRevisionMayControlAudio() {
        assertTrue(SignalTruthLocalAuthorityPolicy.unchanged(local, local.copy()))
    }
    @Test fun remoteOrDifferentEndpointIsNotPhoneAuthority() {
        assertFalse(SignalTruthLocalAuthorityPolicy.isPhone(local.copy(endpointId = "receiver")))
        assertFalse(SignalTruthLocalAuthorityPolicy.isPhone(local.copy(isLocal = false)))
        assertFalse(SignalTruthLocalAuthorityPolicy.isPhone(null))
    }
    @Test fun returnToLocalWithNewRevisionIsNotTheSameLease() {
        assertFalse(SignalTruthLocalAuthorityPolicy.unchanged(local, local.copy(revision = 10)))
    }
    @Test fun negativeEpochNeverAuthorizesACommand() {
        assertFalse(SignalTruthLocalAuthorityPolicy.isPhone(local.copy(revision = -1)))
    }
    @Test fun replayGainOwnerHandoffDuringPreferenceReadDoesNotMutateEngineOrStore() {
        var live = local
        var configured = 0
        var saves = 0
        val store = object : ReplayGainQuickStore {
            override fun withPreampDb(): Float { live = local.copy(endpointId = "receiver", isLocal = false, revision = 9); return 0f }
            override fun withoutPreampDb() = 0f
            override fun saveMode(mode: ReplayGainMode) { saves++ }
        }
        val engine = object : ReplayGainQuickEngine {
            override fun currentMode() = ReplayGainMode.OFF
            override fun configure(mode: ReplayGainMode, withPreampDb: Float, withoutPreampDb: Float): Boolean { configured++; return true }
        }
        val result = SignalTruthReplayGainCoordinator(engine,store,authorityLease={live},isLocalOutputAuthority={true}).choose(SignalDestination.LOCAL, ReplayGainMode.TRACK)
        assertEquals(ReplayGainQuickStatus.OUTPUT_CHANGED, result.status)
        assertEquals(0, configured)
        assertEquals(0, saves)
    }
    @Test fun replayGainHandoffDuringConfigureRefusesPreferenceCommit() {
        var live=local;var changedMode=ReplayGainMode.OFF;var saves=0
        val engine=object:ReplayGainQuickEngine{
            override fun currentMode()=changedMode
            override fun configure(mode:ReplayGainMode,withPreampDb:Float,withoutPreampDb:Float):Boolean {
                changedMode=mode
                live=local.copy(revision=10)
                return true
            }
        }
        val store=object:ReplayGainQuickStore{
            override fun withPreampDb()=0f
            override fun withoutPreampDb()=0f
            override fun saveMode(mode:ReplayGainMode){saves++}
        }
        val result=SignalTruthReplayGainCoordinator(engine,store,authorityLease={live},isLocalOutputAuthority={true}).choose(SignalDestination.LOCAL,ReplayGainMode.TRACK)
        assertEquals(ReplayGainQuickStatus.OUTPUT_CHANGED,result.status)
        assertEquals(0,saves)
    }
    @Test fun dspHandoffDuringManagerSetModeDoesNotConfirmNewRoute() {
        var live=local;var changedMode=AudioProcessingMode.DSP
        val engine=object:AudioModeQuickEngine{
            override fun currentMode()=changedMode
            override fun configure(mode:AudioProcessingMode):Boolean {changedMode=mode;live=local.copy(revision=11);return true}
        }
        val result=SignalTruthAudioModeCoordinator(engine,authorityLease={live},hasLocalAuthority={true}).choose(SignalDestination.LOCAL,AudioProcessingMode.AUTO)
        assertEquals(AudioModeQuickStatus.OUTPUT_CHANGED,result.status)
    }
    @Test fun headphoneHandoffDuringPresetReadBackIsNotConfirmed() {
        var live=local
        var preset="Neutral"
        val engine=object:HeadphoneQuickEngine{
            override fun snapshot():HeadphoneQuickSnapshot? {
                if(preset=="Warm") live=local.copy(revision=9)
                return HeadphoneQuickSnapshot(preset,false,false,6)
            }
            override fun selectPreset(p:String):Boolean {preset=p;return true}
            override fun disableProfile()=true
        }
        val result=SignalTruthHeadphoneCoordinator(engine,authorityLease={live},hasLocalAuthority={true}).choosePreset(SignalDestination.LOCAL,"Warm")
        assertEquals(HeadphoneQuickStatus.OUTPUT_CHANGED,result.status)
    }
    @Test fun headphoneDisableRefusesChangedOutputEvenWhenStoreSucceeded() {
        var live=local
        var enabled=true
        val engine=object:HeadphoneQuickEngine{
            override fun snapshot()=HeadphoneQuickSnapshot("Warm",enabled,false,6)
            override fun selectPreset(preset:String)=true
            override fun disableProfile():Boolean {enabled=false;live=local.copy(endpointId="receiver",isLocal=false,revision=9);return true}
        }
        val result=SignalTruthHeadphoneCoordinator(engine,authorityLease={live},hasLocalAuthority={true}).disable(SignalDestination.LOCAL)
        assertEquals(HeadphoneQuickStatus.OUTPUT_CHANGED,result.status)
    }
    @Test fun replayGainStoreHandoffNeverReportsConfigured() {
        var live = local
        var changedMode = ReplayGainMode.TRACK
        val engine=object: ReplayGainQuickEngine {
            override fun currentMode()=changedMode
            override fun configure(mode:ReplayGainMode,withPreampDb:Float,withoutPreampDb:Float):Boolean {changedMode=mode;return true}
        }
        val store=object:ReplayGainQuickStore {
            override fun withPreampDb()=0f
            override fun withoutPreampDb()=0f
            override fun saveMode(mode:ReplayGainMode) {live=local.copy(revision=9)}
        }
        val result=SignalTruthReplayGainCoordinator(engine,store,authorityLease={live},isLocalOutputAuthority={true})
            .choose(SignalDestination.LOCAL,ReplayGainMode.TRACK)
        assertEquals(ReplayGainQuickStatus.OUTPUT_CHANGED,result.status)
    }

    @Test fun headphoneDisableReadbackHandoffNeverReportsOldOwner() {
        var live=local
        var enabled=true
        val engine=object: HeadphoneQuickEngine {
            override fun snapshot():HeadphoneQuickSnapshot {
                if (!enabled) live=local.copy(revision=9)
                return HeadphoneQuickSnapshot("Warm",enabled,false,6)
            }
            override fun selectPreset(preset:String)=true
            override fun disableProfile():Boolean {enabled=false;return true}
        }
        val result=SignalTruthHeadphoneCoordinator(engine,authorityLease={live},hasLocalAuthority={true})
            .disable(SignalDestination.LOCAL)
        assertEquals(HeadphoneQuickStatus.OUTPUT_CHANGED,result.status)
    }

}
