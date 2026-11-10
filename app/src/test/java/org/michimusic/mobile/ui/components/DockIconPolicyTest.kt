package org.michimusic.mobile.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dock ships five Phosphor vectors. This test pins the public wiring that a
 * redesign can silently break: which resource each route shows, that no two
 * destinations share a vector, and that the labels the accessible names depend
 * on stay untouched.
 */
class DockIconPolicyTest {

    @Test
    fun `every dock route resolves to its own vector`() {
        val byRoute = defaultNavTabs.associate { it.route to it.iconRes }
        assertEquals(setOf("home", "library", "playlist", "search", "devices"), byRoute.keys)
        assertEquals(5, byRoute.values.toSet().size)
        assertTrue(byRoute.values.all { it != 0 })
    }

    @Test
    fun `declared tabs keep their gesture-visible labels`() {
        assertEquals(
            listOf("Inicio", "Biblioteca", "Playlists", "Buscar", "Dispositivos"),
            defaultNavTabs.map { it.label },
        )
    }

    @Test
    fun `the dock order matches the declared navigation order`() {
        assertEquals(
            listOf("home", "library", "playlist", "search", "devices"),
            defaultNavTabs.map { it.route },
        )
    }

    @Test
    fun `dock tabs keep their chrome without the removed top rule`() {
        val file = findFile(
            "app/src/main/java/org/michimusic/mobile/ui/components/BottomNavBar.kt",
        )
        assertTrue("BottomNavBar.kt must exist at ${file.absolutePath}", file.exists())
        val source = file.readText()
        assertFalse(
            "DockTab must not reintroduce the 24x2dp decorative accent line",
            source.contains("Modifier.size(width = 24.dp, height = 2.dp)"),
        )
        assertTrue("Role.Tab semantics must stay declared", source.contains("role = Role.Tab"))
        assertTrue(
            "Selected background must stay declared",
            source.contains("aeroDockPanelOpacity(emphasis, highContrast)"),
        )
        assertTrue(
            "Selected border must stay declared",
            source.contains("aeroDockBorderOpacity(emphasis, highContrast)"),
        )
    }

    private fun findFile(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        val sub = File("../$path")
        if (sub.exists()) return sub
        return File(path.removePrefix("app/"))
    }
}
