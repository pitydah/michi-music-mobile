package org.michimusic.data.intelligence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.intelligence.MichiSharingConsentPolicy.PrivacyMode
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MichiIntelligencePrivacyPreferencesTest {
    private lateinit var context: Context
    private val file: File get() = File(context.noBackupFilesDir,
        MichiIntelligencePrivacyPreferences.FILE_NAME)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        file.delete()
        File(file.path + ".bak").delete()
    }

    @After fun cleanup() {
        file.delete()
        File(file.path + ".bak").delete()
    }

    @Test fun maximumIsDefaultAndCreatesNoFile() = runBlocking {
        assertEquals(PrivacyMode.MAXIMUM, MichiIntelligencePrivacyPreferences(context).readMode())
        assertFalse(file.exists())
    }

    @Test fun limitedAndCustomSurviveNewInstancesWithoutBackup() = runBlocking {
        val prefs = MichiIntelligencePrivacyPreferences(context)
        prefs.setMode(PrivacyMode.LIMITED_SHARING)
        assertTrue(file.exists())
        assertEquals(context.noBackupFilesDir.canonicalPath, file.parentFile!!.canonicalPath)
        assertEquals(PrivacyMode.LIMITED_SHARING, MichiIntelligencePrivacyPreferences(context).readMode())
        prefs.setMode(PrivacyMode.CUSTOM_SHARING)
        assertEquals(PrivacyMode.CUSTOM_SHARING, MichiIntelligencePrivacyPreferences(context).readMode())
    }

    @Test fun returnToMaximumRemovesStoredMode() = runBlocking {
        val prefs = MichiIntelligencePrivacyPreferences(context)
        prefs.setMode(PrivacyMode.CUSTOM_SHARING)
        prefs.setMode(PrivacyMode.MAXIMUM)
        assertEquals(PrivacyMode.MAXIMUM, prefs.readMode())
        assertFalse(file.exists())
    }

    @Test fun malformedModeFailsClosed() = runBlocking {
        file.writeText("UNKNOWN_MODE")
        assertEquals(PrivacyMode.MAXIMUM, MichiIntelligencePrivacyPreferences(context).readMode())
    }
}
