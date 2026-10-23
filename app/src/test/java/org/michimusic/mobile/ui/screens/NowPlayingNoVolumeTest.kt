package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NowPlayingNoVolumeTest {

    private fun findFile(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        val sub = File("../$path")
        if (sub.exists()) return sub
        return File(path.removePrefix("app/"))
    }

    @Test
    fun testNowPlayingDoesNotContainVolumeSlider() {
        val file = findFile("app/src/main/java/org/michimusic/mobile/ui/screens/NowPlayingScreen.kt")
        assertTrue("NowPlayingScreen.kt debe existir en ${file.absolutePath}", file.exists())
        val content = file.readText()

        assertFalse(
            "NowPlayingScreen no debe incluir sliders de volumen (D-05)",
            content.contains("MichiFineSlider") || content.contains("VolumeSlider") || content.contains("effectiveVolume")
        )
    }

    @Test
    fun testRemoteScreenDoesNotContainVolumeSlider() {
        val file = findFile("app/src/main/java/org/michimusic/mobile/ui/screens/RemoteScreen.kt")
        assertTrue("RemoteScreen.kt debe existir en ${file.absolutePath}", file.exists())
        val content = file.readText()

        assertFalse(
            "RemoteScreen no debe incluir slider de volumen (D-05)",
            content.contains("MichiFineSlider") || content.contains("VolumeSlider")
        )
    }
}
