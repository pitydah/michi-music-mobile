package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K02: offline historical assertions must be source-bound and exact-key only. */
class MichiAtlasEditorialCatalogTest {
    @Test fun nineEditorialPagesHaveExactApprovedGenreKeys() {
        val keys = setOf("pop", "new wave", "synth-pop", "krautrock", "trip-hop",
            "ndw", "darkwave", "art rock", "son cubano")
        assertEquals(keys, MichiAtlasEditorialCatalog.curatedKeys())
        keys.forEach { key ->
            val page = requireNotNull(MichiAtlasEditorialCatalog.find(key))
            assertEquals(key, page.genreId)
            assertTrue(atlasPageIsValid(page))
            assertTrue(page.displayName.isNotBlank())
            assertTrue(atlasPageSources(page).isNotEmpty())
        }
        assertEquals("synth-pop", genreGroupingKey("Synth Pop"))
        assertEquals("trip-hop", genreGroupingKey("Trip Hop"))
        assertEquals("new wave", genreGroupingKey("New-Wave"))
        assertEquals("ndw", genreGroupingKey("Neue Deutsche Welle"))
        assertEquals("art rock", genreGroupingKey("Art-Rock"))
        assertEquals("son cubano", genreGroupingKey("Son Cubano"))
        assertEquals("darkwave", genreGroupingKey("Dark Wave"))
    }

    @Test fun strictIdentityNeverBorrowsNearbyButDifferentHistory() {
        assertNull(MichiAtlasEditorialCatalog.find("pop rock"))
        assertNull(MichiAtlasEditorialCatalog.find("k-pop"))
        assertNull(MichiAtlasEditorialCatalog.find("synth pop"))
        assertNull(MichiAtlasEditorialCatalog.find("Trip Hop"))
        assertNull(MichiAtlasEditorialCatalog.find("salsa"))
        assertNull(MichiAtlasEditorialCatalog.find("dark wave"))
        assertNull(MichiAtlasEditorialCatalog.find(""))
    }

    @Test fun everyClaimAndRelationHasAnHttpsSourceAndNoSelfEdges() {
        MichiAtlasEditorialCatalog.curatedKeys().forEach { id ->
            val page = requireNotNull(MichiAtlasEditorialCatalog.find(id))
            assertTrue(atlasPageSources(page).all(::atlasSourceIsSafe))
            assertEquals(page.relations, atlasAcceptedRelations(id, page.relations))
            assertTrue(page.relations.none { it.fromGenreId == it.toGenreId })
            assertTrue(page.relations.all { it.explanation.isNotBlank() && it.targetDisplayName.isNotBlank() })
        }
        assertTrue(requireNotNull(MichiAtlasEditorialCatalog.find("pop")).relations.isEmpty())
    }

    @Test fun sourceValidatorRejectsFakeLinksAndMalformedOrigins() {
        assertFalse(atlasSourceIsSafe(AtlasSource("Fuente", "http://example.org")))
        assertFalse(atlasSourceIsSafe(AtlasSource("Fuente", "https://")))
        assertFalse(atlasSourceIsSafe(AtlasSource("Fuente", "https://bad host.example")))
        assertFalse(atlasSourceIsSafe(AtlasSource("Fuente", "https://user:pass@example.org/")))
        assertFalse(atlasSourceIsSafe(AtlasSource("Fuente", "https://example.org/#fragment")))
        assertFalse(atlasSourceIsSafe(AtlasSource("", "https://example.org")))
        assertTrue(atlasSourceIsSafe(AtlasSource("Fuente", "https://example.org/research")))
    }

    @Test fun rejectedUnsourcedClaimInvalidatesThePageAtomically() {
        val original = requireNotNull(MichiAtlasEditorialCatalog.find("trip-hop"))
        val invalid = original.copy(
            milestones = original.milestones + AtlasMilestone(
                "1996", "Afirmación sin evidencia", "No se publica",
                AtlasSource("", "javascript:alert(1)"),
            ),
        )
        assertFalse(atlasPageIsValid(invalid))
        assertTrue(atlasPageIsValid(original))
    }

    @Test fun documentedNewPagesKeepProvenanceAndUnpublishedTargetsNonNavigable() {
        val ndw = requireNotNull(MichiAtlasEditorialCatalog.find("ndw"))
        assertEquals(2, ndw.milestones.size)
        assertTrue(ndw.scenes.single().place.contains("Düsseldorf"))
        assertTrue(ndw.relations.any { it.toGenreId == "punk" })
        assertNull(MichiAtlasEditorialCatalog.find("punk"))

        val dark = requireNotNull(MichiAtlasEditorialCatalog.find("darkwave"))
        assertEquals("new wave", dark.relations.single().toGenreId)
        val newWave = requireNotNull(MichiAtlasEditorialCatalog.find("new wave"))
        assertTrue(newWave.relations.any { it.toGenreId == "darkwave" })
        assertEquals("Son Cubano", requireNotNull(MichiAtlasEditorialCatalog.find("son cubano")).displayName)
        assertEquals("Art Rock", requireNotNull(MichiAtlasEditorialCatalog.find("art rock")).displayName)
    }

    @Test fun timelineAndGeographyAreExplicitNotDerivedFromLocalFiles() {
        val trip = requireNotNull(MichiAtlasEditorialCatalog.find("trip-hop"))
        assertTrue(trip.milestones.any { it.period == "1991" })
        assertTrue(trip.scenes.any { it.place.startsWith("Bristol") })
        val kraft = requireNotNull(MichiAtlasEditorialCatalog.find("krautrock"))
        assertTrue(kraft.scenes.any { it.place == "Alemania Occidental" })
        assertEquals(1, kraft.relations.count { it.kind == AtlasRelationKind.INFLUENCES })
    }
}
