package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreMosaicArtworkPolicyTest {
    private val header = mosaicManifestHeader + "\n"
    private val rock = "UI7_MOSAICO_001_Rock.webp"
    private val rockRoll = "UI7_MOSAICO_081_RockRoll.webp"
    private val jRock = "UI7_MOSAICO_216_JRock.webp"

    private fun row(
        key: String = "rock",
        filename: String = rock,
        sha: String = "a".repeat(64),
        width: Int = 506,
        height: Int = 352,
        approval: String = mosaicApprovalState,
    ): String = listOf(key, filename, sha, width.toString(), height.toString(), approval)
        .joinToString("\t")

    private fun manifest(vararg rows: String): String = header + rows.joinToString("\n") + "\n"

    @Test fun derivedKeysFollowTheApprovedFilenameSuffix() {
        val expected = mapOf(
            rock to "rock",
            "UI7_MOSAICO_003_SynthPop.webp" to "synth-pop",
            "UI7_MOSAICO_009_HipHop.webp" to "hip-hop",
            "UI7_MOSAICO_019_RnB.webp" to "r&b",
            "UI7_MOSAICO_029_LoFi.webp" to "lo-fi",
            "UI7_MOSAICO_034_DrumBass.webp" to "drum bass",
            "UI7_MOSAICO_043_KPop.webp" to "k-pop",
            "UI7_MOSAICO_044_JPop.webp" to "j-pop",
            "UI7_MOSAICO_058_EDM.webp" to "edm",
            rockRoll to "rock roll",
            "UI7_MOSAICO_131_UKGarage.webp" to "uk garage",
            "UI7_MOSAICO_160_YeYe.webp" to "ye ye",
            "UI7_MOSAICO_166_HiNRG.webp" to "hi nrg",
            "UI7_MOSAICO_170_GFunk.webp" to "g funk",
            "UI7_MOSAICO_184_MPB.webp" to "mpb",
            "UI7_MOSAICO_188_LiquidDrumBass.webp" to "liquid drum bass",
            "UI7_MOSAICO_189_IDM.webp" to "idm",
            "UI7_MOSAICO_215_MusicaDeCamara.webp" to "musica de camara",
            jRock to "j rock",
        )
        expected.forEach { (filename, key) ->
            assertEquals(filename, key, mosaicGenreKeyFromFilename(filename))
        }
    }

    @Test fun unknownNamesNeverInventAGenre() {
        listOf(
            "UI7_MOSAICO_1_Rock.webp",
            "UI7_MOSAICO_001_.webp",
            "UI7_MOSAICO_001_Rock.png",
            "Rock.webp",
            "UI7_MOSAICO_001_Rock copy.webp",
            "UI7_MOSAICO_001_Rock/Roll.webp",
        ).forEach { assertNull(it, mosaicGenreKeyFromFilename(it)) }
    }

    @Test fun installedRowsResolveByScopedGenreId() {
        val names = setOf(rock, jRock)
        val artwork = mosaicApprovedArtwork(manifest(row(), row(key = "j rock", filename = jRock)), names)
        assertEquals(2, artwork.size)
        assertEquals(rock, mosaicArtworkForGenre("rock", artwork)?.filename)
        // Case/punctuation tolerance only; the genre must still be the same one.
        assertEquals(jRock, mosaicArtworkForGenre("j-rock", artwork)?.filename)
        assertNull(mosaicArtworkForGenre("rock/pop", artwork))
        assertNull(mosaicArtworkForGenre("", artwork))
        assertNull(mosaicArtworkForGenre("rock", emptyMap()))
    }

    @Test fun closelyRelatedGenresKeepDifferentArtwork() {
        val names = setOf(rock, rockRoll)
        val artwork = mosaicApprovedArtwork(
            manifest(row(), row(key = "rock roll", filename = rockRoll)), names,
        )
        assertNotEquals(
            mosaicArtworkForGenre("rock", artwork)?.filename,
            mosaicArtworkForGenre("rock roll", artwork)?.filename,
        )
    }

    @Test fun incompleteOrUnsafeCatalogFailsClosed() {
        val one = manifest(row())
        assertTrue("unpackaged asset", mosaicApprovedArtwork(one, emptySet()).isEmpty())
        assertTrue("stray webp", mosaicApprovedArtwork(one, setOf(rock, rockRoll)).isEmpty())
        assertTrue("unapproved state",
            mosaicApprovedArtwork(manifest(row(approval = "DRAFT")), setOf(rock)).isEmpty())
        assertTrue("malformed hash",
            mosaicApprovedArtwork(manifest(row(sha = "bad")), setOf(rock)).isEmpty())
        assertTrue("missing header",
            mosaicApprovedArtwork(row(), setOf(rock)).isEmpty())
        assertTrue("wrong version",
            mosaicApprovedArtwork(header.replace("V1", "V2") + row(), setOf(rock)).isEmpty())
        assertTrue("broken row",
            mosaicApprovedArtwork(one + "broken row\n", setOf(rock)).isEmpty())
    }

    @Test fun keyMustBeSpelledByItsOwnFilename() {
        val mismatched = manifest(row(key = "jazz", filename = rock))
        assertTrue(mosaicApprovedArtwork(mismatched, setOf(rock)).isEmpty())
    }

    @Test fun duplicateFilesOrGenreIndexesFailClosed() {
        assertTrue("duplicate file",
            mosaicApprovedArtwork(manifest(row(), row()), setOf(rock)).isEmpty())
        // "NewWave" and "Newwave" are the same approved genre: never two assets.
        val duplicateIndex = manifest(
            row(key = "new wave", filename = "UI7_MOSAICO_017_NewWave.webp"),
            row(key = "new wave", filename = "UI7_MOSAICO_999_Newwave.webp"),
        )
        assertTrue(mosaicApprovedArtwork(
            duplicateIndex,
            setOf("UI7_MOSAICO_017_NewWave.webp", "UI7_MOSAICO_999_Newwave.webp"),
        ).isEmpty())
    }

    @Test fun declaredPixelGeometryIsVerified() {
        assertTrue(mosaicArtworkGeometryValid(506, 352))
        assertTrue(mosaicArtworkGeometryValid(500, 378))
        assertFalse(mosaicArtworkGeometryValid(100, 352))
        assertFalse(mosaicArtworkGeometryValid(506, 40))
        assertFalse(mosaicArtworkGeometryValid(506, 1000))
        assertFalse(mosaicArtworkGeometryValid(4096, 4096))
        assertTrue(mosaicApprovedArtwork(
            manifest(row(width = 506, height = 1000)), setOf(rock),
        ).isEmpty())
    }
}
