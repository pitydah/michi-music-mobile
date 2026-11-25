package org.michimusic.data.metadata

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.DocumentsContract

/**
 * Test-APK-only bridge to the real system DocumentsUI. It persists exactly the
 * grant returned by Android; it neither fabricates a permission nor exposes a
 * provider of its own.
 */
class MetadataSafGrantTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                    )
                    putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents",
                            "primary:Download/MichiMetadataSafE2E",
                        ),
                    )
                },
                REQUEST_TREE,
            )
        }
    }

    @Deprecated("Test activity uses the platform result API deliberately.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TREE) return
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            val flags = data.flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            runCatching {
                contentResolver.takePersistableUriPermission(uri, flags)
            }.onSuccess {
                receiver()?.send(RESULT_OK, Bundle().apply {
                    putString(EXTRA_URI, uri.toString())
                })
            }.onFailure {
                recordError(it.javaClass.simpleName)
            }
        } else {
            recordError("SAF_TREE_NOT_GRANTED")
        }
        finish()
    }

    private fun recordError(code: String) {
        receiver()?.send(RESULT_ERROR, Bundle().apply {
            putString(EXTRA_ERROR, code)
        })
    }

    @Suppress("DEPRECATION")
    private fun receiver(): ResultReceiver? =
        intent.getParcelableExtra(EXTRA_RECEIVER)

    companion object {
        private const val REQUEST_TREE = 21120
        const val EXTRA_URI = "tree-uri"
        const val EXTRA_ERROR = "error"
        const val EXTRA_RECEIVER = "result-receiver"
        const val RESULT_ERROR = 2
    }
}
