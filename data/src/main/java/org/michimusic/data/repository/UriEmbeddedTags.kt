package org.michimusic.data.repository

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataFieldReadReport

/**
 * One local metadata source for MediaStore and SAF. It never writes source media.
 * Prefer seekable descriptors (late MP4/APE) and fall back to bounded stream parsing
 * for SAF providers that expose pipes or disallow seeking.
 */
internal data class EditorEmbeddedTagRead(
    val legacy: EmbeddedTagReadResult,
    val report: MetadataFieldReadReport?,
)

internal object UriEmbeddedTags {
    /** Compatibility for existing indexing paths. Call readDetailed for truthful diagnostics. */
    fun read(context: Context, uri: Uri): EmbeddedTagFields? =
        EmbeddedTagReadPolicy.fieldsOrNull(readDetailed(context, uri))

    fun readDetailed(context: Context, uri: Uri): EmbeddedTagReadResult =
        readInternal(context, uri, wantEditor = false).legacy

    /** Same descriptor and same file-stamp window as the existing cold read. */
    fun readEditorDetailed(context: Context, uri: Uri): EditorEmbeddedTagRead =
        readInternal(context, uri, wantEditor = true)

    private fun readInternal(context: Context, uri: Uri, wantEditor: Boolean): EditorEmbeddedTagRead {
        var descriptorOpened = false
        var report: MetadataFieldReadReport? = null
        val seekable = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.let { descriptor ->
                descriptorOpened = true
                // AutoCloseInputStream owns the descriptor; no double close.
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    val legacy = SeekableEmbeddedTags.read(stream.channel)
                    if (wantEditor) report = MetadataColdFieldParser.observe(stream.channel)
                    legacy
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: SecurityException) {
            return EditorEmbeddedTagRead(EmbeddedTagReadResult.PermissionDenied, null)
        } catch (_: Exception) {
            null
        }
        if (seekable != null) return EditorEmbeddedTagRead(
            EmbeddedTagReadPolicy.fromFields(seekable), report)

        var streamOpened = false
        val streamResult = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                streamOpened = true
                EmbeddedTagFieldsReader.read(stream)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: SecurityException) {
            return EditorEmbeddedTagRead(EmbeddedTagReadResult.PermissionDenied, null)
        } catch (_: Exception) {
            return EditorEmbeddedTagRead(EmbeddedTagReadResult.ReadFailed, null)
        }
        if (streamResult != null) return EditorEmbeddedTagRead(
            EmbeddedTagReadPolicy.fromFields(streamResult), null)
        // A provider that opened a descriptor/stream but returned no parseable fields
        // is NOT proof that the audio has no tags; it may be truncated/unsupported.
        if (descriptorOpened || streamOpened) return EditorEmbeddedTagRead(
            EmbeddedTagReadResult.Unverifiable, null)
        // An inaccessible/null descriptor AND stream is an explicit provider failure.
        return EditorEmbeddedTagRead(EmbeddedTagReadResult.ReadFailed, null)
    }
}
