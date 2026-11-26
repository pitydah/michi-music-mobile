package org.michimusic.data.metadata

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A15/A16: drives the auxiliary TEST-ONLY fixture application
 * (`:metadata-fixture`, `org.michimusic.metadata.fixture`).
 *
 * The fixture is a separate application with its own `applicationId` and its own
 * UID. It creates REAL MediaStore audio items through the real `media` provider
 * and hands out only a framework `FLAG_GRANT_READ_URI_PERMISSION` for the exact
 * item it created. Nothing here fabricates write access: the write grant must
 * come from the real system consent flow (`MediaStore.createWriteRequest`) that
 * the production `MetadataSaveService` requests.
 *
 * The fixture APK is packaged as an androidTest asset (see
 * `:data:stageMetadataFixtureApk`) and installed through the shell only on the
 * isolated emulator used by `connectedDebugAndroidTest`. No distributed APK, and
 * no product variant, depends on this module.
 *
 * The wire contract is duplicated below on purpose: `:data` must not depend on
 * the fixture application module. `ping()` fails loudly if the two drift.
 */
internal class MetadataOtherUidFixtureClient(
    private val context: Context,
    private val device: UiDevice,
) {
    private var installed = false

    /** Installs the asset APK with the shell UID (the test app has no INSTALL_PACKAGES). */
    fun ensureInstalled() {
        if (installed) return
        val assetApk = InstrumentationRegistry.getInstrumentation().context.assets
            .open(APK_ASSET)
            .use { input ->
                val directory = context.getExternalFilesDir("metadata-fixture")
                    ?: File(context.filesDir, "metadata-fixture")
                directory.mkdirs()
                val target = File(directory, APK_ASSET)
                target.outputStream().use { output -> input.copyTo(output) }
                target
            }
        val remote = "$REMOTE_DIR/$APK_ASSET"
        device.executeShellCommand("mkdir -p $REMOTE_DIR")
        device.executeShellCommand("cp ${assetApk.absolutePath} $remote")
        val install = device.executeShellCommand("pm install -r -t $remote").trim()
        if (!install.contains("Success")) {
            // A stale build with an incompatible signature is the only expected
            // retry; a genuine failure still fails the test below.
            device.executeShellCommand("pm uninstall $FIXTURE_PACKAGE")
            val retry = device.executeShellCommand("pm install -t $remote").trim()
            check(retry.contains("Success")) { "fixture install failed: $install / $retry" }
        }
        val listed = device.executeShellCommand("pm list packages $FIXTURE_PACKAGE")
        check(listed.contains(FIXTURE_PACKAGE)) {
            "fixture package not installed after `pm install`: $listed"
        }
        installed = true
    }

    fun ping(): Bundle = command(COMMAND_PING)

    /** Creates a real, published MediaStore item owned by the fixture UID. */
    fun create(bytes: ByteArray, displayName: String): Bundle {
        val bundle = command(COMMAND_CREATE) { intent ->
            intent.putExtra(EXTRA_BYTES, bytes)
            intent.putExtra(EXTRA_DISPLAY_NAME, displayName)
            intent.putExtra(EXTRA_TARGET_PACKAGE, context.packageName)
            intent.putExtra(EXTRA_TARGET_ACTIVITY, TARGET_ACTIVITY)
        }
        deliverReadGrant(bundle)
        return bundle
    }

    /** Owner-side observation: live bytes digest and live provider row of the item. */
    fun digest(uri: Uri): Bundle = command(COMMAND_DIGEST) { intent ->
        intent.putExtra(EXTRA_URI, uri.toString())
    }

    /** Revocation of the real framework grant, performed by its actual grantor. */
    fun revokeRead(uri: Uri): Bundle = command(COMMAND_REVOKE_READ) { intent ->
        intent.putExtra(EXTRA_URI, uri.toString())
    }

    /** Re-grants the fixture's own framework read grant, for revalidation only. */
    fun grantRead(uri: Uri): Bundle {
        val bundle = command(COMMAND_GRANT_READ) { intent ->
            intent.putExtra(EXTRA_URI, uri.toString())
            intent.putExtra(EXTRA_TARGET_PACKAGE, context.packageName)
            intent.putExtra(EXTRA_TARGET_ACTIVITY, TARGET_ACTIVITY)
        }
        deliverReadGrant(bundle)
        return bundle
    }

    fun delete(uri: Uri): Bundle = command(COMMAND_DELETE) { intent ->
        intent.putExtra(EXTRA_URI, uri.toString())
    }

    private fun deliverReadGrant(bundle: Bundle) {
        @Suppress("DEPRECATION")
        val pending = bundle.getParcelable<PendingIntent>(EXTRA_READ_GRANT)
            ?: error("fixture returned no read-grant PendingIntent")
        val expected = checkNotNull(bundle.getString(EXTRA_URI))
        MetadataReadGrantTestActivity.reset()
        pending.send()
        val granted = MetadataReadGrantTestActivity.awaitGrant(COMMAND_TIMEOUT_MS)
            ?: error("read-grant activity received no URI")
        check(granted.toString() == expected) {
            "read-grant activity received $granted, expected $expected"
        }
    }

    private fun command(name: String, configure: (Intent) -> Unit = {}): Bundle {
        val intent = Intent(ACTION_COMMAND)
            .setPackage(FIXTURE_PACKAGE)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra(EXTRA_COMMAND, name)
        configure(intent)
        val latch = CountDownLatch(1)
        var result: Bundle? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, receiverIntent: Intent?) {
                result = getResultExtras(true) ?: Bundle()
                latch.countDown()
            }
        }
        context.sendOrderedBroadcast(
            intent,
            null,
            receiver,
            Handler(Looper.getMainLooper()),
            Activity.RESULT_OK,
            null,
            null,
        )
        check(latch.await(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "fixture command '$name' timed out"
        }
        val bundle = checkNotNull(result) { "fixture command '$name' returned no result" }
        val error = bundle.getString(EXTRA_ERROR)
        check(error == null) { "fixture command '$name' failed: $error" }
        return bundle
    }

    companion object {
        const val FIXTURE_PACKAGE = "org.michimusic.metadata.fixture"
        private const val APK_ASSET = "metadata-fixture-debug.apk"
        private const val REMOTE_DIR = "/data/local/tmp"
        private const val COMMAND_TIMEOUT_MS = 60_000L

        // Mirrors org.michimusic.metadata.fixture.FixtureCommandReceiver.
        const val ACTION_COMMAND = "org.michimusic.metadata.fixture.COMMAND"
        private const val EXTRA_COMMAND = "command"
        private const val COMMAND_PING = "ping"
        private const val COMMAND_CREATE = "create"
        private const val COMMAND_DIGEST = "digest"
        private const val COMMAND_GRANT_READ = "grant-read"
        private const val COMMAND_REVOKE_READ = "revoke-read"
        private const val COMMAND_DELETE = "delete"

        const val EXTRA_URI = "uri"
        const val EXTRA_DISPLAY_NAME = "display-name"
        private const val EXTRA_TARGET_PACKAGE = "target-package"
        private const val EXTRA_TARGET_ACTIVITY = "target-activity"
        private const val EXTRA_READ_GRANT = "read-grant"
        private const val TARGET_ACTIVITY =
            "org.michimusic.data.metadata.MetadataReadGrantTestActivity"
        private const val EXTRA_BYTES = "bytes"
        const val EXTRA_SIZE = "size"
        const val EXTRA_SHA256 = "sha256"
        const val EXTRA_PROVIDER_SIZE = "provider-size"
        const val EXTRA_PACKAGE_NAME = "package-name"
        const val EXTRA_UID = "uid"
        const val EXTRA_ROWS = "rows"
        private const val EXTRA_ERROR = "error"
    }
}
