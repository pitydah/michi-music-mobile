package org.michimusic.data.metadata

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A15/A16 test-APK-only receiver for a real framework URI grant.
 *
 * The other-UID fixture owns the MediaStore item, so it cannot create a useful
 * grant with `Context.grantUriPermission`. It instead returns a PendingIntent
 * for this exported activity with `FLAG_GRANT_READ_URI_PERMISSION`. The
 * instrumentation sends that PendingIntent while it is foreground; the system
 * then delivers the read grant to the test APK UID and this activity records the
 * exact URI. The activity stays alive because the grant is tied to its task.
 */
class MetadataReadGrantTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        record(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        record(intent)
    }

    private fun record(intent: Intent?) {
        intent?.data?.let {
            grantedUri = it
            latch.countDown()
        }
    }

    companion object {
        @Volatile
        private var grantedUri: Uri? = null

        @Volatile
        private var latch = CountDownLatch(1)

        fun reset() {
            grantedUri = null
            latch = CountDownLatch(1)
        }

        fun awaitGrant(timeoutMs: Long): Uri? {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return grantedUri
        }
    }
}
