package org.michimusic.data.repository

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException

/**
 * One local metadata source for MediaStore and SAF. It never writes source media.
 * Prefer seekable descriptors (late MP4/APE) and fall back to bounded stream parsing
 * for SAF providers that expose pipes or disallow seeking.
 */
internal object UriEmbeddedTags {
    /** Compatibility for existing indexing paths. Call readDetailed for truthful diagnostics. */
    fun read(context: Context, uri: Uri): EmbeddedTagFields? =
        EmbeddedTagReadPolicy.fieldsOrNull(readDetailed(context, uri))

    fun readDetailed(context: Context, uri: Uri): EmbeddedTagReadResult {
        var descriptorOpened = false
        val seekable = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.let { descriptor ->
                descriptorOpened = true
                // AutoCloseInputStream owns the descriptor; no double close.
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    SeekableEmbeddedTags.read(stream.channel)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: SecurityException) {
            return EmbeddedTagReadResult.PermissionDenied
        } catch (_: Exception) {
            null
        }
        if (seekable != null) return EmbeddedTagReadPolicy.fromFields(seekable)

        var streamOpened = false
        val streamResult = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                streamOpened = true
                EmbeddedTagFieldsReader.read(stream)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: SecurityException) {
            return EmbeddedTagReadResult.PermissionDenied
        } catch (_: Exception) {
            return EmbeddedTagReadResult.ReadFailed
        }
        if (streamResult != null) return EmbeddedTagReadPolicy.fromFields(streamResult)
        // A provider that opened a descriptor/stream but returned no parseable fields
        // is NOT proof that the audio has no tags; it may be truncated/unsupported.
        if (descriptorOpened || streamOpened) return EmbeddedTagReadResult.Unverifiable
        // An inaccessible/null descriptor AND stream is an explicit provider failure.
        return EmbeddedTagReadResult.ReadFailed
    }
}
