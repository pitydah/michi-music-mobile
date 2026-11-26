package org.michimusic.mobile.ui.components

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream

/**
 * ME-10F. No network, no thumbnail scraping, no output stream. Reads ONLY
 * embedded metadata header(s) after an explicit gesture in an open local editor.
 * Source URI is owned by the editor's already-verified local-track entrypoint.
 * Never marks the result as a new write authorization or persists image bytes.
 */
internal object MetadataArtLyricsOnDemandReader {
    sealed interface Outcome {
        data class Ready(val snapshot: MetadataArtworkLyricsProbe.Snapshot):Outcome
        data class Blocked(val label:String):Outcome
    }
    suspend fun inspect(resolver: ContentResolver, sourceUri: String): Outcome =
        withContext(Dispatchers.IO) {
            if (!sourceUri.startsWith("content://")) return@withContext Outcome.Blocked("Origen no compatible")
            try {
                val uri = Uri.parse(sourceUri)
                val input = resolver.openInputStream(uri)
                    ?: return@withContext Outcome.Blocked("El proveedor no permite leer este documento")
                input.use { stream ->
                    val header = readExact(stream,4) ?: return@use Outcome.Blocked("Encabezado incompleto")
                    val magic = String(header, Charsets.US_ASCII)
                    // ISO base media files (M4A/MP4) carry their `covr` artwork in
                    // `moov`, which can sit anywhere in the file, so they need the
                    // positional reader instead of a bounded header prefix.
                    if (magic != "fLaC" && magic != "ID3")
                        return@use inspectIsoBmff(resolver, uri)
                    val bytes = when (magic) {
                        "fLaC" -> readFlacMetadata(stream,header)
                        else -> {
                            val rest = readExact(stream,6)
                            val first = if (rest != null) header+rest else null
                            val count = first?.let(MetadataArtworkLyricsProbe::id3DeclaredLength)
                            if (count == null) null else {
                                val body = readExact(stream,count-10)
                                if (body == null || first == null) null else first+body
                            }
                        }
                    } ?: return@use Outcome.Blocked("La cabecera es incompatible o excede el límite")
                    when (val inspected = MetadataArtworkLyricsProbe.inspect(bytes)) {
                        is MetadataArtworkLyricsProbe.Result.Ready -> Outcome.Ready(inspected.snapshot)
                        is MetadataArtworkLyricsProbe.Result.Blocked -> Outcome.Blocked(
                            "No se puede comprobar toda la estructura: ${inspected.reason.name}")
                    }
                }
            } catch (c: CancellationException) { throw c }
            catch (_: SecurityException) { Outcome.Blocked("Permiso de lectura revocado") }
            catch (_: Exception) { Outcome.Blocked("No se pudo completar la lectura del documento") }
        }

    /**
     * ISO base media (M4A/MP4) artwork lives in `moov`, so the whole container
     * must be walked positionally. Only the tag payload is read; `mdat` is
     * skipped arithmetically by the probe.
     */
    private fun inspectIsoBmff(resolver: ContentResolver, uri: Uri): Outcome {
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: return Outcome.Blocked("El proveedor no permite leer este documento")
        return descriptor.use { parcel ->
            FileInputStream(parcel.fileDescriptor).use { stream ->
                when (val read = MetadataArtworkLyricsProbe.inspectChannel(stream.channel)) {
                    is MetadataArtworkLyricsProbe.ChannelResult.Ready ->
                        if (read.observation.snapshot.format == MetadataArtworkLyricsProbe.Format.MP4)
                            Outcome.Ready(read.observation.snapshot)
                        else Outcome.Blocked("La cabecera es incompatible o excede el límite")
                    is MetadataArtworkLyricsProbe.ChannelResult.Blocked -> Outcome.Blocked(
                        "No se puede comprobar toda la estructura: ${read.reason.name}")
                }
            }
        }
    }

    /** Never reads audio beyond the declared tag; caller limits declared sizes. */
    private fun readExact(input: InputStream, count:Int): ByteArray? {
        if (count < 0 || count > MetadataArtworkLyricsProbe.MAX_TAG_BYTES) return null
        val output=ByteArray(count);var at=0
        while(at<count) {
            val n=input.read(output,at,count-at)
            if(n<0)return null
            if(n==0) { val single=input.read();if(single<0)return null;output[at++]=single.toByte() }
            else at+=n
        }
        return output
    }
    private fun readFlacMetadata(stream: InputStream, start: ByteArray): ByteArray? {
        val output=ByteArrayOutputStream();output.write(start)
        var first=true
        while (output.size()<MetadataArtworkLyricsProbe.MAX_TAG_BYTES) {
            val header=readExact(stream,4)?:return null
            val kind=header[0].toInt() and 0x7f
            val last=(header[0].toInt() and 0x80)!=0
            val size=((header[1].toInt() and 255) shl 16) or
                ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
            if(first && (kind!=0 || size!=34))return null
            first=false
            if(size > MetadataArtworkLyricsProbe.MAX_TAG_BYTES-output.size()-4)return null
            val block=readExact(stream,size)?:return null
            output.write(header);output.write(block)
            if(last)return output.toByteArray()
        }
        return null
    }

    /** A picker URI is untrusted. Limit image bytes before decoding it. */
    suspend fun readPickedImage(resolver:ContentResolver, imageUri:Uri):ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                resolver.openInputStream(imageUri)?.use { stream ->
                    val out=ByteArrayOutputStream(); val chunk=ByteArray(32*1024)
                    while(out.size()<=8*1024*1024) {
                        val n=stream.read(chunk)
                        if(n<0)break
                        if(n>0)out.write(chunk,0,n)
                        else {val b=stream.read();if(b<0)break;out.write(b)}
                    }
                    out.toByteArray().takeIf {it.size in 16..8*1024*1024}
                }
            } catch (c:CancellationException) {throw c}
            catch (_:Exception) {null}
        }
}
