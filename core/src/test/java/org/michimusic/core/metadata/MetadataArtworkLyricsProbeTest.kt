package org.michimusic.core.metadata

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class MetadataArtworkLyricsProbeTest {
    private fun sync(x:Int)=byteArrayOf(((x shr 21) and 127).toByte(), ((x shr 14) and 127).toByte(),((x shr 7) and 127).toByte(),(x and 127).toByte())
    private fun word(x:Int)=byteArrayOf((x ushr 24).toByte(),(x ushr 16).toByte(),(x ushr 8).toByte(),x.toByte())
    private val png = byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10) + ByteArray(16)
    private fun frame(id:String, data:ByteArray, version:Int=4): ByteArray =
        id.toByteArray() + (if (version==4) sync(data.size) else word(data.size)) + byteArrayOf(0,0) + data
    private fun tag(vararg frames:ByteArray, version:Int=4): ByteArray {
        val payload=frames.fold(ByteArray(0)) { a,b -> a+b }
        return "ID3".toByteArray()+byteArrayOf(version.toByte(),0,0)+sync(payload.size)+payload
    }
    private fun apic(type:Int=3)=byteArrayOf(3)+"image/png".toByteArray()+byteArrayOf(0,type.toByte(),0)+png
    private fun text(language:String,descriptor:String,body:String): ByteArray =
        byteArrayOf(3)+language.toByteArray()+descriptor.toByteArray()+byteArrayOf(0)+body.toByteArray()
    private fun picBlock():ByteArray {
        val mime="image/png".toByteArray()
        return word(3)+word(mime.size)+mime+word(0)+word(2)+word(2)+word(8)+word(0)+word(png.size)+png
    }
    private fun flacWithPicture():ByteArray {
        val streamInfo=byteArrayOf(0,0,0,34)+ByteArray(34)
        val body=picBlock();val picture=byteArrayOf(0x86.toByte(),0,0,body.size.toByte())+body
        return "fLaC".toByteArray()+streamInfo+picture
    }
    @Test fun id3ArtAndSpanishTextAreSeparateVerifiedEntries() {
        val bytes=tag(frame("COMM",text("spa","Notas","Comentario")),frame("APIC",apic()),frame("USLT",text("eng","","Lyrics")))
        val read=MetadataArtworkLyricsProbe.inspect(bytes) as MetadataArtworkLyricsProbe.Result.Ready
        assertEquals(2,read.snapshot.texts.size)
        assertEquals("spa",read.snapshot.texts[0].identity.language)
        assertEquals("Notas",read.snapshot.texts[0].identity.description)
        assertEquals("Comentario",read.snapshot.texts[0].text)
        assertEquals(1,read.snapshot.artworks.size)
        assertArrayEquals(png,read.snapshot.artworks.single().image)
        assertEquals(3,read.snapshot.artworks.single().info.type)
    }
    @Test fun id3V23IsSupportedForConservativePlainBytes() {
        val data=tag(frame("COMM",byteArrayOf(0)+"eng".toByteArray()+byteArrayOf(0)+"safe".toByteArray(),3),version=3)
        assertEquals(MetadataArtworkLyricsProbe.Format.ID3_V23,
            (MetadataArtworkLyricsProbe.inspect(data) as MetadataArtworkLyricsProbe.Result.Ready).snapshot.format)
    }
    @Test fun duplicateLanguageDescriptorIsRejected() {
        val x=frame("COMM",text("eng","","A"))
        val y=frame("COMM",text("eng","","B"))
        assertEquals(MetadataArtworkLyricsProbe.Reason.DUPLICATE_TEXT_IDENTITY,
            (MetadataArtworkLyricsProbe.inspect(tag(x,y)) as MetadataArtworkLyricsProbe.Result.Blocked).reason)
    }
    @Test fun flaggedOrCorruptFrameFailsClosed() {
        val x=frame("APIC",apic()).also { it[9]=1 }
        assertEquals(MetadataArtworkLyricsProbe.Reason.UNSUPPORTED_VERSION_OR_FLAGS,
            (MetadataArtworkLyricsProbe.inspect(tag(x)) as MetadataArtworkLyricsProbe.Result.Blocked).reason)
    }
    @Test fun duplicatedMalformedCommentDoesNotBecomeAbsent() {
        val x=frame("COMM",text("enx","","safe"))
        val corrupt=x.copyOf(); corrupt[11]=0
        assertEquals(MetadataArtworkLyricsProbe.Reason.TEXT_UNSUPPORTED,
            (MetadataArtworkLyricsProbe.inspect(tag(corrupt)) as MetadataArtworkLyricsProbe.Result.Blocked).reason)
    }
    @Test fun noArtAndNoCommentsIsValidOnlyForCompleteTag() {
        val x=tag(frame("TIT2",byteArrayOf(3,65)))
        val r=MetadataArtworkLyricsProbe.inspect(x) as MetadataArtworkLyricsProbe.Result.Ready
        assertTrue(r.snapshot.artworks.isEmpty());assertTrue(r.snapshot.texts.isEmpty())
        assertEquals(MetadataArtworkLyricsProbe.Reason.TRUNCATED,
            (MetadataArtworkLyricsProbe.inspect(x.copyOf(x.size-1)) as MetadataArtworkLyricsProbe.Result.Blocked).reason)
    }
    @Test fun flacImageIsByteExactAndNotDuplicated() {
        val r=MetadataArtworkLyricsProbe.inspect(flacWithPicture()) as MetadataArtworkLyricsProbe.Result.Ready
        assertEquals(MetadataArtworkLyricsProbe.Format.FLAC,r.snapshot.format)
        assertEquals(2,r.snapshot.artworks.single().info.width)
        assertArrayEquals(png,r.snapshot.artworks.single().image)
    }
    @Test fun missingFlacMetadataTerminatorIsUnsafe() {
        val x=flacWithPicture().copyOf();x[42]=6
        assertEquals(MetadataArtworkLyricsProbe.Reason.TRUNCATED,
            (MetadataArtworkLyricsProbe.inspect(x) as MetadataArtworkLyricsProbe.Result.Blocked).reason)
    }
    @Test fun providerUnknownOrMissingPictureFailsNotEmptyAssertion() {
        assertEquals(MetadataArtworkLyricsProbe.Reason.UNKNOWN_FORMAT,
            (MetadataArtworkLyricsProbe.inspect(byteArrayOf(1,2,3,4)) as MetadataArtworkLyricsProbe.Result.Blocked).reason)
        val x=tag(frame("APIC",byteArrayOf(1,2,3)))
        assertEquals(MetadataArtworkLyricsProbe.Reason.ARTWORK_UNSUPPORTED,
            (MetadataArtworkLyricsProbe.inspect(x) as MetadataArtworkLyricsProbe.Result.Blocked).reason)
    }
    @Test fun tooManyPicturesIsRejectedRatherThanDroppingUnseen() {
        val x=frame("APIC",apic())
        val result=MetadataArtworkLyricsProbe.inspect(tag(*Array(7){x}))
        assertEquals(MetadataArtworkLyricsProbe.Reason.TOO_MANY_IMAGES,
            (result as MetadataArtworkLyricsProbe.Result.Blocked).reason)
    }
    @Test fun typedTextWithAnInvalidLanguageCannotBeAdvertisedAsVerified() {
        val result=MetadataArtworkLyricsProbe.inspect(tag(frame("USLT",text("spa","nota","valid")),
            frame("COMM",text("e?g","","bad"))))
        assertEquals(MetadataArtworkLyricsProbe.Reason.TEXT_UNSUPPORTED,
            (result as MetadataArtworkLyricsProbe.Result.Blocked).reason)
    }
    @Test fun upperBoundAndExactDeclaredLengthProtectMetadataOnlyRead() {
        val header=tag(frame("TIT2",byteArrayOf(3,65))).copyOfRange(0,10)
        assertEquals(22,MetadataArtworkLyricsProbe.id3DeclaredLength(header))
        assertNull(MetadataArtworkLyricsProbe.id3DeclaredLength(header.copyOfRange(0,9)))
        val huge=header.copyOf();huge[6]=0x7f;huge[7]=0x7f;huge[8]=0x7f;huge[9]=0x7f
        assertNull(MetadataArtworkLyricsProbe.id3DeclaredLength(huge))
    }
}
