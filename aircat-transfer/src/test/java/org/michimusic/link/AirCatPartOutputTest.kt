package org.michimusic.link

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AirCatPartOutputTest {
    @Test fun newPartWritesOriginalBytes() {
        val dir = Files.createTempDirectory("aircat-part")
        val part = dir.resolve("track.part").toFile()
        AirCatPartOutput.open(part, append = false, expectedOffset = 0L).use { it.write(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), part.readBytes())
    }

    @Test fun resumeRequiresExactPersistedLength() {
        val dir = Files.createTempDirectory("aircat-part")
        val part = dir.resolve("track.part").toFile().also { it.writeBytes(byteArrayOf(1, 2)) }
        assertThrows(IOException::class.java) { AirCatPartOutput.open(part, true, 1L) }
        assertArrayEquals(byteArrayOf(1, 2), part.readBytes())
    }

    @Test fun resumeAppendsWithoutTruncatingVerifiedPrefix() {
        val dir = Files.createTempDirectory("aircat-part")
        val part = dir.resolve("track.part").toFile().also { it.writeBytes(byteArrayOf(1, 2)) }
        AirCatPartOutput.open(part, true, 2L).use { it.write(byteArrayOf(3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), part.readBytes())
    }

    @Test fun resetTruncatesOnlyAnExistingRegularPart() {
        val dir = Files.createTempDirectory("aircat-part")
        val part = dir.resolve("track.part").toFile().also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        AirCatPartOutput.open(part, false, 0L).use { it.write(byteArrayOf(7)) }
        assertArrayEquals(byteArrayOf(7), part.readBytes())
    }

    @Test fun symlinkCannotOverwriteItsTarget() {
        val dir = Files.createTempDirectory("aircat-part")
        val target = dir.resolve("private-data").toFile().also { it.writeText("untouched") }
        val part = dir.resolve("alias.part")
        Files.createSymbolicLink(part, target.toPath())
        assertThrows(IOException::class.java) { AirCatPartOutput.open(part.toFile(), false, 0L) }
        assertEquals("untouched", target.readText())
    }

    @Test fun danglingSymlinkIsOccupiedAndCannotBeOverwritten() {
        val dir = Files.createTempDirectory("aircat-part")
        val part = dir.resolve("alias.part")
        Files.createSymbolicLink(part, dir.resolve("not-existing"))
        assertThrows(IOException::class.java) { AirCatPartOutput.open(part.toFile(), false, 0L) }
        assertFalse(Files.exists(dir.resolve("not-existing")))
    }

    @Test fun missingPartCannotBeResumed() {
        val dir = Files.createTempDirectory("aircat-part")
        assertThrows(IOException::class.java) {
            AirCatPartOutput.open(dir.resolve("missing.part").toFile(), true, 2L)
        }
    }

    @Test fun parentSymlinkIsRefused() {
        val root = Files.createTempDirectory("aircat-part")
        val actual = Files.createDirectory(root.resolve("actual"))
        val alias = root.resolve("alias")
        Files.createSymbolicLink(alias, actual)
        assertThrows(IOException::class.java) {
            AirCatPartOutput.open(alias.resolve("track.part").toFile(), false, 0L)
        }
    }
}
