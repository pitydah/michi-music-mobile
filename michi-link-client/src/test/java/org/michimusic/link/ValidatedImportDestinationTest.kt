package org.michimusic.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ValidatedImportDestinationTest {
    @Test fun preventsPathTraversalAndUnsupportedFormats() {
        val dir = File(System.getProperty("java.io.tmpdir"), "michi-import-r16")
        assertNull(validatedImportDestination(dir, "../config", "mp3"))
        assertNull(validatedImportDestination(dir, "a/b", "flac"))
        assertNull(validatedImportDestination(dir, "foo", "../../json"))
        assertNull(validatedImportDestination(dir, "", "mp3"))
    }

    @Test fun validInputRemainsInManagedDirectory() {
        val dir = File(System.getProperty("java.io.tmpdir"), "michi-import-r16")
        val path = validatedImportDestination(dir, "song_17", "FLAC")!!
        assertEquals(dir.canonicalFile, path.canonicalFile.parentFile)
        assertEquals("song_17.flac", path.name)
    }
}
