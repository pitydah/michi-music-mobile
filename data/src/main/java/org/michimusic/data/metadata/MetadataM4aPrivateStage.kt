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
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataCandidateSemanticPolicy
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataFieldReadbackPolicy
import org.michimusic.core.metadata.MetadataPhysicalContainer
import org.michimusic.core.metadata.MetadataReview

/**
 * Private-only M4A scalar staging through the pinned mature JAudiotagger engine.
 *
 * The engine never receives a provider descriptor. Source mutation remains the
 * existing journaled exact-byte transport after this candidate passes cold
 * parser, semantic, audio-only and exact `mdat` preservation gates.
 */
internal object MetadataM4aPrivateStage {
    private const val MAX_BYTES = 1024L * 1024L * 1024L

    data class Request(
        val sourceSha256: String,
        val expectedBytes: Long,
        val review: MetadataReview.Ready,
    )

    data class Receipt(
        val candidateBytes: Long,
        val candidateSha256: String,
        val audioPayloadBytes: Long,
        val audioPayloadSha256: String,
    )

    fun stage(source: File, destination: File, request: Request): Receipt {
        require(request.sourceSha256.matches(Regex("[a-f0-9]{64}")))
        require(request.expectedBytes in 24..MAX_BYTES)
        require(request.review.diffs.isNotEmpty())
        require(request.review.diffs.all { it.field == MetadataField.TITLE })
        require(
            MetadataEditPreview.review(
                request.review.original,
                request.review.diffs.map { diff ->
                    org.michimusic.core.metadata.MetadataChange.Replace(diff.field, diff.after)
                },
            ) == request.review,
        ) { "REVIEW_NOT_REPRODUCIBLE" }
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

        val beforeAudio = FileChannel.open(
            sourcePath,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val report = MetadataColdFieldParser.observe(channel)
            require(report.container == MetadataPhysicalContainer.MP4_M4A) {
                "NOT_MP4_M4A"
            }
            request.review.diffs.forEach { diff ->
                val observed = report.observation(diff.field)
                require(
                    observed is MetadataFieldObservation.Present &&
                        observed.values == diff.before,
                ) { "SOURCE_REVIEW_MISMATCH" }
            }
            MetadataM4aAudioOnlyInspector.inspect(channel) as?
                MetadataM4aAudioOnlyInspector.Result.AudioOnly
                ?: error("SOURCE_NOT_AUDIO_ONLY")
        }

        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            TagOptionSingleton.getInstance().isAndroid = true
            val audioFile = Mp4FileReader().read(temporary)
            request.review.diffs.forEach { diff ->
                require(diff.after.size == 1) { "M4A_SINGLE_VALUE_ONLY" }
                audioFile.tag.setField(FieldKey.TITLE, diff.after.single())
            }
            Mp4FileWriter().write(audioFile)
            FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use {
                it.force(true)
            }

            val candidateBytes = temporary.length()
            require(candidateBytes in 24..MAX_BYTES) { "INVALID_CANDIDATE_SIZE" }
            val candidateSha = sha(temporary)
            val afterAudio = FileChannel.open(
                temporary.toPath(),
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val report = MetadataColdFieldParser.observe(channel)
                require(MetadataFieldReadbackPolicy.check(request.review, report) ==
                    MetadataFieldReadbackPolicy.Result.VERIFIED) {
                    "CANDIDATE_READBACK_MISMATCH"
                }
                MetadataM4aAudioOnlyInspector.inspect(channel) as?
                    MetadataM4aAudioOnlyInspector.Result.AudioOnly
                    ?: error("CANDIDATE_NOT_AUDIO_ONLY")
            }
            require(beforeAudio.evidence.mediaPayloadBytes ==
                afterAudio.evidence.mediaPayloadBytes &&
                beforeAudio.evidence.mediaPayloadSha256 ==
                afterAudio.evidence.mediaPayloadSha256) {
                "MEDIA_PAYLOAD_CHANGED"
            }

            val intent = MetadataCommitIntentManifest.create(
                transactionId = UUID.randomUUID().toString(),
                review = request.review,
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
                temporary.toPath(),
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
            Files.move(temporary.toPath(), destinationPath)
            return Receipt(
                candidateBytes,
                candidateSha,
                afterAudio.evidence.mediaPayloadBytes,
                afterAudio.evidence.mediaPayloadSha256,
            )
        } finally {
            temporary.delete()
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
