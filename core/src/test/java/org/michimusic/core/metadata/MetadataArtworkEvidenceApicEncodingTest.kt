package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataArtworkEvidenceApicEncodingTest {
    private fun be(n:Int)=byteArrayOf((n ushr 24).toByte(),(n ushr 16).toByte(),
        (n ushr 8).toByte(),n.toByte())
    private fun png(width:Int=1,height:Int=1)=
        byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10)+be(13)+"IHDR".toByteArray()+
        be(width)+be(height)+byteArrayOf(8,2,0,0,0)+ByteArray(4)+
        be(0)+"IEND".toByteArray()+ByteArray(4)

    private fun apic(encoding:Int, description:ByteArray, image:ByteArray=png(),
        mime:String="image/png"):ByteArray =
        byteArrayOf(encoding.toByte())+mime.toByteArray()+byteArrayOf(0,3)+
        description+(if (encoding==0 || encoding==3) byteArrayOf(0) else byteArrayOf(0,0))+image

    @Test fun v23AcceptsLatin1AndUtf16Only() {
        assertNotNull(MetadataArtworkEvidence.id3ApicFrame(apic(0,"Title".toByteArray()),3))
        assertNotNull(MetadataArtworkEvidence.id3ApicFrame(
            apic(1,byteArrayOf(0xff.toByte(),0xfe.toByte(),0x41,0)),3))
        assertNull(MetadataArtworkEvidence.id3ApicFrame(apic(2,byteArrayOf(0,0x41)),3))
        assertNull(MetadataArtworkEvidence.id3ApicFrame(apic(3,"A".toByteArray()),3))
    }

    @Test fun v24AcceptsAllFourEncodings() {
        assertNotNull(MetadataArtworkEvidence.id3ApicFrame(apic(0,"Title".toByteArray()),4))
        assertNotNull(MetadataArtworkEvidence.id3ApicFrame(
            apic(1,byteArrayOf(0xff.toByte(),0xfe.toByte(),0x41,0)),4))
        assertNotNull(MetadataArtworkEvidence.id3ApicFrame(apic(2,byteArrayOf(0,0x41)),4))
        assertNotNull(MetadataArtworkEvidence.id3ApicFrame(apic(3,"Title".toByteArray()),4))
    }

    @Test fun utf16LittleEndianBomDecodesDescription() {
        val frame=MetadataArtworkEvidence.id3ApicFrame(
            apic(1,byteArrayOf(0xff.toByte(),0xfe.toByte(),0x41,0,0x42,0)),4)!!
        assertEquals("AB",frame.description)
    }

    @Test fun utf16BigEndianBomDecodesDescription() {
        val frame=MetadataArtworkEvidence.id3ApicFrame(
            apic(1,byteArrayOf(0xfe.toByte(),0xff.toByte(),0,0x41,0,0x42)),4)!!
        assertEquals("AB",frame.description)
    }

    @Test fun utf16BeWithoutBomDecodesDescription() {
        val frame=MetadataArtworkEvidence.id3ApicFrame(
            apic(2,byteArrayOf(0,0x41,0,0x42)),4)!!
        assertEquals("AB",frame.description)
    }

    @Test fun emptyDescriptionIsAllowed() {
        val frame=MetadataArtworkEvidence.id3ApicFrame(apic(3,ByteArray(0)),4)!!
        assertEquals("",frame.description)
    }

    @Test fun corruptOrTruncatedImageIsRejected() {
        val corrupt=png().also { it[0]=0 }
        assertNull(MetadataArtworkEvidence.id3ApicFrame(apic(3,byteArrayOf(0),corrupt),4))
        val full=apic(3,byteArrayOf(0))
        assertNull(MetadataArtworkEvidence.id3ApicFrame(full.copyOf(8),4))
    }

    @Test fun imageStartPointsToTheExactImageBytes() {
        val image=png()
        val payload=apic(3,"Desc".toByteArray(),image)
        val frame=MetadataArtworkEvidence.id3ApicFrame(payload,4)!!
        assertEquals(image.toList(),payload.copyOfRange(frame.imageStart,payload.size).toList())
        assertEquals(1,frame.picture.width)
        assertEquals(1,frame.picture.height)
    }
    @Test fun realJpegWithSosIsAccepted() {
        val jpeg=java.util.Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAADAAIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDxGiiitjI//9k=")
        val frame=MetadataArtworkEvidence.id3ApicFrame(apic(3,ByteArray(0),jpeg,"image/jpeg"),4)
        assertNotNull(frame)
        assertEquals(2,frame!!.picture.width)
        assertEquals(3,frame.picture.height)
    }

    @Test fun jpegInterMarkerFillBytesAreSkipped() {
        val jpeg=java.util.Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAADAAIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDxGiiitjI//9k=")
        val sof=jpeg.indices.first { i -> i+1<jpeg.size && jpeg[i]==0xff.toByte() && jpeg[i+1]==0xc0.toByte() }
        val filled=jpeg.copyOfRange(0,sof)+byteArrayOf(0xff.toByte())+jpeg.copyOfRange(sof,jpeg.size)
        val frame=MetadataArtworkEvidence.id3ApicFrame(apic(3,ByteArray(0),filled,"image/jpeg"),4)
        assertNotNull(frame)
        assertEquals(2,frame!!.picture.width)
        assertEquals(3,frame.picture.height)
    }

}
