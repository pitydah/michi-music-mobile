package org.michimusic.data.local

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.WorkerThread

class MediaQueryDispatcher(
    private val contentResolver: ContentResolver,
    uri: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
) {

    private var projection: Array<String>? = null
    private var selection: String? = null
    private var selectionArgs: MutableList<String>? = null
    private var sortOrder: String? = null
    private var queryUri: Uri = uri

    fun withColumns(vararg cols: String): MediaQueryDispatcher =
        apply { projection = arrayOf(*cols) }

    fun setProjection(proj: Array<String>?): MediaQueryDispatcher =
        apply { projection = proj }

    fun setSelection(sel: String?): MediaQueryDispatcher =
        apply { selection = sel }

    fun addSelection(sel: String?, mode: String = "AND"): MediaQueryDispatcher =
        apply {
            if (!sel.isNullOrBlank()) {
                selection = if (selection == null) sel else "$selection $mode $sel"
            }
        }

    fun addArgs(vararg args: String): MediaQueryDispatcher =
        apply {
            if (args.isNotEmpty()) {
                val list = selectionArgs ?: mutableListOf()
                list.addAll(args)
                selectionArgs = list
            }
        }

    fun setSortOrder(order: String?): MediaQueryDispatcher =
        apply { sortOrder = order }

    @WorkerThread
    fun dispatch(): Cursor? = try {
        contentResolver.query(
            queryUri,
            projection,
            selection,
            selectionArgs?.toTypedArray(),
            sortOrder,
        )
    } catch (e: SecurityException) {
        null
    }
}
