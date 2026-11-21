package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataMp4ColdReaderTest {
    private fun ascii(v: String) = v.toByteArray(Charsets.ISO_8859_1)
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(),(v ushr 16).toByte(),
        (v ushr 8).toByte(),v.toByte())
    private fun atom(name: String, body: ByteArray): ByteArray =
        be32(body.size+8) + ascii(name) + body
    private fun dataTag(type: Int, value: String) = atom("data",byteArrayOf(
        0,0,0,type.toByte(),0,0,0,0)+value.toByteArray(Charsets.UTF_8))
    private fun item(name: String, value: String) = atom(name,dataTag(1,value))
    private fun body(items: ByteArray, late: Boolean = false): ByteArray {
        val ftyp = atom("ftyp",ascii("M4A ") + ByteArray(4))
        val metadata = atom("moov",atom("udta",atom("meta",ByteArray(4) + atom("ilst",items))))
        return ftyp + (if (late) atom("mdat",ByteArray(22)) else ByteArray(0)) + metadata
    }
    private fun observe(bytes: ByteArray): MetadataFieldReadReport {
        val file=File.createTempFile("michi-mp4-read", ".m4a")
        try {
            file.writeBytes(bytes)
            return RandomAccessFile(file,"r").use { MetadataMp4ColdReader.observe(it.channel) }
        } finally { file.delete() }
    }
    @Test fun findsLateMoovAndTitle() {
        val report=observe(body(item("©nam","Michi Song"),true))
        assertEquals(MetadataPhysicalContainer.MP4_M4A,report.container)
        assertEquals(MetadataFieldObservation.Present(listOf("Michi Song")),
            report.observation(MetadataField.TITLE))
    }
    @Test fun missingTitleIsUnverifiableNotAbsent() = assertTrue(
        observe(body(item("©ART","Artist"))).observation(MetadataField.TITLE)
            is MetadataFieldObservation.Unverifiable)
    @Test fun supportsArtistAndAlbum() {
        val r=observe(body(item("©ART","Artist")+item("©alb","Album")))
        assertEquals(MetadataFieldObservation.Present(listOf("Artist")),
            r.observation(MetadataField.ARTIST))
        assertEquals(MetadataFieldObservation.Present(listOf("Album")),
            r.observation(MetadataField.ALBUM))
    }
    @Test fun preservesUnicode() = assertEquals(
        MetadataFieldObservation.Present(listOf("Canción ♪")),
        observe(body(item("©nam","Canción ♪"))).observation(MetadataField.TITLE))
    @Test fun unsupportedBinaryTagCannotBeInvented() = assertTrue(
        observe(body(atom("©nam",dataTag(0,"Song"))))
            .observation(MetadataField.TITLE) is MetadataFieldObservation.Conflicted)
    @Test fun duplicateTitleCannotBePromotedToSingleValue() = assertTrue(
        observe(body(item("©nam","A")+item("©nam","B")))
            .observation(MetadataField.TITLE) is MetadataFieldObservation.Conflicted)
    @Test fun corruptAtomIsUnverifiable() = assertTrue(
        observe(body(item("©nam","A")).copyOfRange(0,28))
            .observation(MetadataField.TITLE) is MetadataFieldObservation.Unverifiable)
    @Test fun noIlstIsUnverifiable() = assertTrue(
        observe(atom("ftyp",ascii("M4A ")+ByteArray(4))+
            atom("moov",atom("udta",atom("meta",ByteArray(4)))))
            .observation(MetadataField.TITLE) is MetadataFieldObservation.Unverifiable)
    @Test fun inspectIsReadOnly() {
        val bytes=body(item("©nam","Original"))
        val report=observe(bytes)
        assertTrue(report.verifiedFields.contains(MetadataField.TITLE))
        assertTrue(!report.verifiedFields.contains(MetadataField.BARCODE))
    }
}
