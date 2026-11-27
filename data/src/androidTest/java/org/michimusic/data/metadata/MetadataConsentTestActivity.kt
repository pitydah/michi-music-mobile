package org.michimusic.data.metadata

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.os.ResultReceiver

/**
 * A15/A16 test-APK-only bridge to the REAL system MediaStore write-consent dialog.
 *
 * The instrumentation never fabricates a grant: it asks the production
 * `MediaStore.createWriteRequest` PendingIntent for its `IntentSender` and this
 * activity launches it with `startIntentSenderForResult`. The system dialog and
 * the user's decision stay in the platform; this bridge only forwards the
 * resulting `resultCode` (RESULT_OK / RESULT_CANCELED) to the test.
 *
 * [onCreate] is written so an Activity recreation in the middle of the dialog
 * neither relaunches the consent flow nor loses the pending result: the launched
 * flag and the receiver survive through the instance state, and the platform
 * delivers the child result to the recreated instance.
 */
class MetadataConsentTestActivity : Activity() {
    private var receiver: ResultReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = savedInstanceState?.getResultReceiver() ?: intent.resultReceiver()
        if (savedInstanceState?.getBoolean(STATE_LAUNCHED) == true) return
        val sender = intent.intentSender()
        if (sender == null) {
            recordError("MISSING_INTENT_SENDER")
            finish()
            return
        }
        @Suppress("DEPRECATION")
        startIntentSenderForResult(sender, REQUEST_CONSENT, null, 0, 0, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED, true)
        receiver?.let { outState.putParcelable(EXTRA_RECEIVER, it) }
    }

    @Deprecated("Test activity uses the platform result API deliberately.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONSENT) return
        receiver?.send(resultCode, null)
        receiver = null
        finish()
    }

    private fun recordError(code: String) {
        receiver?.send(RESULT_ERROR, Bundle().apply { putString(EXTRA_ERROR, code) })
        receiver = null
    }

    @Suppress("DEPRECATION")
    private fun Bundle.getResultReceiver(): ResultReceiver? = getParcelable(EXTRA_RECEIVER)

    @Suppress("DEPRECATION")
    private fun Intent.resultReceiver(): ResultReceiver? = getParcelableExtra(EXTRA_RECEIVER)

    @Suppress("DEPRECATION")
    private fun Intent.intentSender(): IntentSender? = getParcelableExtra(EXTRA_INTENT_SENDER)

    companion object {
        private const val REQUEST_CONSENT = 21121
        private const val STATE_LAUNCHED = "consent-launched"

        const val EXTRA_INTENT_SENDER = "intent-sender"
        const val EXTRA_RECEIVER = "result-receiver"
        const val EXTRA_ERROR = "error"
        const val RESULT_ERROR = 2
    }
}
