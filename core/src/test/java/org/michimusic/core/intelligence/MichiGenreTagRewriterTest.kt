package org.michimusic.core.intelligence

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test

class MichiGenreTagRewriterTest {
    private val writer = MichiGenreTagRewriter
    private fun le(n: Int) = byteArrayOf(n.toByte(),(n ushr 8).toByte(),(n ushr 16).toByte(),(n ushr 24).toByte())
    private fun block(type: Int, data: ByteArray, last: Boolean = false): ByteArray =
        byteArrayOf((type or (if(last)128 else 0)).toByte(),(data.size ushr 16).toByte(),
            (data.size ushr 8).toByte(),data.size.toByte())+data
    private fun comment(vendor: String, vararg values: String): ByteArray {
        val out = ByteArrayOutputStream()
        val v = vendor.toByteArray();out.write(le(v.size));out.write(v);out.write(le(values.size))
        for (s in values) { val e=s.toByteArray();out.write(le(e.size));out.write(e) }
        return out.toByteArray()
    }
    private val audio = "FRAME-STREAM-UNTOUCHED".toByteArray()
    private fun flac() = "fLaC".toByteArray() +
        block(0, ByteArray(34)) + block(4,comment("michi", "TITLE=Song", "ARTIST=Cat", "GENRE=Jazz")) +
        block(6,"COVER-PHOTO".toByteArray(),true) + audio
    private fun sync(n: Int) = byteArrayOf((n ushr 21).toByte(), ((n ushr 14) and 127).toByte(),
        ((n ushr 7) and 127).toByte(), (n and 127).toByte())
    private fun frame(name: String, data: ByteArray) = name.toByteArray() + sync(data.size) +
        byteArrayOf(0,0) + data
    private fun mp3() = run {
        val frames = frame("TIT2", byteArrayOf(3)+"Song".toByteArray()) +
            frame("TCON", byteArrayOf(3)+"Jazz".toByteArray()) +
            frame("APIC", "RAW-IMAGE-UNCHANGED".toByteArray())
        "ID3".toByteArray()+byteArrayOf(4,0,0)+sync(frames.size)+frames+audio
    }
    @Test fun flacRewritesOnlyGenreAndPreservesCoverAndAudio() {
        val original=File.createTempFile("mi23-src-",".flac")
        val updated=File(original.path+".staging")
        try {
            original.writeBytes(flac())
            val before=original.readBytes()
            val result=writer.rewrite(original,updated,listOf("Progressive Rock", "Art Rock"))
            assertEquals("FLAC",result.format)
            assertEquals(listOf("Jazz"),result.oldGenres)
            assertArrayEquals(before, original.readBytes())
            val contents=updated.readBytes()
            assertTrue(contents.toString(StandardCharsets.UTF_8).contains("TITLE=Song"))
            assertTrue(contents.toString(StandardCharsets.UTF_8).contains("ARTIST=Cat"))
            assertTrue(contents.toString(StandardCharsets.UTF_8).contains("GENRE=Art Rock"))
            assertTrue(contents.toString(StandardCharsets.UTF_8).contains("COVER-PHOTO"))
            assertArrayEquals(audio, contents.takeLast(audio.size).toByteArray())
        } finally {original.delete();updated.delete()}
    }
    @Test fun mp3V24PreservesOtherFramesAndAudio() {
        val original=File.createTempFile("mi23-src-",".mp3")
        val updated=File(original.path+".staging")
        try {
            original.writeBytes(mp3())
            val result=writer.rewrite(original,updated,listOf("Rock"))
            assertEquals("MP3-ID3v2.4",result.format)
            val contents=updated.readBytes()
            assertTrue(contents.toString(StandardCharsets.ISO_8859_1).contains("RAW-IMAGE-UNCHANGED"))
            assertTrue(contents.toString(StandardCharsets.ISO_8859_1).contains("TIT2"))
            assertArrayEquals(audio, contents.takeLast(audio.size).toByteArray())
            assertEquals(1, original.readBytes().toString(StandardCharsets.ISO_8859_1).split("TCON").size-1)
        } finally {original.delete();updated.delete()}
    }
    @Test fun noInplaceWritesAndNoUnsupportedVersionMutation() {
        val original=File.createTempFile("mi23-src-",".mp3")
        try {
            original.writeBytes(mp3())
            try {writer.rewrite(original,original,listOf("Rock"));fail("self writing forbidden")}
            catch (_: MichiGenreTagRewriter.UnsupportedFormat) {}
            assertArrayEquals(mp3(),original.readBytes())
            val v3 = mp3().clone();v3[3]=3
            original.writeBytes(v3)
            val target=File(original.path+".target")
            try {writer.rewrite(original,target,listOf("Rock", "Pop"));fail("must refuse")}
            catch (_: MichiGenreTagRewriter.UnsupportedFormat) {}
            assertFalse(target.exists())
        } finally {original.delete()}
    }
    @Test fun invalidGenreNeverCreatesStaging() {
        val original=File.createTempFile("mi23-src-",".flac")
        val target=File(original.path+".staging")
        try {
            original.writeBytes(flac())
            try {writer.rewrite(original,target,listOf("Rock\u0000Pop"));fail("invalid")}
            catch (_: MichiGenreTagRewriter.UnsupportedFormat) {}
            assertFalse(target.exists())
        } finally {original.delete()}
    }
}
