package org.michimusic.mobile.playback

/** A successful UDP send is observable only on the mobile sender.
 * It is NOT a ReceiverLite delivery acknowledgement or physical DAC observation.
 * Session identity must come from the live receiver session, never cached metrics.
 */
internal object SignalTruthRtpSenderReceipt {
    fun isValid(destination: SignalDestination, sessionId: String?, packetsSent: Long, senderActive: Boolean): Boolean =
        destination == SignalDestination.MICHI_STREAM && senderActive &&
            !sessionId.isNullOrBlank() && packetsSent > 0L
}
