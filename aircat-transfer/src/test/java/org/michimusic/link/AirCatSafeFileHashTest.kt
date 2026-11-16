package org.michimusic.link

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirCatSafeFileHashTest {
    @Test fun exactOriginalBytesProduceExpectedDigest() {
        val dir = Files.createTempDirectory("aircat-hash").toFile()
        try {
            val file = dir.resolve("original.flac")
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AirCatSafeFileHash.sha256(file))
        } finally { dir.deleteRecursively() }
    }

    @Test fun symbolicLinkCannotBeReadAsTrustedOriginal() {
        val dir = Files.createTempDirectory("aircat-hash").toFile()
        try {
            val original = dir.resolve("real.flac")
            original.writeText("abc")
            val alias = dir.resolve("alias.flac")
            Files.createSymbolicLink(alias.toPath(), original.toPath())
            assertEquals("", AirCatSafeFileHash.sha256(alias))
        } finally { dir.deleteRecursively() }
    }

    @Test fun danglingSymbolicLinkDoesNotFollowTarget() {
        val dir = Files.createTempDirectory("aircat-hash").toFile()
        try {
            val alias = dir.resolve("alias.flac")
            Files.createSymbolicLink(alias.toPath(), dir.resolve("nope.flac").toPath())
            assertEquals("", AirCatSafeFileHash.sha256(alias))
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingFileNeverHasTrustedSha() {
        val dir = Files.createTempDirectory("aircat-hash").toFile()
        try { assertTrue(AirCatSafeFileHash.sha256(dir.resolve("missing")).isEmpty()) }
        finally { dir.deleteRecursively() }
    }
}
