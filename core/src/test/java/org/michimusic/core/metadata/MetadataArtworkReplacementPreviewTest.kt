package org.michimusic.core.metadata

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class MetadataArtworkReplacementPreviewTest {
    private val src = byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10)+ByteArray(16)
    private val replacement=src+byteArrayOf(1,2,3)
    private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
    private val picture = MetadataArtworkLyricsProbe.Artwork(0,
        MetadataArtworkEvidence.Picture(MetadataArtworkEvidence.Format.ID3_APIC,3,"image/png",src.size,sha(src)),src)
    private val original = MetadataArtworkLyricsProbe.Snapshot(MetadataArtworkLyricsProbe.Format.ID3_V24,listOf(picture),emptyList())
    @Test fun proposesSingleImageWithExactOldDigest() {
        val p=(MetadataArtworkReplacementPreview.review(original,0,replacement,"image/png") as MetadataArtworkReplacementPreview.Result.Ready).proposal
        assertEquals(sha(src),p.sourceSha256);assertEquals(sha(replacement),p.proposedSha256)
        assertArrayEquals(replacement,p.proposedBytes)
        assertArrayEquals(src,picture.image)
    }
    @Test fun refusesWhenArtworkUnobserved() {
        assertEquals(MetadataArtworkReplacementPreview.Reason.ORIGINAL_NOT_VERIFIED,
            (MetadataArtworkReplacementPreview.review(null,0,replacement,"image/png") as MetadataArtworkReplacementPreview.Result.Blocked).reason)
    }
    @Test fun mismatchedIndexFails() {
        assertEquals(MetadataArtworkReplacementPreview.Reason.INVALID_SELECTION,
            (MetadataArtworkReplacementPreview.review(original,7,replacement,"image/png") as MetadataArtworkReplacementPreview.Result.Blocked).reason)
    }
    @Test fun noChangeDoesNotCreateOperation() {
        assertEquals(MetadataArtworkReplacementPreview.Reason.SAME_IMAGE,
            (MetadataArtworkReplacementPreview.review(original,0,src,"image/png") as MetadataArtworkReplacementPreview.Result.Blocked).reason)
    }
    @Test fun corruptedAndWrongMimeAreRejected() {
        assertEquals(MetadataArtworkReplacementPreview.Reason.INVALID_BYTES,
            (MetadataArtworkReplacementPreview.review(original,0,ByteArray(30),"image/png") as MetadataArtworkReplacementPreview.Result.Blocked).reason)
        assertEquals(MetadataArtworkReplacementPreview.Reason.UNSUPPORTED_MIME,
            (MetadataArtworkReplacementPreview.review(original,0,replacement,"image/webp") as MetadataArtworkReplacementPreview.Result.Blocked).reason)
    }
    @Test fun alteredSourceArtHashCannotBeReplacedSilently() {
        val wrong = original.copy(artworks=listOf(picture.copy(info=picture.info.copy(sha256="0".repeat(64)))))
        assertEquals(MetadataArtworkReplacementPreview.Reason.ORIGINAL_NOT_VERIFIED,
            (MetadataArtworkReplacementPreview.review(wrong,0,replacement,"image/png") as MetadataArtworkReplacementPreview.Result.Blocked).reason)
    }
    @Test fun duplicateCoverTypeDoesNotAutoOverwriteBoth() {
        val multiple = original.copy(artworks=listOf(picture,picture.copy(ordinal=1)))
        assertEquals(MetadataArtworkReplacementPreview.Reason.MULTIPLE_TARGETS,
            (MetadataArtworkReplacementPreview.review(multiple,0,replacement,"image/png") as MetadataArtworkReplacementPreview.Result.Blocked).reason)
    }
}
