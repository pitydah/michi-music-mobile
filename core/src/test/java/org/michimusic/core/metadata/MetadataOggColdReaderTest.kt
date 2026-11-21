package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataOggColdReaderTest {
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte(),
        (n ushr 16).toByte(), (n ushr 24).toByte())
    private fun packetId(opus: Boolean) = if (opus) ascii("OpusHead") +
        byteArrayOf(1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0) else
        byteArrayOf(1) + ascii("vorbis") + ByteArray(23)
    private fun comment(opus: Boolean, tags: List<String>): ByteArray = ByteArrayOutputStream().apply {
        write(if (opus) ascii("OpusTags") else byteArrayOf(3) + ascii("vorbis"))
        write(le32(4)); write(ascii("mich")); write(le32(tags.size))
        tags.forEach { val encoded=it.toByteArray(Charsets.UTF_8)
            write(le32(encoded.size)); write(encoded) }
        if (!opus) write(1)
    }.toByteArray()
    private fun oggPage(payload: ByteArray, seq: Int, first: Boolean): ByteArray {
        require(payload.size < 255)
        val page = ByteArrayOutputStream().apply {
            write(ascii("OggS")); write(0); write(if(first)2 else 0)
            write(ByteArray(8)); write(le32(42)); write(le32(seq)); write(ByteArray(4))
            write(1); write(payload.size); write(payload)
        }.toByteArray()
        var crc=0
        page.indices.forEach { i ->
            crc = crc xor ((if (i in 22..25) 0 else page[i].toInt() and 255) shl 24)
            repeat(8){ crc=(crc shl 1) xor if((crc and Int.MIN_VALUE) != 0) 0x04C11DB7 else 0 }
        }
        le32(crc).copyInto(page, 22)
        return page
    }
    private fun observe(opus: Boolean, values: List<String> = listOf("TITLE=Song", "GENRE=Jazz"),
                        corruptCrc: Boolean=false): MetadataFieldReadReport {
        val a=oggPage(packetId(opus),0,true)
        val b=oggPage(comment(opus, values),1,false)
        if (corruptCrc) b[22] = (b[22].toInt() xor 1).toByte()
        val file=File.createTempFile("michi-ogg-read", ".ogg")
        try {
            file.writeBytes(a + b)
            return RandomAccessFile(file,"r").use {
                MetadataOggColdReader.observe(it.channel)
            }
        } finally { file.delete() }
    }
    @Test fun readsOpusTitleAndGenre() {
        val report=observe(true)
        assertEquals(MetadataPhysicalContainer.OGG_OPUS,report.container)
        assertEquals(MetadataFieldObservation.Present(listOf("Song")),
            report.observation(MetadataField.TITLE))
        assertEquals(MetadataFieldObservation.Present(listOf("Jazz")),
            report.observation(MetadataField.GENRE))
    }
    @Test fun readsVorbisTitleAndGenre() {
        val report=observe(false)
        assertEquals(MetadataPhysicalContainer.OGG_VORBIS,report.container)
        assertEquals(MetadataFieldObservation.Present(listOf("Song")),
            report.observation(MetadataField.TITLE))
    }
    @Test fun absentSupportedFieldHasVerifiedAbsence() = assertEquals(
        MetadataFieldObservation.VerifiedAbsent,
        observe(true, listOf("TITLE=Song")).observation(MetadataField.GENRE))
    @Test fun unsupportedFieldIsNeverVerifiedAbsent() = assertTrue(
        observe(true).observation(MetadataField.WEBSITE) is MetadataFieldObservation.Unverifiable)
    @Test fun duplicatedSingleValueIsConflicted() = assertTrue(
        observe(true, listOf("TITLE=A", "TITLE=B"))
            .observation(MetadataField.TITLE) is MetadataFieldObservation.Conflicted)
    @Test fun repeatedMultipleValuesStaySeparate() = assertEquals(
        MetadataFieldObservation.Present(listOf("Jazz; Fusion", "Rock")),
        observe(true,listOf("GENRE=Jazz; Fusion", "GENRE=Rock"))
            .observation(MetadataField.GENRE))
    @Test fun corruptedCrcNeverProvesMissingTags() = assertTrue(
        observe(true,corruptCrc=true).observation(MetadataField.GENRE)
            is MetadataFieldObservation.Unverifiable)
    @Test fun malformedValueNeverBecomesEmptyValue() = assertTrue(
        observe(false,listOf("TITLE=")).observation(MetadataField.TITLE)
            is MetadataFieldObservation.Unverifiable)
    @Test fun noTagDoesNotInventTitle() = assertEquals(
        MetadataFieldObservation.VerifiedAbsent,
        observe(false,emptyList()).observation(MetadataField.TITLE))
}
