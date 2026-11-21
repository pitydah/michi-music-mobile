package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.michimusic.core.metadata.MetadataReadAccessPolicy
import android.provider.OpenableColumns
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.michimusic.core.metadata.MetadataDocumentProvider

/**
 * ME-05: Android source-READ boundary, never opens a descriptor in a write mode.
 * SAF is confined to known on-device system providers; arbitrary cloud provider
 * documents cannot accidentally enter a local-only binary preparation workflow.
 */
internal class MetadataProviderReadAdapter(private val context: Context) {
    data class Stamp(val bytes: Long, val modifiedAt: Long)
    data class Access(val uri: Uri, val provider: MetadataDocumentProvider, val stamp: Stamp)
    class Refused(val code: Code) : Exception(code.name)
    enum class Code {
        NOT_LOCAL_CONTENT, NOT_TRUSTED_PROVIDER, NOT_A_DOCUMENT,
        MISSING_OR_INVALID_STAMP, PROVIDER_UNAVAILABLE, ACCESS_DENIED,
        SOURCE_CHANGED, SHORT_READ, SOURCE_TOO_LARGE,
    }

    fun openForRead(sourceId: String, rawUri: String, expectedBytes: Long): Access {
        val uri = Uri.parse(rawUri)
        if (uri.scheme != "content") throw Refused(Code.NOT_LOCAL_CONTENT)
        val provider = when(MetadataReadAccessPolicy.classify(sourceId,uri.scheme,uri.authority)) {
            MetadataReadAccessPolicy.Kind.MEDIASTORE -> MetadataDocumentProvider.MEDIASTORE
            MetadataReadAccessPolicy.Kind.SAF_LOCAL -> MetadataDocumentProvider.SAF
            MetadataReadAccessPolicy.Kind.REJECT -> throw Refused(Code.NOT_TRUSTED_PROVIDER)
        }
        if (provider == MetadataDocumentProvider.SAF &&
            !DocumentsContract.isDocumentUri(context, uri)) throw Refused(Code.NOT_A_DOCUMENT)
        val stamp = stamp(uri,provider)
        if (stamp.bytes != expectedBytes) throw Refused(Code.SOURCE_CHANGED)
        return Access(uri, provider, stamp)
    }

    fun stamp(access: Access): Stamp = stamp(access.uri,access.provider)

    private fun stamp(uri: Uri, provider: MetadataDocumentProvider): Stamp {
        val sizeKey = if (provider == MetadataDocumentProvider.MEDIASTORE)
            MediaStore.Audio.Media.SIZE else OpenableColumns.SIZE
        val timeKey = if (provider == MetadataDocumentProvider.MEDIASTORE)
            MediaStore.Audio.Media.DATE_MODIFIED else DocumentsContract.Document.COLUMN_LAST_MODIFIED
        return try {
            context.contentResolver.query(uri, arrayOf(sizeKey,timeKey),null,null,null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) null
                else Stamp(cursor.getLong(0),cursor.getLong(1))
            }?.takeIf { it.bytes > 0 && it.modifiedAt > 0 }
                ?: throw Refused(Code.MISSING_OR_INVALID_STAMP)
        } catch (cancel: CancellationException) { throw cancel }
          catch (refused: Refused) { throw refused }
          catch (_: SecurityException) { throw Refused(Code.ACCESS_DENIED) }
          catch (_: Exception) { throw Refused(Code.PROVIDER_UNAVAILABLE) }
    }

    fun <T> readStream(access: Access, work: (InputStream) -> T): T = try {
        val input=context.contentResolver.openInputStream(access.uri)
            ?: throw Refused(Code.PROVIDER_UNAVAILABLE)
        input.use(work)
    } catch (cancel: CancellationException) { throw cancel }
      catch (refused: Refused) { throw refused }
      catch (_: SecurityException) { throw Refused(Code.ACCESS_DENIED) }
      catch (_: Exception) { throw Refused(Code.PROVIDER_UNAVAILABLE) }

    /** Optional explicit full-byte recheck. Does not infer identity from mtime alone. */
    fun verifyBytes(access: Access, expectedBytes: Long, expectedSha256: String,
        checkCancellation: () -> Unit = {}): Boolean {
        val sha=MessageDigest.getInstance("SHA-256")
        var read=0L
        readStream(access) { input ->
            val buf=ByteArray(64*1024)
            var zeroReads=0
            while (true) {
                checkCancellation()
                val n=input.read(buf)
                if (n<0) break
                if (n==0) {
                    if (++zeroReads > 8) throw Refused(Code.PROVIDER_UNAVAILABLE)
                    continue
                }
                zeroReads=0
                read+=n
                if (read>expectedBytes) throw Refused(Code.SOURCE_TOO_LARGE)
                sha.update(buf,0,n)
            }
        }
        val hex=sha.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
        if (read != expectedBytes || hex != expectedSha256) return false
        return stamp(access) == access.stamp
    }
}
