package org.michimusic.mobile.ui.seeker

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteOrder
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.michimusic.mobile.ui.theme.WaveformEnvelope

/**
 * Offline, read-only, bounded visual analysis of the same local asset referenced by
 * the Track. Does not touch AudioController, Media3 playback, Signal Truth or network.
 * Only successfully decoded, stable local assets can enter the in-memory LRU.
 */
internal object LocalWaveformDecoder {
    private data class Fingerprint(val path: String, val size: Long, val modified: Long)
    private const val CACHE_ENTRIES = 24
    private const val DECODER_TIMEOUT_US = 10_000L
    private const val MAX_WALL_MS = 20_000L
    private const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
    private val gate = Mutex() // never decode two songs concurrently
    private val cache = object : LinkedHashMap<Fingerprint, List<Float>>(CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Fingerprint, List<Float>>?): Boolean =
            size > CACHE_ENTRIES
    }

    suspend fun load(
        context: Context,
        filepath: String,
        durationMs: Long,
    ): List<Float>? = withContext(Dispatchers.IO) {
        gate.withLock {
            currentCoroutineContext().ensureActive()
            val uri = when {
                filepath.startsWith("content://", ignoreCase = true) -> Uri.parse(filepath)
                filepath.startsWith("file:///", ignoreCase = true) -> Uri.parse(filepath)
                filepath.startsWith("/") -> Uri.fromFile(File(filepath))
                else -> return@withLock null
            }
            val fingerprint = assetFingerprint(context, uri, filepath)
            if (fingerprint != null) {
                synchronized(cache) { cache[fingerprint] }?.let { return@withLock it }
            }
            // Fail closed: media may be revoked/unavailable or codecs may be unsupported.
            // Cancellation must propagate so changing track/style frees the decoder.
            val amplitudes = try {
                decode(context, uri, durationMs)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            // A SAF provider or on-disk asset may change while the decoder reads.
            // Never cache frames under an obsolete size/mtime identity.
            if (fingerprint != null && amplitudes != null) {
                val after = assetFingerprint(context, uri, filepath)
                if (after == null || !stableWaveformAsset(
                        fingerprint.size, fingerprint.modified, after.size, after.modified
                    )) return@withLock null
                synchronized(cache) { cache[fingerprint] = amplitudes }
            }
            amplitudes
        }
    }

    /** Cache only when size and last-modified are actually reported. */
    private fun assetFingerprint(context: Context, uri: Uri, filepath: String): Fingerprint? {
        if (uri.scheme == "file" || filepath.startsWith("/")) {
            val file = File(uri.path ?: filepath)
            val length = file.length()
            val modified = file.lastModified()
            return if (length > 0L && modified > 0L) Fingerprint(filepath, length, modified) else null
        }
        val columns = arrayOf(
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        return try {
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                fun getLong(name: String): Long {
                    val index = cursor.getColumnIndex(name)
                    return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
                }
                val size = getLong(OpenableColumns.SIZE)
                // MediaStore DATE_MODIFIED is in seconds, DocumentsContract in ms.
                val modified = getLong(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    .takeIf { it > 0L } ?: getLong(MediaStore.MediaColumns.DATE_MODIFIED)
                if (size > 0L && modified > 0L) Fingerprint(filepath, size, modified) else null
            }
        } catch (_: Exception) {
            null // Some SAF providers reject unknown projection columns: no cache.
        }
    }

    private suspend fun decode(context: Context, uri: Uri, durationMs: Long): List<Float>? {
        val extractor = MediaExtractor()
        try {
            // Context overload respects SAF/content:// grants without copying the file.
            extractor.setDataSource(context, uri, emptyMap())
            var audioIndex = -1
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) { audioIndex = index; break }
            }
            if (audioIndex < 0) return null
            extractor.selectTrack(audioIndex)
            val inputFormat = extractor.getTrackFormat(audioIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (channels !in 1..8) return null
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            else if (durationMs in 1L..720_000L) durationMs * 1_000L else 0L
            val frames = waveformFrameBudget(durationUs, sampleRate) ?: return null
            val envelope = WaveformEnvelope(frames, channels, bins = 256)
            val codec = MediaCodec.createDecoderByType(mime)
            var started = false
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                started = true
                var inputEnded = false
                var outputEnded = false
                var decodedFrames = 0L
                val bufferInfo = MediaCodec.BufferInfo()
                var outputChannels = channels
                var outputEncoding = AudioFormat.ENCODING_PCM_16BIT
                val startMs = SystemClock.elapsedRealtime()
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    if (SystemClock.elapsedRealtime() - startMs > MAX_WALL_MS) return null
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(DECODER_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return null
                            inputBuffer.clear()
                            val count = extractor.readSampleData(inputBuffer, 0)
                            if (count < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, count,
                                    extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DECODER_TIMEOUT_US)
                    when (outputIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            outputEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT
                            // A remixed channel layout would invalidate interleaved PCM grouping.
                            if (outputChannels != channels) return null
                        }
                        in 0..Int.MAX_VALUE -> {
                            try {
                                if (bufferInfo.size > 0) {
                                    if (bufferInfo.size > MAX_OUTPUT_BYTES) return null
                                    val bytesPerSample = when (outputEncoding) {
                                        AudioFormat.ENCODING_PCM_8BIT -> 1
                                        AudioFormat.ENCODING_PCM_16BIT -> 2
                                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                                        AudioFormat.ENCODING_PCM_FLOAT,
                                        AudioFormat.ENCODING_PCM_32BIT -> 4
                                        else -> return null
                                    }
                                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: return null
                                    if (!validPcmOutputRange(
                                            bufferInfo.offset, bufferInfo.size,
                                            outputBuffer.capacity(), bytesPerSample * channels
                                        )) return null
                                    outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    // Bounded allocation (<= 2048 frames x 8 channels), regardless
                                    // of an OEM decoder's reported output buffer size. Decoding
                                    // remains cancellable *inside* a large output buffer.
                                    val frameCount = completePcmFrames(
                                        bufferInfo.size, bytesPerSample, channels,
                                    ) ?: return null
                                    var remainingFrames = frameCount
                                    while (remainingFrames > 0) {
                                        currentCoroutineContext().ensureActive()
                                        val sampleCount = boundedWaveformChunkSamples(remainingFrames, channels)
                                        if (sampleCount == 0) return null
                                        val samples = FloatArray(sampleCount)
                                        for (i in samples.indices) {
                                            samples[i] = when (outputEncoding) {
                                                AudioFormat.ENCODING_PCM_8BIT -> normalizePcm8(outputBuffer.get())
                                                AudioFormat.ENCODING_PCM_16BIT -> outputBuffer.short / 32768f
                                                AudioFormat.ENCODING_PCM_24BIT_PACKED -> readPcm24Sample(outputBuffer)
                                                AudioFormat.ENCODING_PCM_32BIT -> normalizePcm32(outputBuffer.int)
                                                else -> outputBuffer.float
                                            }
                                        }
                                        envelope.acceptInterleaved(samples)
                                        val consumedFrames = sampleCount / channels
                                        decodedFrames += consumedFrames
                                        if (decodedFrames > 40_000_000L) return null
                                        remainingFrames -= consumedFrames
                                    }
                                }
                                outputEnded = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            } finally {
                                codec.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                }
                return envelope.finish()
            } finally {
                if (started) runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }
}
