package org.michimusic.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class MusicFolderPreferencesTest {
    private lateinit var context: Context
    private lateinit var preferences: MusicFolderPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("michi_library", Context.MODE_PRIVATE)
            .edit().clear().commit()
        preferences = MusicFolderPreferences(context)
    }

    @Test
    fun unsetSelectionMeansSmartDefault() {
        assertNull(preferences.includedFolders())
    }

    @Test
    fun explicitFoldersArePersistedNormalized() {
        preferences.setIncludedFolders(setOf(" Music ", "Download", "MUSIC"))

        assertEquals(setOf("music", "download"), preferences.includedFolders())
    }

    @Test
    fun wildcardRoundTrips() {
        preferences.setIncludedFolders(setOf(ALL_FOLDERS))

        assertEquals(setOf(ALL_FOLDERS), preferences.includedFolders())
    }

    @Test
    fun clearingRestoresSmartDefault() {
        preferences.setIncludedFolders(setOf("music"))
        preferences.clearSelection()

        assertNull(preferences.includedFolders())
    }
}
