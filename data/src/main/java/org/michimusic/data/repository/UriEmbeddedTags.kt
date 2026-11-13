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
    fun read(context: Context, uri: Uri): EmbeddedTagFields? {
        val seekable = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.let { descriptor ->
                // AutoCloseInputStream owns the descriptor; do not close the FD twice.
                // Some DocumentsProviders return a pipe; size/readAt then fail safely.
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    SeekableEmbeddedTags.read(stream.channel)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        if (seekable != null) return seekable
        return try {
            context.contentResolver.openInputStream(uri)?.let(EmbeddedTagFieldsReader::read)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }
}
