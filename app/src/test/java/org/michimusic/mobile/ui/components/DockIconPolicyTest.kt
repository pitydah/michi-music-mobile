package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
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
}
