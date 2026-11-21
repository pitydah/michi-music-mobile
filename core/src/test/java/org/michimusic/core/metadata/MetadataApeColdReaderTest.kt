package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataApeColdReaderTest {
    private fun ascii(s: String)=s.toByteArray(Charsets.US_ASCII)
    private fun le(n: Int)=byteArrayOf(n.toByte(),(n ushr 8).toByte(),
        (n ushr 16).toByte(),(n ushr 24).toByte())
    private fun item(key: String, value: ByteArray, flags: Int=0) =
        le(value.size) + le(flags) + ascii(key) + byteArrayOf(0) + value
    private fun build(vararg items: ByteArray, tagVersion: Int=2000): ByteArray {
        val entries=items.fold(ByteArray(0)) { a,b -> a+b }
        val footer=ascii("APETAGEX") + le(tagVersion) + le(entries.size+32) + le(items.size) +
            le(0) + ByteArray(8)
        return ascii("wvpk") + ByteArray(20) + entries + footer
    }
    private fun read(bytes: ByteArray): MetadataFieldReadReport {
        val file=File.createTempFile("michi-ape-read", ".wv")
        try {
            file.writeBytes(bytes)
            return RandomAccessFile(file,"r").use {MetadataApeColdReader.observe(it.channel)}
        } finally {file.delete()}
    }
    @Test fun readsApeV2Title() = assertEquals(
        MetadataFieldObservation.Present(listOf("Track")),
        read(build(item("TITLE",ascii("Track")))).observation(MetadataField.TITLE))
    @Test fun multiValueIsOrdered() = assertEquals(
        MetadataFieldObservation.Present(listOf("Jazz","Fusion")),
        read(build(item("GENRE",ascii("Jazz")+byteArrayOf(0)+ascii("Fusion"))))
            .observation(MetadataField.GENRE))
    @Test fun missingKnownFieldCanBeProvenAbsent() = assertEquals(
        MetadataFieldObservation.VerifiedAbsent,
        read(build(item("TITLE",ascii("A")))).observation(MetadataField.GENRE))
    @Test fun unrecognizedFieldIsUnverifiable() = assertTrue(
        read(build(item("TITLE",ascii("A")))).observation(MetadataField.WEBSITE)
            is MetadataFieldObservation.Unverifiable)
    @Test fun duplicateSingleValueConflicts() = assertTrue(
        read(build(item("TITLE",ascii("A")),item("TITLE",ascii("B"))))
            .observation(MetadataField.TITLE) is MetadataFieldObservation.Conflicted)
    @Test fun refusesUnsupportedVersion() = assertTrue(
        read(build(item("TITLE",ascii("A")),tagVersion=1000))
            .observation(MetadataField.TITLE) is MetadataFieldObservation.Unverifiable)
    @Test fun refusesCorruptPayloadLength() {
        val bytes=build(item("TITLE",ascii("A"))).clone()
        // First item size starts right after 24-byte synthetic WavPack prefix.
        le(999).copyInto(bytes,24)
        assertTrue(read(bytes).observation(MetadataField.TITLE)
            is MetadataFieldObservation.Unverifiable)
    }
    @Test fun refusesBinaryTagClaim() = assertTrue(
        read(build(item("TITLE",ascii("A"),2)))
            .observation(MetadataField.TITLE) is MetadataFieldObservation.Unverifiable)
    @Test fun noFooterDoesNotInventAbsence() = assertTrue(
        read(ascii("wvpk")+ByteArray(60)).observation(MetadataField.TITLE)
            is MetadataFieldObservation.Unverifiable)
}
