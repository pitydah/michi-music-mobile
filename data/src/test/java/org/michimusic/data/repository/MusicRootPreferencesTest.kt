package org.michimusic.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class MusicRootPreferencesTest {
    private lateinit var context: Context
    private lateinit var preferences: MusicRootPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("michi_library", Context.MODE_PRIVATE)
            .edit().clear().commit()
        preferences = MusicRootPreferences(context)
    }

    @Test
    fun absentSelectionMeansTheDefaultMusicRoot() {
        val root = preferences.currentRoot()

        assertTrue(root.isDefault)
        assertEquals("Music/", root.label)
    }

    @Test
    fun customTreeRoundTripsWithDisplayName() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")

        assertTrue(preferences.setCustomRoot(uri, "  Mi música  "))
        val root = preferences.currentRoot()

        assertFalse(root.isDefault)
        assertEquals(uri.toString(), root.treeUri)
        assertEquals("Mi música", root.label)
    }

    @Test
    fun restoreDefaultRemovesTheCustomTree() {
        preferences.setCustomRoot(Uri.parse("content://tree/custom"), "Custom")

        assertTrue(preferences.restoreDefault())
        assertTrue(preferences.currentRoot().isDefault)
    }

    @Test
    fun legacyFolderAllowlistIsRemoved() {
        context.getSharedPreferences("michi_library", Context.MODE_PRIVATE)
            .edit().putString("included_folders_v1", "music\ndownload").commit()

        preferences.restoreDefault()

        assertFalse(
            context.getSharedPreferences("michi_library", Context.MODE_PRIVATE)
                .contains("included_folders_v1"),
        )
    }
}
