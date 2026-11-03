package org.michimusic.link

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class LinkPackageImporterR162Test {
    @Test fun duplicateImportNeverClobbersEarlierContent() {
        val dir = Files.createTempDirectory("r162-import").toFile()
        try {
            val context = mockk<Context>()
            every { context.filesDir } returns dir
            val source = dir.resolve("source.flac").apply { writeText("NEW") }
            val target = dir.resolve("synced_music/song.flac").apply {
                parentFile!!.mkdirs()
                writeText("OLD")
            }
            assertNull(LinkPackageImporter(context).importTrack(source, "song", "flac"))
            assertEquals("OLD", target.readText())
            assertEquals("NEW", source.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun validImportCopiesBytesAndRejectsTraversal() {
        val dir = Files.createTempDirectory("r162-import").toFile()
        try {
            val context = mockk<Context>()
            every { context.filesDir } returns dir
            val source = dir.resolve("source.flac").apply { writeText("SAFE") }
            val importer = LinkPackageImporter(context)
            assertNull(importer.importTrack(source, "../escape", "flac"))
            val target = importer.importTrack(source, "song", "flac")
            assertNotNull(target)
            assertEquals("SAFE", target!!.readText())
            assertFalse(dir.resolve("escape.flac").exists())
        } finally { dir.deleteRecursively() }
    }
}
