package org.michimusic.metadata.fixture

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import java.security.MessageDigest

/**
 * A15/A16 test-only command channel.
 *
 * The fixture runs as its own application/UID. It creates REAL MediaStore audio
 * items through the real `media` provider, so the app under test observes a
 * source it does not own. The only permission the fixture hands out is a
 * framework `FLAG_GRANT_READ_URI_PERMISSION` for the exact item it created; the
 * write access must then come from the system consent flow
 * (`MediaStore.createWriteRequest`) driven by the caller, never from here.
 *
 * Commands are ordered broadcasts: the caller receives a result [Bundle] with
 * exactly what this process observed (uri, provider size, sha256, uid).
 */
class FixtureCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMAND) return
        val result = Bundle()
        try {
            when (intent.getStringExtra(EXTRA_COMMAND)) {
                COMMAND_PING -> ping(context, result)
                COMMAND_CREATE -> create(context, intent, result)
                COMMAND_DIGEST -> digest(context, intent, result)
                COMMAND_GRANT_READ -> grantRead(context, intent, result)
                COMMAND_REVOKE_READ -> revokeRead(context, intent, result)
                COMMAND_DELETE -> delete(context, intent, result)
                else -> result.putString(EXTRA_ERROR, "UNKNOWN_COMMAND")
            }
        } catch (error: Exception) {
            result.putString(
                EXTRA_ERROR,
                error.javaClass.simpleName + ": " + error.message,
            )
        }
        setResultExtras(result)
    }

    private fun ping(context: Context, result: Bundle) {
        result.putString(EXTRA_PACKAGE_NAME, context.packageName)
        result.putInt(EXTRA_UID, Process.myUid())
    }

    /**
     * Creates a real MediaStore audio item owned by the fixture UID, publishes
     * it and grants read access to [EXTRA_TARGET_PACKAGE]. The bytes are always
     * supplied by the caller so the app under test controls the pre-state hash.
     */
    private fun create(context: Context, intent: Intent, result: Bundle) {
        val bytes = intent.getByteArrayExtra(EXTRA_BYTES)
            ?: throw IllegalArgumentException("missing $EXTRA_BYTES")
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
            ?: throw IllegalArgumentException("missing $EXTRA_DISPLAY_NAME")
        val targetPackage = grantTarget(intent)
        val targetActivity = grantActivity(intent)
        require(displayName.endsWith(".mp3")) { "fixture only publishes MP3 items" }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        ) ?: throw IllegalStateException("MediaStore refused the fixture insert")
        try {
            val descriptor = resolver.openFileDescriptor(uri, "rwt")
                ?: throw IllegalStateException("fixture could not open its own item")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            result.putParcelable(
                EXTRA_READ_GRANT,
                readGrant(context, uri, targetPackage, targetActivity),
            )
            result.putString(EXTRA_URI, uri.toString())
            result.putString(EXTRA_SHA256, sha256(bytes))
            result.putLong(EXTRA_SIZE, bytes.size.toLong())
            result.putInt(EXTRA_UID, Process.myUid())
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    /**
     * Owner-side observation of the live bytes and the live provider row. This
     * is the independent check that a refused save did not mutate the source,
     * even when the app under test has lost its grant.
     */
    private fun digest(context: Context, intent: Intent, result: Bundle) {
        val uri = uriExtra(intent)
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("fixture could not read its own item")
        result.putString(EXTRA_URI, uri.toString())
        result.putString(EXTRA_SHA256, sha256(bytes))
        result.putLong(EXTRA_SIZE, bytes.size.toLong())
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                result.putString(EXTRA_DISPLAY_NAME, cursor.getString(0))
                result.putLong(EXTRA_PROVIDER_SIZE, cursor.getLong(1))
            }
        }
    }

    private fun grantRead(context: Context, intent: Intent, result: Bundle) {
        val uri = uriExtra(intent)
        result.putParcelable(
            EXTRA_READ_GRANT,
            readGrant(context, uri, grantTarget(intent), grantActivity(intent)),
        )
        result.putString(EXTRA_URI, uri.toString())
    }

    /**
     * Revokes the framework read grant this fixture previously made. The real
     * system then denies the app under test; the fixture keeps its own owner
     * access, which is what makes the byte evidence possible.
     */
    private fun revokeRead(context: Context, intent: Intent, result: Bundle) {
        val uri = uriExtra(intent)
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        result.putString(EXTRA_URI, uri.toString())
    }

    private fun delete(context: Context, intent: Intent, result: Bundle) {
        val uri = uriExtra(intent)
        result.putInt(EXTRA_ROWS, context.contentResolver.delete(uri, null, null))
    }

    private fun uriExtra(intent: Intent): Uri = Uri.parse(
        intent.getStringExtra(EXTRA_URI)
            ?: throw IllegalArgumentException("missing $EXTRA_URI"),
    )

    private fun grantTarget(intent: Intent): String {
        val target = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
            ?: throw IllegalArgumentException("missing $EXTRA_TARGET_PACKAGE")
        require(target.startsWith("org.michimusic.")) {
            "fixture grants only to Michi packages"
        }
        return target
    }

    private fun grantActivity(intent: Intent): String {
        val target = intent.getStringExtra(EXTRA_TARGET_ACTIVITY)
            ?: throw IllegalArgumentException("missing $EXTRA_TARGET_ACTIVITY")
        require(target.startsWith("org.michimusic.")) {
            "fixture grants only to Michi components"
        }
        return target
    }

    private fun readGrant(
        context: Context,
        uri: Uri,
        targetPackage: String,
        targetActivity: String,
    ): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(targetPackage, targetActivity)
            data = uri
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        const val ACTION_COMMAND = "org.michimusic.metadata.fixture.COMMAND"

        const val EXTRA_COMMAND = "command"
        const val COMMAND_PING = "ping"
        const val COMMAND_CREATE = "create"
        const val COMMAND_DIGEST = "digest"
        const val COMMAND_GRANT_READ = "grant-read"
        const val COMMAND_REVOKE_READ = "revoke-read"
        const val COMMAND_DELETE = "delete"

        const val EXTRA_URI = "uri"
        const val EXTRA_DISPLAY_NAME = "display-name"
        const val EXTRA_TARGET_PACKAGE = "target-package"
        const val EXTRA_TARGET_ACTIVITY = "target-activity"
        const val EXTRA_READ_GRANT = "read-grant"
        const val EXTRA_BYTES = "bytes"
        const val EXTRA_SIZE = "size"
        const val EXTRA_SHA256 = "sha256"
        const val EXTRA_PROVIDER_SIZE = "provider-size"
        const val EXTRA_PACKAGE_NAME = "package-name"
        const val EXTRA_UID = "uid"
        const val EXTRA_ROWS = "rows"
        const val EXTRA_ERROR = "error"

        private const val RELATIVE_PATH = "Music/MichiOtherUid/"
    }
}
