package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataArtworkEvidenceTest {
    private val png = byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10) +
        word(13) + "IHDR".toByteArray() + word(1) + word(1) +
        byteArrayOf(8,2,0,0,0) + ByteArray(4) + word(0) + "IEND".toByteArray() + ByteArray(4)
    private val jpg = byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xc0.toByte(),
        0,17,8,0,9,0,12,3,1,0x11,0,2,0x11,0,3,0x11,0,
        0xff.toByte(),0xd9.toByte())
    private fun word(n:Int)=byteArrayOf((n ushr 24).toByte(),(n ushr 16).toByte(),(n ushr 8).toByte(),n.toByte())
    private fun flac(image:ByteArray=png, mime:String="image/png", w:Int=512, h:Int=512) = ByteArrayOutputStream().apply {
        write(word(3));write(word(mime.length));write(mime.toByteArray());write(word(0))
        write(word(w));write(word(h));write(word(24));write(word(0));write(word(image.size));write(image)
    }.toByteArray()
    private fun apic(image:ByteArray=jpg,mime:String="image/jpeg") =
        byteArrayOf(3) + mime.toByteArray() + byteArrayOf(0,3,0) + image
    @Test fun readsFlacPictureHeaderAndDigest() {
        val x=MetadataArtworkEvidence.flacPicture(flac())!!
        assertEquals(MetadataArtworkEvidence.Format.FLAC_PICTURE,x.format)
        assertEquals("image/png",x.mime)
        assertEquals(512,x.width)
        assertEquals(512,x.height)
        assertEquals(64,x.sha256.length)
        assertEquals(png.size,x.bytes)
    }
    @Test fun readsId3JpegWithoutDecoding() {
        val x=MetadataArtworkEvidence.id3Apic(apic())!!
        assertEquals(MetadataArtworkEvidence.Format.ID3_APIC,x.format)
        assertEquals(3,x.type)
        assertEquals(jpg.size,x.bytes)
        assertEquals(12,x.width)
        assertEquals(9,x.height)
    }
    @Test fun malformedFlacLengthIsRejected() {
        val bad=flac().copyOfRange(0,flac().size-1)
        assertNull(MetadataArtworkEvidence.flacPicture(bad))
    }
    @Test fun dimensionOverBudgetIsRejected() {
        assertNull(MetadataArtworkEvidence.flacPicture(flac(w=50000)))
    }
    @Test fun rejectsMismatchedMediaMagic() {
        assertNull(MetadataArtworkEvidence.flacPicture(flac(image=jpg)))
        assertNull(MetadataArtworkEvidence.id3Apic(apic(image=png)))
    }
    @Test fun corruptedUnknownMimeRejected() {
        assertNull(MetadataArtworkEvidence.flacPicture(flac(mime="image/webp")))
        assertNull(MetadataArtworkEvidence.id3Apic(apic(mime="image/webp")))
    }
    @Test fun corruptControlBytesAreNotAProofOfArtwork() {
        val bad=apic().copyOf()
        bad[0]=1
        assertNull(MetadataArtworkEvidence.id3Apic(bad))
    }
    @Test fun changesToImageBytesChangeDigest() {
        val a=MetadataArtworkEvidence.flacPicture(flac())!!.sha256
        val changed=png.copyOf().also {it[it.lastIndex]=1}
        val b=MetadataArtworkEvidence.flacPicture(flac(image=changed))!!.sha256
        assertTrue(a!=b)
    }
}
