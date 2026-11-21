package org.michimusic.core.metadata

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRiffAiffColdReaderTest {
    private fun ascii(s: String)=s.toByteArray(Charsets.US_ASCII)
    private fun le(n: Int)=byteArrayOf(n.toByte(),(n ushr 8).toByte(),
        (n ushr 16).toByte(),(n ushr 24).toByte())
    private fun be(n: Int)=byteArrayOf((n ushr 24).toByte(),(n ushr 16).toByte(),
        (n ushr 8).toByte(),n.toByte())
    private fun chunk(name: String, body: ByteArray, riff: Boolean): ByteArray =
        ascii(name)+(if(riff) le(body.size) else be(body.size))+body+
            if (body.size%2==1) byteArrayOf(0) else byteArrayOf()
    private fun wav(vararg info: ByteArray): ByteArray {
        val list=chunk("LIST",ascii("INFO")+info.fold(ByteArray(0)){a,b->a+b},true)
        val body=ascii("WAVE")+list+chunk("data",ByteArray(8),true)
        return ascii("RIFF")+le(body.size)+body
    }
    private fun aiff(vararg tags: ByteArray): ByteArray {
        val body=ascii("AIFF")+tags.fold(ByteArray(0)){a,b->a+b}+
            chunk("SSND",ByteArray(8),false)
        return ascii("FORM")+be(body.size)+body
    }
    private fun report(bytes: ByteArray): MetadataFieldReadReport {
        val file=File.createTempFile("michi-riff-read", ".bin")
        try {
            file.writeBytes(bytes)
            return RandomAccessFile(file,"r").use { MetadataRiffAiffColdReader.observe(it.channel) }
        } finally { file.delete() }
    }
    @Test fun wavReadsInfoTitleAndArtist() {
        val p=report(wav(chunk("INAM",ascii("Title"),true),chunk("IART",ascii("Artist"),true)))
        assertEquals(MetadataPhysicalContainer.WAV,p.container)
        assertEquals(MetadataFieldObservation.Present(listOf("Title")),p.observation(MetadataField.TITLE))
        assertEquals(MetadataFieldObservation.Present(listOf("Artist")),p.observation(MetadataField.ARTIST))
    }
    @Test fun wavUnknownGenreIsNotAbsent() = assertTrue(
        report(wav(chunk("INAM",ascii("Title"),true))).observation(MetadataField.GENRE)
            is MetadataFieldObservation.Unverifiable)
    @Test fun wavConflictedSingleTitleIsNotEditable() = assertTrue(
        report(wav(chunk("INAM",ascii("A"),true),chunk("INAM",ascii("B"),true)))
            .observation(MetadataField.TITLE) is MetadataFieldObservation.Conflicted)
    @Test fun aiffReadsName() = assertEquals(
        MetadataFieldObservation.Present(listOf("Chamber")),
        report(aiff(chunk("NAME",ascii("Chamber"),false))).observation(MetadataField.TITLE))
    @Test fun aiffMissingNameIsUnknown() = assertTrue(
        report(aiff(chunk("AUTH",ascii("Composer"),false))).observation(MetadataField.TITLE)
            is MetadataFieldObservation.Unverifiable)
    @Test fun invalidChunkOffsetIsUnverifiable() {
        val bytes=wav(chunk("INAM",ascii("Title"),true))
        val changed=bytes.copyOf().also { le(4096).copyInto(it,16) }
        assertTrue(report(changed).observation(MetadataField.TITLE)
            is MetadataFieldObservation.Unverifiable)
    }
    @Test fun nonAsciiCodepageIsNotGuessed() = assertTrue(
        report(aiff(chunk("NAME",byteArrayOf(0xe9.toByte()),false)))
            .observation(MetadataField.TITLE) is MetadataFieldObservation.Conflicted)
}
