package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** To be run by OpenCode. Synthetic fixtures prove parser contracts, not Android SAF. */
class MetadataColdFieldParserTest {
    private fun big(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(),
        (n ushr 8).toByte(), n.toByte())
    private fun little(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte(),
        (n ushr 16).toByte(), (n ushr 24).toByte())
    private fun sync(n: Int) = byteArrayOf((n ushr 21 and 127).toByte(),
        (n ushr 14 and 127).toByte(), (n ushr 7 and 127).toByte(), (n and 127).toByte())
    private fun id3Frame(code: String, value: String, flags: Int = 0, v4: Boolean = false): ByteArray {
        val payload = byteArrayOf(if (v4) 3 else 0) +
            value.toByteArray(if (v4) Charsets.UTF_8 else Charsets.ISO_8859_1)
        val size = if (v4) sync(payload.size) else big(payload.size)
        return code.toByteArray(Charsets.US_ASCII) + size + byteArrayOf(0, flags.toByte()) + payload
    }
    private fun id3(version: Int = 4, vararg frames: ByteArray, headerFlags: Int = 0): ByteArray {
        val contents = frames.fold(ByteArray(0)) { acc, item -> acc + item }
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            version.toByte(), 0, headerFlags.toByte()) + sync(contents.size) + contents + byteArrayOf(0,1,2)
    }
    private fun block(type: Int, bytes: ByteArray, last: Boolean) =
        byteArrayOf((type or (if (last) 128 else 0)).toByte(),
            (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
    private fun comments(vararg tags: String): ByteArray {
        val out = ByteArrayOutputStream(); val vendor = "Michi".toByteArray(Charsets.UTF_8)
        out.write(little(vendor.size)); out.write(vendor); out.write(little(tags.size))
        for (tag in tags) {
            val bytes = tag.toByteArray(Charsets.UTF_8)
            out.write(little(bytes.size)); out.write(bytes)
        }
        return out.toByteArray()
    }
    private fun streamInfo(): ByteArray = ByteArray(34).also { b ->
        b[0]=0x10; b[1]=0; b[2]=0x10; b[3]=0
        // STREAMINFO 44.1 kHz: bits [10..12] encode 20-bit sample rate.
        b[10]=0x0a; b[11]=0xc4.toByte(); b[12]=0x40
    }
    private fun flac(vararg tags: String): ByteArray =
        "fLaC".toByteArray() + block(0, streamInfo(), false) +
            block(4, comments(*tags), true) + byteArrayOf(3,4,5,6)
    private fun observe(data: ByteArray): MetadataFieldReadReport {
        val temp = Files.createTempFile("michi-me04-read", ".bin")
        try {
            Files.write(temp, data)
            return FileChannel.open(temp, StandardOpenOption.READ).use(MetadataColdFieldParser::observe)
        } finally { Files.deleteIfExists(temp) }
    }
    private fun present(report: MetadataFieldReadReport, field: MetadataField): List<String> =
        (report.observation(field) as MetadataFieldObservation.Present).values

    @Test fun flacProvesMissingFieldsRatherThanGuessing() {
        val report = observe(flac("TITLE=Noches", "ARTIST=Gato"))
        assertEquals(MetadataPhysicalContainer.FLAC, report.container)
        assertEquals(listOf("Noches"), present(report, MetadataField.TITLE))
        assertEquals(MetadataFieldObservation.VerifiedAbsent, report.observation(MetadataField.COMPOSER))
        assertEquals(MetadataFieldObservation.VerifiedAbsent, report.observation(MetadataField.GENRE))
    }
    @Test fun flacGenreSemicolonIsSingleValue() {
        val r=observe(flac("GENRE=Jazz; Fusion", "GENRE=Bebop"))
        assertEquals(listOf("Jazz; Fusion", "Bebop"), present(r, MetadataField.GENRE))
    }
    @Test fun flacReadsExtendedCreditsAndIdsWithoutInventingThem() {
        val r=observe(flac("SUBTITLE=Live", "LABEL=Independent", "CATALOGNUMBER=MM-101",
            "ISRC=CLABC2600123", "ORIGINALDATE=1980", "BPM=128", "INITIALKEY=Am"))
        assertEquals(listOf("MM-101"),present(r,MetadataField.CATALOG_NUMBER))
        assertEquals(listOf("CLABC2600123"),present(r,MetadataField.ISRC))
        assertEquals(listOf("1980"),present(r,MetadataField.ORIGINAL_RELEASE_DATE))
        assertEquals(listOf("Am"),present(r,MetadataField.MUSICAL_KEY))
    }
    @Test fun flacExpandedReadObservesClassicCreditsAndEdition() {
        val result=observe(flac("ORCHESTRA=Orquesta", "ORCHESTRA=Ensamble", "RECORDINGDATE=1984",
            "ALBUMSUBTITLE=Edición revisada", "MOVEMENTNUMBER=2", "WORK=Sinfonía"))
        assertEquals(listOf("Orquesta", "Ensamble"), present(result, MetadataField.ORCHESTRA))
        assertEquals(listOf("1984"), present(result, MetadataField.RECORDING_DATE))
        assertEquals(listOf("2"), present(result, MetadataField.MOVEMENT_NUMBER))
        assertEquals(listOf("Sinfonía"), present(result, MetadataField.WORK))
    }
    @Test fun id3NeverProvesPhysicallyUnsupportedOrchestraAbsent() {
        val result=observe(id3(4,id3Frame("TIT2", "Song", v4=true)))
        assertFalse(MetadataField.ORCHESTRA in result.verifiedFields)
        assertTrue(result.observation(MetadataField.ORCHESTRA) is MetadataFieldObservation.Unverifiable)
        assertFalse(MetadataField.CATALOG_NUMBER in result.verifiedFields)
    }
    @Test fun flacSingleValueCreditsRejectDuplicateWithoutClobbering() {
        val result=observe(flac("EDITION=First", "EDITION=Second"))
        assertTrue(result.observation(MetadataField.EDITION) is MetadataFieldObservation.Conflicted)
    }
    @Test fun flacAlbumArtistAliasConflictIsBlocked() {
        val r=observe(flac("ALBUMARTIST=A", "ALBUM ARTIST=B"))
        assertTrue(r.observation(MetadataField.ALBUM_ARTIST) is MetadataFieldObservation.Conflicted)
        assertFalse(MetadataField.ALBUM_ARTIST in r.verifiedFields)
    }
    @Test fun flacRepeatedSingleTitleIsConflictEvenIfIdentical() {
        val r=observe(flac("TITLE=A", "TITLE=A"))
        assertTrue(r.observation(MetadataField.TITLE) is MetadataFieldObservation.Conflicted)
    }
    @Test fun flacPairedTrackNumbersAreIndependent() {
        val r=observe(flac("TRACKNUMBER=03", "TRACKTOTAL=12", "DISCNUMBER=1"))
        assertEquals(listOf("3"), present(r, MetadataField.TRACK_NUMBER))
        assertEquals(listOf("12"), present(r, MetadataField.TRACK_TOTAL))
        assertEquals(MetadataFieldObservation.VerifiedAbsent, r.observation(MetadataField.DISC_TOTAL))
    }
    @Test fun flacMixedFractionAndSeparateTotalConflict() {
        val r=observe(flac("TRACKNUMBER=3/12", "TRACKTOTAL=13"))
        assertTrue(r.observation(MetadataField.TRACK_TOTAL) is MetadataFieldObservation.Conflicted)
    }
    @Test fun flacInvalidStreamInfoNeverProvesAbsence() {
        val b="fLaC".toByteArray() + block(0,ByteArray(34),false) +
            block(4,comments("TITLE=Invalid"),true)
        assertTrue(observe(b).verifiedFields.isEmpty())
    }
    @Test fun flacUtf8MalformedCannotProveAbsence() {
        val b=flac("TITLE=Rød").copyOf()
        val i=b.indexOf('ø'.toString().toByteArray(Charsets.UTF_8)[0])
        assertTrue(i > 0)
        b[i]=0xC3.toByte(); b[i+1]=0x28
        assertTrue(observe(b).observation(MetadataField.TITLE) is MetadataFieldObservation.Unverifiable)
    }
    @Test fun flacPictureBeforeCommentsDoesNotConsumePicturePayload() {
        val b="fLaC".toByteArray() + block(0,streamInfo(),false) +
            block(6,ByteArray(8192),false) + block(4,comments("TITLE=Preserved"),true)
        assertEquals(listOf("Preserved"),present(observe(b),MetadataField.TITLE))
    }
    @Test fun duplicateVorbisBlocksInvalidateWholeReport() {
        val b="fLaC".toByteArray() + block(0,streamInfo(),false) +
            block(4,comments("TITLE=A"),false) + block(4,comments("TITLE=B"),true)
        assertTrue(observe(b).observation(MetadataField.TITLE) is MetadataFieldObservation.Unverifiable)
    }
    @Test fun unsupportedContainerHasNoVerifiedFields() {
        val r=observe("RIFF...........".toByteArray())
        assertTrue(r.verifiedFields.isEmpty())
    }
    @Test fun id3v24ProvesMissingAlbum() {
        val r=observe(id3(4,id3Frame("TIT2","Song",v4=true)))
        assertEquals(MetadataPhysicalContainer.ID3_V24,r.container)
        assertEquals(MetadataFieldObservation.VerifiedAbsent,r.observation(MetadataField.ALBUM))
        assertEquals(listOf("Song"),present(r,MetadataField.TITLE))
    }
    @Test fun id3v23ParsesTrackAndTotalWithoutCoercingGenre() {
        val r=observe(id3(3,id3Frame("TRCK","03/12"),id3Frame("TCON","Jazz; Fusion")))
        assertEquals(listOf("3"),present(r,MetadataField.TRACK_NUMBER))
        assertEquals(listOf("12"),present(r,MetadataField.TRACK_TOTAL))
        assertEquals(listOf("Jazz; Fusion"),present(r,MetadataField.GENRE))
    }
    @Test fun id3v24KeepsArtistsSeparatedWithoutSplittingSemicolons() {
        val r=observe(id3(4,id3Frame("TPE1","Jazz; Fusion\u0000Artist B",v4=true)))
        assertEquals(listOf("Jazz; Fusion","Artist B"),present(r,MetadataField.ARTIST))
    }
    @Test fun id3v24ReadsPhysicalIsrcAndOriginalDate() {
        val r=observe(id3(4,id3Frame("TSRC","CLABC2600123",v4=true),
            id3Frame("TDOR","1980",v4=true), id3Frame("TIT3","Live",v4=true)))
        assertEquals(listOf("CLABC2600123"),present(r,MetadataField.ISRC))
        assertEquals(listOf("1980"),present(r,MetadataField.ORIGINAL_RELEASE_DATE))
        assertEquals(listOf("Live"),present(r,MetadataField.SUBTITLE))
    }
    @Test fun id3v23VersionSpecificDateIsNotConfusedWithV24() {
        val r=observe(id3(3,id3Frame("TORY","1975")))
        assertEquals(listOf("1975"),present(r,MetadataField.ORIGINAL_RELEASE_DATE))
        val wrong=observe(id3(3,id3Frame("TDOR","1975")))
        assertFalse(MetadataField.ORIGINAL_RELEASE_DATE in wrong.verifiedFields)
    }
    @Test fun id3DuplicateTitleIsConflicted() {
        val r=observe(id3(4,id3Frame("TIT2","A",v4=true),id3Frame("TIT2","B",v4=true)))
        assertTrue(r.observation(MetadataField.TITLE) is MetadataFieldObservation.Conflicted)
    }
    @Test fun unsupportedFrameFlagsDoNotBecomeAbsence() {
        val r=observe(id3(4,id3Frame("TIT2","Hidden",flags=4,v4=true)))
        assertTrue(r.observation(MetadataField.TITLE) is MetadataFieldObservation.Unverifiable)
    }
    @Test fun unsupportedGlobalUnsyncCannotProveOtherFieldsAbsent() {
        val r=observe(id3(4,id3Frame("TIT2","A",v4=true),headerFlags=0x80))
        assertTrue(r.verifiedFields.isEmpty())
    }
    @Test fun id3v23SupplementalDateCannotBeDeclaredAbsent() {
        val raw="TDAT".toByteArray()+big(5)+byteArrayOf(0,0)+byteArrayOf(0,1,0,2,0)
        val r=observe(id3(3,raw))
        assertFalse(MetadataField.RELEASE_DATE in r.verifiedFields)
        assertTrue(MetadataField.TITLE in r.verifiedFields)
    }
    @Test fun id3GenreNumericCodesNotInvented() {
        val r=observe(id3(3,id3Frame("TCON","(17)")))
        assertTrue(r.observation(MetadataField.GENRE) is MetadataFieldObservation.Unverifiable)
    }
    @Test fun malformedId3FrameLengthBlocksWholeReport() {
        val r=observe(id3(3, "TIT2".toByteArray() + big(100000) + byteArrayOf(0,0,0,1)))
        assertTrue(r.verifiedFields.isEmpty())
    }
    @Test fun truncatedFlacCannotAssertAbsence() {
        val r=observe("fLaC".toByteArray() + byteArrayOf(0,0,0,34) + ByteArray(5) + byteArrayOf(0,1,2))
        assertTrue(r.verifiedFields.isEmpty())
    }
    @Test fun unknownArtAndPrivateFramesDoNotInvalidateSimpleTitle() {
        val raw=byteArrayOf(0,0,1,2,3)
        val apic="APIC".toByteArray()+sync(raw.size)+byteArrayOf(0,0)+raw
        val r=observe(id3(4,apic,id3Frame("TIT2","Keep",v4=true)))
        assertEquals(listOf("Keep"),present(r,MetadataField.TITLE))
    }
}
