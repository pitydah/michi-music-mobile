package org.michimusic.core.intelligence

/**
 * MI-03P: deliberately restrictive model of a FUTURE provider boundary.
 * It does not connect to Gemini. It cannot accept song names, file paths,
 * listening history, playlist contents, user text, or identifiers as inputs.
 * Any eventual HTTP adapter MUST enforce this gate at the actual outbound edge.
 */
object MichiExternalPrivacyGate {
    enum class PublicTopic { WHAT_IS_FLAC, WHAT_IS_REPLAYGAIN, WHAT_IS_HI_RES }
    enum class Denial { PROVIDER_OFF, NOT_USER_INITIATED, NO_ONE_SHOT_APPROVAL }

    data class Request(
        val topic: PublicTopic,
        val providerEnabled: Boolean,
        val userInitiated: Boolean,
        val oneShotApproval: Boolean,
    )

    sealed interface Decision {
        data class Denied(val why: Denial) : Decision
        class Approved internal constructor(val fixedPublicPrompt: String) : Decision
    }

    fun evaluate(request: Request): Decision {
        if (!request.providerEnabled) return Decision.Denied(Denial.PROVIDER_OFF)
        if (!request.userInitiated) return Decision.Denied(Denial.NOT_USER_INITIATED)
        if (!request.oneShotApproval) return Decision.Denied(Denial.NO_ONE_SHOT_APPROVAL)
        // No free-form payload: all possible outbound text is fixed at compile time.
        val prompt = when (request.topic) {
            PublicTopic.WHAT_IS_FLAC -> "Explain the public audio format FLAC in general terms."
            PublicTopic.WHAT_IS_REPLAYGAIN -> "Explain the public audio concept ReplayGain in general terms."
            PublicTopic.WHAT_IS_HI_RES -> "Explain the public audio concept high-resolution audio in general terms."
        }
        return Decision.Approved(prompt)
    }
}
