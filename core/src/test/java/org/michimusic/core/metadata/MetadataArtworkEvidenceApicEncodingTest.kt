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

    private fun apic(encoding:Int, description:ByteArray, image:ByteArray=png()):ByteArray =
        byteArrayOf(encoding.toByte())+"image/png".toByteArray()+byteArrayOf(0,3)+
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
}
