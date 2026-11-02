package org.michimusic.mobile.playback

import java.util.Locale
import org.michimusic.core.models.SignalFormat
import org.michimusic.core.models.Track

/** Source/file properties only. NEVER substitute decoder PCM/AudioTrack properties. */
object SignalSourcePresentation {
    private enum class Class { LOSSY, LOSSLESS, CONTAINER, UNKNOWN }
    private data class Codec(val label: String, val classification: Class)

    private fun knownCodec(value: String?): Codec? {
        val s = value?.trim()?.lowercase(Locale.ROOT)?.removePrefix("audio/")?.removePrefix("x-") ?: return null
        return when {
            s.isBlank() || s == "unknown" || s == "desconocido" -> null
            s == "mpeg" || s == "mp3" || s == "mpeg3" || s == "x-mpeg" -> Codec("MP3", Class.LOSSY)
            s.contains("adpcm") -> Codec("ADPCM", Class.LOSSY)
            s.contains("flac") -> Codec("FLAC", Class.LOSSLESS)
            s.contains("alac") -> Codec("ALAC", Class.LOSSLESS)
            // WavPack hybrid .wv is potentially lossy without the .wvc correction file.
            // No correction-file verification exists in Mobile: don't promise losslessness.
            s.contains("wavpack") || s == "wv" -> Codec("WavPack", Class.UNKNOWN)
            s.contains("aiff") || s == "aif" -> Codec("AIFF", Class.CONTAINER)
            s == "wav" || s == "wave" || s == "vnd.wave" || s == "x-wav" -> Codec("WAV", Class.CONTAINER)
            s == "raw" || s == "pcm" || s.contains("lpcm") -> Codec("PCM", Class.LOSSLESS)
            s.contains("mp4a-latm") || s == "aac" || s == "aac-adts" -> Codec("AAC", Class.LOSSY)
            s.contains("opus") -> Codec("Opus", Class.LOSSY)
            s.contains("vorbis") -> Codec("Vorbis", Class.LOSSY)
            s == "wma" || s.contains("ms-wma") -> Codec("WMA", Class.LOSSY)
            s == "ogg" || s == "m4a" || s == "mp4" || s == "x-m4a" -> Codec(s.uppercase(Locale.ROOT), Class.CONTAINER)
            else -> null
        }
    }

    fun describe(source: SignalFormat?, track: Track?): String? {
        val fileCodec = knownCodec(track?.format)
        val observedCodec = knownCodec(source?.codec)
        val selected = when {
            // A compressed codec has priority over a container description.
            observedCodec?.classification == Class.LOSSY -> observedCodec
            observedCodec?.classification == Class.LOSSLESS && observedCodec.label != "PCM" -> observedCodec
            // The PCM decoder input is not proof that an originally compressed
            // MP3/AAC file was lossless: preserve the known source codec.
            observedCodec?.label == "PCM" && fileCodec?.classification == Class.LOSSY -> fileCodec
            observedCodec?.label == "PCM" && fileCodec?.label in setOf("WAV", "AIFF") ->
                Codec("${fileCodec!!.label} (PCM)", Class.LOSSLESS)
            // Prefer an identified audio codec to a generic MP4/Ogg/WAV container.
            observedCodec?.classification == Class.CONTAINER &&
                fileCodec?.classification in setOf(Class.LOSSY, Class.LOSSLESS) -> fileCodec
            observedCodec != null -> observedCodec
            else -> fileCodec
        } ?: return null
        val rateHz = (source?.sampleRate ?: 0).takeIf { it in 8_000..768_000 }
            ?: track?.sampleRate?.takeIf { it in 8_000..768_000 }
        val bits = source?.bitDepth?.takeIf { it in 8..64 && selected.classification == Class.LOSSLESS }
        val bps = source?.bitrateBps?.takeIf { it in 8_000..50_000_000 }
            ?: track?.bitrate?.let { raw ->
                when (raw) {
                    in 8..8_000 -> raw * 1000 // Track DTO's bitrate is normally in kb/s
                    in 8_001..50_000_000 -> raw // Some providers expose bits/s
                    else -> null
                }
            }
        val suffix = mutableListOf<String>()
        if (bits != null) suffix += "$bits bit"
        if (rateHz != null) suffix += formatRate(rateHz)
        if (bps != null) {
            val bitrate = if (bps % 1000 == 0) "${bps / 1000}" else
                String.format(Locale.US, "%.1f", bps / 1000.0)
            val note = when (source?.bitrateKind) {
                "PEAK" -> " (pico)"
                "FILE_AVG", "AVERAGE" -> " (promedio)"
                else -> ""
            }
            suffix += "$bitrate kb/s$note"
        }
        return (listOf(selected.label) + suffix).joinToString(" · ")
    }

    private fun formatRate(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000} kHz"
        else String.format(Locale.forLanguageTag("es-CL"), "%.3f", hz / 1000.0)
            .trimEnd('0').trimEnd(',') + " kHz"
}
