package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class GenreMosaicExactIdentityTest {
    private val moduleDir: File = sequenceOf(File("."), File("app"))
        .map { it.absoluteFile }
        .first { File(it, "src/main/assets/ui7/mosaic/manifest.tsv").isFile }

    private fun realArtwork(): Map<String, MosaicArtworkAsset> {
        val mosaicDir = File(moduleDir, "src/main/assets/ui7/mosaic")
        val installed = mosaicDir.listFiles().orEmpty()
            .filter { it.extension == "webp" }
            .map { it.name }
            .toSet()
        val rejected = mutableListOf<String>()
        val artwork = mosaicApprovedArtwork(
            manifest = File(mosaicDir, "manifest.tsv").readText(),
            installed = installed,
            onRejected = { rejected += it },
        )
        assertEquals("rejected=$rejected", 665, artwork.size)
        return artwork
    }

    @Test
    fun exactLookupNeverBorrowsArtworkAcrossMeaningfulPunctuation() {
        val artwork = realArtwork()

        assertNull(mosaicArtworkForGenre("Hip/Hop", artwork))
        assertNull(mosaicArtworkForGenre("R B", artwork))
        assertNull(mosaicArtworkForGenre("Rock/Pop", artwork))
        // A style outside the packaged 665 keeps no crop at all.
        assertNull(mosaicArtworkForGenre("Barbershop", artwork))
        // Electronic and Jangle Pop now own their imported crop, and it is their own:
        // proximity to Electronica or to any pop style never hands them a neighbour's.
        assertEquals("UI7_MOSAICO_329_Electronic.webp",
            mosaicArtworkForGenre("Electronic", artwork)?.filename)
        assertEquals("UI7_MOSAICO_010_Electronica.webp",
            mosaicArtworkForGenre("Electronica", artwork)?.filename)
        assertEquals("UI7_MOSAICO_313_JanglePop.webp",
            mosaicArtworkForGenre("Jangle Pop", artwork)?.filename)

        assertEquals(
            "UI7_MOSAICO_009_HipHop.webp",
            mosaicArtworkForGenre("Hip-Hop", artwork)?.filename,
        )
        assertEquals(
            "UI7_MOSAICO_019_RnB.webp",
            mosaicArtworkForGenre("R&B", artwork)?.filename,
        )
        assertEquals(
            "UI7_MOSAICO_219_Darkwave.webp",
            mosaicArtworkForGenre("Dark Wave", artwork)?.filename,
        )
        assertEquals(
            "UI7_MOSAICO_054_PostRock.webp",
            mosaicArtworkForGenre("Post Rock", artwork)?.filename,
        )
        assertEquals(
            "UI7_MOSAICO_054_PostRock.webp",
            mosaicArtworkForGenre("Post-Rock", artwork)?.filename,
        )
        assertEquals(
            "UI7_MOSAICO_030_PopRock.webp",
            mosaicArtworkForGenre("Pop Rock", artwork)?.filename,
        )
        assertEquals(
            "UI7_MOSAICO_058_EDM.webp",
            mosaicArtworkForGenre("EDM", artwork)?.filename,
        )
        assertEquals(
            "UI7_MOSAICO_166_HiNRG.webp",
            mosaicArtworkForGenre("hi nrg", artwork)?.filename,
        )
        assertEquals(
            "UI7_MOSAICO_216_JRock.webp",
            mosaicArtworkForGenre("j rock", artwork)?.filename,
        )
    }
}
