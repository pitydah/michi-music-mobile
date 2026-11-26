package org.michimusic.data.metadata

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.jaudiotagger.audio.mp4.Mp4FileReader
import org.jaudiotagger.audio.mp4.Mp4FileWriter
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.mp4.Mp4FieldKey
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4DiscNoField
import org.jaudiotagger.tag.mp4.field.Mp4TrackField
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataCandidateSemanticPolicy
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataFieldReadbackPolicy
import org.michimusic.core.metadata.MetadataFieldReadReport
import org.michimusic.core.metadata.MetadataMp4CovrStage
import org.michimusic.core.metadata.MetadataPhysicalContainer
import org.michimusic.core.metadata.MetadataPrivateBundleStage
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSelectiveAssetStage

/**
 * Private-only M4A staging through the pinned mature JAudiotagger engine plus
 * direct `covr` surgery for artwork resources.
 *
 * The engine never receives a provider descriptor and only runs when scalar
 * diffs exist. Artwork-only saves never invoke it: the container surgery in
 * [MetadataMp4CovrStage] copies `mdat` verbatim and rewrites only `moov`.
 * Source mutation remains the existing journaled exact-byte transport after
 * this candidate passes cold parser, semantic, audio-only, artwork readback and
 * actual referenced-sample preservation gates. The gate hashes the bytes each
 * sample table entry points at, so a rewritten `moov` may move `stco`/`co64`
 * offsets but may not reorder, duplicate or replace sample data.
 */
internal object MetadataM4aPrivateStage {
    private const val MAX_BYTES = 1024L * 1024L * 1024L
    private val scalarKeys = mapOf(
        MetadataField.TITLE to FieldKey.TITLE,
        MetadataField.ARTIST to FieldKey.ARTIST,
        MetadataField.ALBUM to FieldKey.ALBUM,
        MetadataField.ALBUM_ARTIST to FieldKey.ALBUM_ARTIST,
        MetadataField.GENRE to FieldKey.GENRE,
        MetadataField.COMPOSER to FieldKey.COMPOSER,
        MetadataField.RELEASE_DATE to FieldKey.YEAR,
        MetadataField.COMMENT to FieldKey.COMMENT,
        MetadataField.LYRICS to FieldKey.LYRICS,
        MetadataField.GROUPING to FieldKey.GROUPING,
    )
    private data class Composite(
        val number: MetadataField,
        val total: MetadataField,
        val key: FieldKey,
    )
    private val composites = listOf(
        Composite(MetadataField.TRACK_NUMBER, MetadataField.TRACK_TOTAL, FieldKey.TRACK),
        Composite(MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL, FieldKey.DISC_NO),
    )
    private val compositeFields = composites.flatMapTo(linkedSetOf()) {
        listOf(it.number, it.total)
    }
    private val supported = scalarKeys.keys + compositeFields
    private val canonicalNumber = Regex("[1-9][0-9]{0,3}")

    data class Request(
        val sourceSha256: String,
        val expectedBytes: Long,
        /** Reviewed scalar diffs. `null` for an artwork-only M4A save. */
        val review: MetadataReview.Ready? = null,
        /** Addressed `covr` edits, applied after the scalar stage. */
        val resourceEdits: List<MetadataSelectiveAssetStage.Edit> = emptyList(),
        /** Snapshot observed on the private source before any staging. */
        val resources: MetadataArtworkLyricsProbe.Snapshot? = null,
    )

    data class Receipt(
        val candidateBytes: Long,
        val candidateSha256: String,
        val audioPayloadBytes: Long,
        val audioPayloadSha256: String,
        val samples: MetadataM4aAudioOnlyInspector.SampleEvidence,
        val resources: MetadataArtworkLyricsProbe.Snapshot? = null,
    )

    fun stage(source: File, destination: File, request: Request): Receipt {
        val review = request.review
        val diffs = review?.diffs.orEmpty()
        require(diffs.isNotEmpty() || request.resourceEdits.isNotEmpty()) {
            "EMPTY_M4A_REQUEST"
        }
        require(request.sourceSha256.matches(Regex("[a-f0-9]{64}")))
        require(request.expectedBytes in 24..MAX_BYTES)
        if (review != null) {
            require(diffs.all { it.field in supported }) {
                "UNSUPPORTED_M4A_FIELD"
            }
            require(
                MetadataEditPreview.review(
                    review.original,
                    diffs.map { diff ->
                        if (diff.after.isEmpty())
                            MetadataChange.Clear(diff.field)
                        else MetadataChange.Replace(diff.field, diff.after)
                    },
                ) == review,
            ) { "REVIEW_NOT_REPRODUCIBLE" }
        }
        require(request.resourceEdits.none {
            it is MetadataSelectiveAssetStage.Edit.DescribedText
        }) { "UNSUPPORTED_M4A_RESOURCE" }
        val sourcePath = source.toPath()
        val destinationPath = destination.toPath()
        require(Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            "SOURCE_NOT_REGULAR"
        }
        require(!Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS)) {
            "DESTINATION_EXISTS"
        }
        require(source.canonicalFile.parentFile == destination.canonicalFile.parentFile) {
            "PRIVATE_SIBLING_REQUIRED"
        }
        require(source.length() == request.expectedBytes && sha(source) == request.sourceSha256) {
            "SOURCE_FINGERPRINT_MISMATCH"
        }

        val beforeState = FileChannel.open(
            sourcePath,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val report = MetadataColdFieldParser.observe(channel)
            require(report.container == MetadataPhysicalContainer.MP4_M4A) {
                "NOT_MP4_M4A"
            }
            diffs.forEach { diff ->
                val observed = report.observation(diff.field)
                require(
                    (observed is MetadataFieldObservation.Present &&
                        observed.values == diff.before) ||
                        (observed == MetadataFieldObservation.VerifiedAbsent &&
                            diff.before.isEmpty()),
                ) { "SOURCE_REVIEW_MISMATCH" }
            }
            report to (MetadataM4aAudioOnlyInspector.inspect(channel) as?
                MetadataM4aAudioOnlyInspector.Result.AudioOnly
                ?: error("SOURCE_NOT_AUDIO_ONLY"))
        }
        val beforeReport = beforeState.first
        val beforeAudio = beforeState.second
        val beforeResources = request.resources
            ?: if (request.resourceEdits.isEmpty()) null
            else MetadataPrivateBundleStage.observeFile(source)
        require(beforeResources == null ||
            beforeResources.format == MetadataArtworkLyricsProbe.Format.MP4) {
            "SOURCE_NOT_MP4_M4A"
        }

        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        val covrTemporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.covr")
        var candidate = temporary
        var artworkAfter: MetadataArtworkLyricsProbe.Snapshot? = null
        try {
            if (diffs.isEmpty()) {
                // Artwork-only: the container surgery is the single writer and the
                // private source is read without any engine rewrite.
                artworkAfter = MetadataMp4CovrStage.stage(
                    source = source,
                    destination = temporary,
                    expectedBytes = request.expectedBytes,
                    expectedSourceSha256 = request.sourceSha256,
                    observed = beforeResources,
                    edits = request.resourceEdits,
                ).resources
            } else {
                FileInputStream(source).use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output, 64 * 1024)
                        output.fd.sync()
                    }
                }
                applyDiffs(temporary, requireNotNull(review), beforeReport)
                if (request.resourceEdits.isNotEmpty()) {
                    artworkAfter = MetadataMp4CovrStage.stage(
                        source = temporary,
                        destination = covrTemporary,
                        expectedBytes = temporary.length(),
                        expectedSourceSha256 = sha(temporary),
                        observed = beforeResources,
                        edits = request.resourceEdits,
                    ).resources
                    candidate = covrTemporary
                }
            }

            val candidateBytes = candidate.length()
            require(candidateBytes in 24..MAX_BYTES) { "INVALID_CANDIDATE_SIZE" }
            val candidateSha = sha(candidate)
            val afterAudio = FileChannel.open(
                candidate.toPath(),
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val report = MetadataColdFieldParser.observe(channel)
                if (review != null) {
                    val readback = MetadataFieldReadbackPolicy.check(review, report)
                    require(readback == MetadataFieldReadbackPolicy.Result.VERIFIED) {
                        "CANDIDATE_READBACK_MISMATCH"
                    }
                }
                MetadataM4aAudioOnlyInspector.inspect(channel) as?
                    MetadataM4aAudioOnlyInspector.Result.AudioOnly
                    ?: error("CANDIDATE_NOT_AUDIO_ONLY")
            }
            // The candidate must reference the same actual sample bytes in the
            // same decode order, with the same effective codec configuration,
            // timeline, edit list and track identity. Chunk offsets may move.
            require(MetadataM4aAudioOnlyInspector.equivalent(
                beforeAudio.evidence,
                afterAudio.evidence,
            )) { "MEDIA_PAYLOAD_CHANGED" }
            verifyArtwork(candidate, beforeResources, artworkAfter)

            if (review != null) {
                val intent = MetadataCommitIntentManifest.create(
                    transactionId = UUID.randomUUID().toString(),
                    review = review,
                    provider = MetadataDocumentProvider.MEDIASTORE,
                    container = MetadataAudioContainer.MP4_M4A,
                    originalBytes = request.expectedBytes,
                    originalSha256 = request.sourceSha256,
                    candidateBytes = candidateBytes,
                    candidateSha256 = candidateSha,
                )
                val originalReport = FileChannel.open(
                    sourcePath,
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS,
                ).use(MetadataColdFieldParser::observe)
                val candidateReport = FileChannel.open(
                    candidate.toPath(),
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS,
                ).use(MetadataColdFieldParser::observe)
                require(MetadataCandidateSemanticPolicy.compare(
                    intent,
                    originalReport,
                    candidateReport,
                ) is MetadataCandidateSemanticPolicy.Verdict.ObservedFieldsMatch) {
                    "CANDIDATE_SEMANTIC_MISMATCH"
                }
            }
            Files.move(candidate.toPath(), destinationPath)
            return Receipt(
                candidateBytes,
                candidateSha,
                afterAudio.evidence.mediaPayloadBytes,
                afterAudio.evidence.mediaPayloadSha256,
                afterAudio.evidence.samples,
                artworkAfter,
            )
        } finally {
            temporary.delete()
            covrTemporary.delete()
        }
    }

    /** Scalar staging through the pinned engine, on the private copy only. */
    private fun applyDiffs(
        temporary: File,
        review: MetadataReview.Ready,
        beforeReport: MetadataFieldReadReport,
    ) {
        TagOptionSingleton.getInstance().isAndroid = true
        TagOptionSingleton.getInstance().isWriteMp4GenresAsText = true
        val audioFile = Mp4FileReader().read(temporary)
        val tag = requireNotNull(audioFile.tag) { "M4A_TAG_UNAVAILABLE" }
        val changes = review.diffs.associateBy { it.field }
        fun original(field: MetadataField): List<String> =
            (beforeReport.observation(field) as?
                MetadataFieldObservation.Present)?.values.orEmpty()
        fun target(field: MetadataField): String? {
            val change = changes[field] ?: return original(field).firstOrNull()
            require(change.after.size <= 1) { "M4A_SINGLE_VALUE_ONLY" }
            return change.after.singleOrNull()
        }
        val applied = mutableSetOf<FieldKey>()
        for (diff in review.diffs) {
            require(diff.after.size <= 1) { "M4A_SINGLE_VALUE_ONLY" }
            val composite = composites.firstOrNull {
                it.number == diff.field || it.total == diff.field
            }
            if (composite != null) {
                if (!applied.add(composite.key)) continue
                val number = target(composite.number)
                val total = target(composite.total)
                require(number == null || canonicalNumber.matches(number)) {
                    "M4A_INVALID_NUMBER"
                }
                require(total == null || canonicalNumber.matches(total)) {
                    "M4A_INVALID_NUMBER"
                }
                require(number != null || total == null) {
                    "M4A_INVALID_NUMBER_PAIR"
                }
                if (number == null) {
                    runCatching { tag.deleteField(composite.key) }.getOrElse {
                        throw IllegalArgumentException(
                            "M4A_FIELD_FAILED:${diff.field}", it)
                    }
                } else {
                    runCatching {
                        val n = number.toInt()
                        (tag as Mp4Tag).deleteField(
                            if (composite.key == FieldKey.TRACK) Mp4FieldKey.TRACK
                            else Mp4FieldKey.DISCNUMBER,
                        )
                        val t = total?.toInt() ?: 0
                        if (composite.key == FieldKey.TRACK)
                            tag.setField(Mp4TrackField(n, t))
                        else tag.setField(Mp4DiscNoField(n, t))
                    }.getOrElse {
                        throw IllegalArgumentException(
                            "M4A_FIELD_FAILED:${diff.field}", it)
                    }
                }
            } else {
                val key = scalarKeys.getValue(diff.field)
                val value = diff.after.singleOrNull().orEmpty()
                if (value.isEmpty()) {
                    runCatching { tag.deleteField(key) }.getOrElse {
                        throw IllegalArgumentException(
                            "M4A_FIELD_FAILED:${diff.field}", it)
                    }
                } else {
                    runCatching { tag.setField(key, value) }.getOrElse {
                        throw IllegalArgumentException(
                            "M4A_FIELD_FAILED:${diff.field}", it)
                    }
                }
            }
        }
        Mp4FileWriter().write(audioFile)
        FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use {
            it.force(true)
        }
    }

    /**
     * Artwork that nobody selected must survive byte-exactly. When the source
     * already carried images and no resource edit was requested, the staged
     * candidate has to expose the identical list.
     */
    private fun verifyArtwork(
        candidate: File,
        before: MetadataArtworkLyricsProbe.Snapshot?,
        expected: MetadataArtworkLyricsProbe.Snapshot?,
    ) {
        val target = expected ?: before ?: return
        // When an edit produced an explicit expected list (including an empty
        // list after removing everything), it must be verified. Only a scalar
        // rewrite with no selected artwork and no original artwork can skip.
        if (expected == null && (before == null || before.artworks.isEmpty())) return
        val observation = FileChannel.open(
            candidate.toPath(),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { MetadataArtworkLyricsProbe.inspectChannel(it) }
            as? MetadataArtworkLyricsProbe.ChannelResult.Ready
            ?: error("CANDIDATE_ARTWORK_UNREADABLE")
        val actual = observation.observation.snapshot
        require(actual.format == MetadataArtworkLyricsProbe.Format.MP4) {
            "CANDIDATE_ARTWORK_UNREADABLE"
        }
        require(actual.artworks.size == target.artworks.size) {
            "CANDIDATE_ARTWORK_CHANGED"
        }
        actual.artworks.zip(target.artworks).forEach { (seen, wanted) ->
            require(seen.info.mime == wanted.info.mime &&
                seen.info.sha256 == wanted.info.sha256 &&
                seen.info.bytes == wanted.info.bytes &&
                seen.image.contentEquals(wanted.image)) {
                "CANDIDATE_ARTWORK_CHANGED"
            }
        }
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }
}
