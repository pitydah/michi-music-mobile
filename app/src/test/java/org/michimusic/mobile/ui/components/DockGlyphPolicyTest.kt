package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The dock draws hand-built glyphs for the four core routes and falls back to
 * the NavTabItem ImageVectors for any other route. That mapping used to live
 * inline in the render pass, where it could drift from the tab list without any
 * test noticing.
 */
class DockGlyphPolicyTest {

    @Test
    fun `core routes map to their own glyph`() {
        assertEquals(DockGlyph.Home, dockGlyphFor("home"))
        assertEquals(DockGlyph.Library, dockGlyphFor("library"))
        assertEquals(DockGlyph.Search, dockGlyphFor("search"))
        assertEquals(DockGlyph.Devices, dockGlyphFor("devices"))
    }

    @Test
    fun `unknown routes fall back to the icon vectors instead of a glyph`() {
        assertNull(dockGlyphFor("playlists"))
        assertNull(dockGlyphFor("settings"))
        assertNull(dockGlyphFor(""))
    }

    @Test
    fun `every glyph is claimed by exactly one route`() {
        val claimed = defaultNavTabs.mapNotNull { dockGlyphFor(it.route) }
        assertEquals(claimed.size, claimed.distinct().size)
        assertEquals(DockGlyph.entries.size, claimed.size)
    }

    @Test
    fun `declared tabs keep their gesture-visible labels`() {
        // Auto-sizing must not be "fixed" by shortening the copy: the labels are
        // the accessibility contract asserted by the dock navigation test.
        assertEquals(
            listOf("Inicio", "Biblioteca", "Buscar", "Dispositivos"),
            defaultNavTabs.map { it.label },
        )
    }
}
