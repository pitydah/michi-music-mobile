package org.michimusic.player

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.michimusic.core.models.AudioProcessingMode

@RunWith(RobolectricTestRunner::class)
class HeadphoneProfilePolicyTest {
    private val dac = OutputDescriptor(17, "USB Audio", AudioDeviceInfo.TYPE_USB_DEVICE)
    private val phone = OutputDescriptor(3, "Phone Speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val profile = HeadphoneProfileState(enabled = true, selectedRouteId = 17, selectedObservedAtMs = 5L)

    @Test fun reenumeratedIdWithNewObservationMustNotReuseHeadphoneProfile() {
        assertFalse(headphonePeqEligible(profile,
            AudioRouteStatus.Confirmed(dac, 6L), AudioProcessingMode.DSP))
    }
    @Test fun selectedAndConfirmedUsb_isEligible() {
        assertTrue(headphonePeqEligible(profile, AudioRouteStatus.Confirmed(dac, 5L), AudioProcessingMode.DSP))
    }
    @Test fun detectedRequestedAndWrongRoute_mustNotApply() {
        assertFalse(headphonePeqEligible(profile, AudioRouteStatus.Requested(dac), AudioProcessingMode.DSP))
        assertFalse(headphonePeqEligible(profile, AudioRouteStatus.Confirmed(phone, 5L), AudioProcessingMode.DSP))
        assertFalse(headphonePeqEligible(profile, AudioRouteStatus.NoDevice, AudioProcessingMode.DSP))
    }
    @Test fun transparentModeDisablesProfileEvenOnCorrectDac() {
        assertFalse(headphonePeqEligible(profile, AudioRouteStatus.Confirmed(dac, 5L), AudioProcessingMode.TRANSPARENT))
    }
    @Test fun autoModePreservesLegacyUsbBypass() {
        assertFalse(headphonePeqEligible(profile, AudioRouteStatus.Confirmed(dac, 5L), AudioProcessingMode.AUTO))
    }

    @Test fun routeMustBeReconfirmedAfterRestart() {
        assertFalse(headphonePeqEligible(profile.copy(selectedRouteId = null), AudioRouteStatus.Confirmed(dac, 5L), AudioProcessingMode.DSP))
    }
    @Test fun sixBandPersistenceRoundTripDoesNotTruncate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("michi_headphone_profiles_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = HeadphoneProfileStore(context)
        store.editBand(5, PeqBand(11999f, -4.25f, 1.7f), persist = true)
        store.rename("Test headphones")
        store.saveNamedProfile()
        val reloaded = HeadphoneProfileStore(context)
        assertTrue(reloaded.state.value.bands.size == MICHI_PEQ_BAND_COUNT)
        assertTrue(reloaded.state.value.bands[5] == PeqBand(11999f, -4.25f, 1.7f))
        reloaded.selectPreset("Warm")
        reloaded.loadNamedProfile("Test headphones")
        assertTrue(reloaded.state.value.bands[5] == PeqBand(11999f, -4.25f, 1.7f))
    }

    @Test fun legacyThreeBandProfileNeverEnablesOrFakesMissingFilters() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("michi_headphone_profiles_v1", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("bands", "60.0,2.0,0.8;200.0,1.0,1.0;2000.0,-1.0,1.0")
            .putBoolean("enabled", true).commit()
        val store = HeadphoneProfileStore(context)
        assertTrue(store.state.value.legacyBandsTruncated)
        assertFalse(store.state.value.enabled)
        assertTrue(store.state.value.bands.size == 3)
        store.selectPreset("Neutral") // explicit user repair
        assertTrue(store.state.value.bands.size == 6)
        assertFalse(store.state.value.legacyBandsTruncated)
    }

    @Test fun enabledProfileCanAlwaysBeSwitchedOffEvenWithoutHeadphones() {
        assertTrue(
            headphoneSwitchEnabled(
                config = profile,
                mode = AudioProcessingMode.DSP,
                legacyBandsTruncated = false,
                hasHeadphone = false,
            ),
        )
    }

    @Test fun disabledProfileNeedsAHeadphoneAndDspModeToBeSwitchedOn() {
        val off = profile.copy(enabled = false)
        assertTrue(headphoneSwitchEnabled(off, AudioProcessingMode.DSP, legacyBandsTruncated = false, hasHeadphone = true))
        assertFalse(headphoneSwitchEnabled(off, AudioProcessingMode.DSP, legacyBandsTruncated = false, hasHeadphone = false))
        assertFalse(headphoneSwitchEnabled(off, AudioProcessingMode.TRANSPARENT, legacyBandsTruncated = false, hasHeadphone = true))
        assertFalse(headphoneSwitchEnabled(off, AudioProcessingMode.DSP, legacyBandsTruncated = true, hasHeadphone = true))
    }
}
