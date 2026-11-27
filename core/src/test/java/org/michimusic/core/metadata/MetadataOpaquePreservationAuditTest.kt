package org.michimusic.core.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataOpaquePreservationAuditTest {
    private val audit = MetadataOpaquePreservationAudit
    private fun s(x: String) = x.toByteArray(StandardCharsets.US_ASCII)
    private fun id3Frame(id: String, value: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(s(id)); write(byteArrayOf(0, 0, 0, value.size.toByte())); write(byteArrayOf(0, 0)); write(value)
    }.toByteArray()
    private fun id3(frames: ByteArray, audio: String = "AUDIO"): ByteArray = ByteArrayOutputStream().apply {
        val n = frames.size
        write(s("ID3")); write(byteArrayOf(3, 0, 0, ((n shr 21) and 0x7f).toByte(),
            ((n shr 14) and 0x7f).toByte(), ((n shr 7) and 0x7f).toByte(), (n and 0x7f).toByte()))
        write(frames); write(s(audio))
    }.toByteArray()
    private fun uint(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte(),
        (n ushr 16).toByte(), (n ushr 24).toByte())
    private fun comments(vendor: String = "michi", vararg values: String): ByteArray =
        ByteArrayOutputStream().apply {
            val v=s(vendor); write(uint(v.size)); write(v); write(uint(values.size))
            values.forEach { val x=s(it); write(uint(x.size)); write(x) }
        }.toByteArray()
    private fun flac(comment: ByteArray, opaque: ByteArray = s("PICTURE"),
                     audio: String = "AUDIO"): ByteArray = ByteArrayOutputStream().apply {
        write(s("fLaC"))
        write(byteArrayOf(0, 0, 0, opaque.size.toByte())); write(opaque)
        write(byteArrayOf(0x84.toByte(), (comment.size ushr 16).toByte(),
            (comment.size ushr 8).toByte(), comment.size.toByte())); write(comment)
        write(s(audio))
    }.toByteArray()
    private fun verify(left: ByteArray, right: ByteArray, format: MetadataAudioContainer,
                       edited: Set<MetadataField> = setOf(MetadataField.TITLE),
                       resources: List<MetadataCommitIntentManifest.ResourceChange> = emptyList()):
                       MetadataOpaquePreservationAudit.Verdict {
        val original = File.createTempFile("opaque-original", ".bin")
        val staged = File.createTempFile("opaque-stage", ".bin")
        try {
            original.writeBytes(left); staged.writeBytes(right)
            return audit.compare(original, staged, format, edited, resources)
        } finally { original.delete(); staged.delete() }
    }
    private fun frameTitle(text: String) = id3Frame("TIT2", byteArrayOf(0) + s(text))
    private fun sameId3(unknown: String = "u", audio: String = "AUDIO") = id3(
        frameTitle("OLD") + id3Frame("PRIV", s(unknown)), audio)
    private fun described(
        kind: MetadataId3DescribedTagCodec.Kind,
        language: String,
        description: String,
        text: String,
    ): ByteArray = id3Frame(
        kind.frameId,
        byteArrayOf(1) + s(language) +
            byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
            description.toByteArray(StandardCharsets.UTF_16LE) +
            byteArrayOf(0, 0) +
            text.toByteArray(StandardCharsets.UTF_16),
    )
    private fun pictureAdd(ordinal: Int) = MetadataCommitIntentManifest.ResourceChange.PictureAdd(
        MetadataArtworkEvidence.Format.ID3_APIC, ordinal, 3, "",
        "image/png", 8, "a".repeat(64),
    )
    private fun pictureReplace(ordinal: Int) =
        MetadataCommitIntentManifest.ResourceChange.PictureReplacement(
            ordinal, MetadataArtworkEvidence.Format.ID3_APIC, 3, "",
            "image/png", 8, "b".repeat(64), "image/png", 9, "c".repeat(64),
        )
    @Test fun id3AllowsExactChangeWithUnchangedPrivAndAudio() = assertTrue(
        verify(sameId3(), id3(frameTitle("NEW") + id3Frame("PRIV",s("u"))),
            MetadataAudioContainer.ID3_V23) is MetadataOpaquePreservationAudit.Verdict.Compared)
    @Test fun id3BlocksChangedPrivateFrame() = assertEquals(
        MetadataOpaquePreservationAudit.Verdict.Blocked(MetadataOpaquePreservationAudit.Refusal.UNEDITED_OPAQUE_CHANGED),
        verify(sameId3(),id3(frameTitle("NEW") + id3Frame("PRIV",s("DIFFERENT"))),
            MetadataAudioContainer.ID3_V23))
    @Test fun id3BlocksChangedAudio() = assertEquals(
        MetadataOpaquePreservationAudit.Verdict.Blocked(MetadataOpaquePreservationAudit.Refusal.AUDIO_CHANGED),
        verify(sameId3(),id3(frameTitle("NEW") + id3Frame("PRIV",s("u")),"OTHER"),
            MetadataAudioContainer.ID3_V23))
    @Test fun id3AllowsTargetedMbidWithUnrelatedTxxxPreserved() {
        val old = MetadataMusicBrainzTagMap.encodeId3(3,MetadataField.MUSICBRAINZ_RECORDING_ID,
            "11111111-1111-4111-8111-111111111111")
        val fresh = MetadataMusicBrainzTagMap.encodeId3(3,MetadataField.MUSICBRAINZ_RECORDING_ID,
            "22222222-2222-4222-8222-222222222222")
        val unrelated = id3Frame("TXXX",byteArrayOf(0) + s("Unrelated") + byteArrayOf(0) + s("X"))
        val before=id3(id3Frame("TXXX",old)+unrelated)
        val after=id3(id3Frame("TXXX",fresh)+unrelated)
        assertTrue(verify(before,after,MetadataAudioContainer.ID3_V23,
            setOf(MetadataField.MUSICBRAINZ_RECORDING_ID)) is MetadataOpaquePreservationAudit.Verdict.Compared)
        assertEquals(MetadataOpaquePreservationAudit.Verdict.Blocked(
            MetadataOpaquePreservationAudit.Refusal.UNEDITED_OPAQUE_CHANGED),
            verify(before,id3(id3Frame("TXXX",fresh)+id3Frame("TXXX",byteArrayOf(0)+
                s("Unrelated")+byteArrayOf(0)+s("Y"))),MetadataAudioContainer.ID3_V23,
                setOf(MetadataField.MUSICBRAINZ_RECORDING_ID)))
    }
    @Test fun id3AllowsCanonicalDescribedEntryOnly() {
        val old=MetadataId3DescribedTagCodec.encodeCanonical(
            MetadataId3DescribedTagCodec.Kind.COMMENT,3,"Before")
        val fresh=MetadataId3DescribedTagCodec.encodeCanonical(
            MetadataId3DescribedTagCodec.Kind.COMMENT,3,"After")
        assertTrue(verify(id3(id3Frame("COMM",old)+id3Frame("PRIV",s("same"))),
            id3(id3Frame("COMM",fresh)+id3Frame("PRIV",s("same"))),
            MetadataAudioContainer.ID3_V23,setOf(MetadataField.COMMENT))
            is MetadataOpaquePreservationAudit.Verdict.Compared)
    }
    @Test fun id3ResourceOnlyAllowsSelectedApicAddAndPreservesPriv() {
        val before = id3(id3Frame("PRIV", s("same")))
        val after = id3(
            id3Frame("PRIV", s("same")) +
                id3Frame("APIC", byteArrayOf(0) + s("image/png") +
                    byteArrayOf(0, 3) + s("NEW")),
        )
        assertTrue(verify(before, after, MetadataAudioContainer.ID3_V23,
            emptySet(), listOf(pictureAdd(0)))
            is MetadataOpaquePreservationAudit.Verdict.Compared)
    }
    @Test fun id3ResourceOnlyBlocksChangedUnselectedApic() {
        val first = id3Frame("APIC", byteArrayOf(0) + s("image/png") +
            byteArrayOf(0, 3) + s("A"))
        val second = id3Frame("APIC", byteArrayOf(0) + s("image/png") +
            byteArrayOf(0, 3) + s("B"))
        val changed = id3Frame("APIC", byteArrayOf(0) + s("image/png") +
            byteArrayOf(0, 3) + s("C"))
        assertEquals(
            MetadataOpaquePreservationAudit.Verdict.Blocked(
                MetadataOpaquePreservationAudit.Refusal.UNEDITED_OPAQUE_CHANGED),
            verify(id3(first + second), id3(first + changed),
                MetadataAudioContainer.ID3_V23, emptySet(), listOf(pictureReplace(0))),
        )
    }
    @Test fun id3ResourceOnlyAllowsSelectedDescribedTextAndPreservesOtherComm() {
        val other = described(
            MetadataId3DescribedTagCodec.Kind.COMMENT, "fra", "notes", "Conservé")
        val added = described(
            MetadataId3DescribedTagCodec.Kind.COMMENT, "spa", "principal", "Nuevo")
        val before = id3(id3Frame("PRIV", s("same")) + other)
        val after = id3(id3Frame("PRIV", s("same")) + other + added)
        val resource = MetadataCommitIntentManifest.ResourceChange.DescribedText(
            kind = MetadataId3DescribedTagCodec.Kind.COMMENT,
            language = "spa",
            description = "principal",
            beforeCount = 0,
            afterCount = 1,
            beforeSha256 = MetadataCommitIntentManifest.valuesDigest(emptyList()),
            afterSha256 = MetadataCommitIntentManifest.valuesDigest(listOf("Nuevo")),
        )
        assertTrue(verify(before, after, MetadataAudioContainer.ID3_V23,
            emptySet(), listOf(resource))
            is MetadataOpaquePreservationAudit.Verdict.Compared)
    }
    @Test fun id3RejectsMalformedUserTextWhenEditingMbid() = assertEquals(
        MetadataOpaquePreservationAudit.Verdict.Blocked(MetadataOpaquePreservationAudit.Refusal.UNSUPPORTED_EDIT),
        verify(id3(id3Frame("TXXX",byteArrayOf(3, 65))),
            id3(id3Frame("TXXX",byteArrayOf(3, 65))),MetadataAudioContainer.ID3_V23,
            setOf(MetadataField.MUSICBRAINZ_ARTIST_ID)))
    @Test fun id3RejectsNonZeroFlags() {
        val corrupt = sameId3().copyOf().also { it[5] = 0x80.toByte() }
        assertEquals(MetadataOpaquePreservationAudit.Verdict.Blocked(MetadataOpaquePreservationAudit.Refusal.UNSUPPORTED_FLAGS),
            verify(corrupt, sameId3(), MetadataAudioContainer.ID3_V23))
    }
    @Test fun flacAllowsUpdatedTitleWithoutTouchingOtherComments() = assertTrue(
        verify(flac(comments("michi", "TITLE=OLD", "X-MICHI=A")),
            flac(comments("michi", "TITLE=NEW", "X-MICHI=A")),
            MetadataAudioContainer.FLAC) is MetadataOpaquePreservationAudit.Verdict.Compared)
    @Test fun flacRejectsChangedUnknownComment() = assertEquals(
        MetadataOpaquePreservationAudit.Verdict.Blocked(MetadataOpaquePreservationAudit.Refusal.UNEDITED_OPAQUE_CHANGED),
        verify(flac(comments("michi", "TITLE=OLD", "X-MICHI=A")),
            flac(comments("michi", "TITLE=NEW", "X-MICHI=B")),
            MetadataAudioContainer.FLAC))
    @Test fun flacRejectsChangedPicture() = assertEquals(
        MetadataOpaquePreservationAudit.Verdict.Blocked(MetadataOpaquePreservationAudit.Refusal.UNEDITED_OPAQUE_CHANGED),
        verify(flac(comments("michi", "TITLE=OLD")),
            flac(comments("michi", "TITLE=NEW"),s("OTHER")),
            MetadataAudioContainer.FLAC))
    @Test fun flacRejectsChangedVendor() = assertEquals(
        MetadataOpaquePreservationAudit.Verdict.Blocked(MetadataOpaquePreservationAudit.Refusal.UNEDITED_OPAQUE_CHANGED),
        verify(flac(comments("vendor", "TITLE=OLD")),
            flac(comments("changed", "TITLE=NEW")),MetadataAudioContainer.FLAC))
    @Test fun flacRejectsUnknownFieldEdit() = assertEquals(
        MetadataOpaquePreservationAudit.Verdict.Blocked(MetadataOpaquePreservationAudit.Refusal.UNSUPPORTED_EDIT),
        verify(flac(comments("michi", "TITLE=OLD")),
            flac(comments("michi", "TITLE=NEW")),MetadataAudioContainer.FLAC,
            setOf(MetadataField.WEBSITE)))
    @Test fun rejectsUnsupportedFormat() = assertEquals(
        MetadataOpaquePreservationAudit.Verdict.Blocked(MetadataOpaquePreservationAudit.Refusal.WRONG_FORMAT),
        verify(sameId3(),sameId3(),MetadataAudioContainer.MP4_M4A))
    @Test fun rejectsEmptyEdit() = assertEquals(
        MetadataOpaquePreservationAudit.Verdict.Blocked(MetadataOpaquePreservationAudit.Refusal.UNSUPPORTED_EDIT),
        verify(sameId3(),sameId3(),MetadataAudioContainer.ID3_V23,emptySet()))
}
