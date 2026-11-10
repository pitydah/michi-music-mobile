package org.michimusic.mobile.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HDR-01 / HEADER-06 ownership guard: the Scaffold must never consume the top
 * inset while the five destinations consume it themselves. The background then
 * reaches physical y=0 without a second black roof.
 */
class HeaderInsetOwnershipTest {
    private val moduleDir: File = File(System.getProperty("user.dir") ?: ".").let { cwd ->
        if (File(cwd, "src/main/java").isDirectory) cwd
        else File(cwd, "app").takeIf { File(it, "src/main/java").isDirectory } ?: cwd
    }

    private fun source(path: String): String = File(moduleDir, path).readText()

    @Test fun scaffoldOwnsOnlyBottomAndHorizontalInsets() {
        val nav = source("src/main/java/org/michimusic/mobile/navigation/MichiNavHost.kt")
        assertTrue(nav.contains("contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only("))
        assertTrue(nav.contains("WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom"))
        assertFalse("Top inset must remain owned by each screen", nav.contains("WindowInsetsSides.Top"))
    }

    @Test fun fiveDestinationsOwnTheStatusBarInsetAndDrawTheirBackdrop() {
        val screens = listOf(
            "src/main/java/org/michimusic/mobile/ui/screens/HomeScreen.kt",
            "src/main/java/org/michimusic/mobile/ui/screens/AlbumsScreen.kt",
            "src/main/java/org/michimusic/mobile/ui/screens/PlaylistScreen.kt",
            "src/main/java/org/michimusic/mobile/ui/screens/SearchScreen.kt",
            "src/main/java/org/michimusic/mobile/ui/screens/DevicesScreen.kt",
        )
        for (screen in screens) {
            val text = source(screen)
            assertTrue("$screen must own the status-bar inset", text.contains(".statusBarsPadding()"))
            assertTrue("$screen must draw an Aero backdrop from y=0", text.contains("AeroPremiumBackdrop("))
        }
    }
}
