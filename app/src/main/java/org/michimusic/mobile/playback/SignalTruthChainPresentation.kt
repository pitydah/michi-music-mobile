package org.michimusic.mobile.playback

import org.michimusic.core.models.SignalEvidenceType

/** Presentation-only grouping. Never adds edges or turns preferences into facts. */
internal data class SignalChainSection(
    val branchId: String,
    val stages: List<SignalStageUi>,
)

internal object SignalTruthChainPresentation {
    /** Keep causal order; an interleaved branch must never be silently merged. */
    fun sections(stages: List<SignalStageUi>): List<SignalChainSection> {
        if (stages.isEmpty()) return emptyList()
        val result = mutableListOf<SignalChainSection>()
        for (stage in stages) {
            val last = result.lastOrNull()
            if (last != null && last.branchId == stage.branchId) {
                result[result.lastIndex] = last.copy(stages = last.stages + stage)
            } else {
                result += SignalChainSection(stage.branchId, listOf(stage))
            }
        }
        return result
    }

    fun branchTitle(branchId: String): String = when (branchId) {
        "rtp" -> "Rama Michi Stream · envío RTP"
        "local" -> "Rama local · AudioTrack"
        else -> "Ruta de señal"
    }

    fun label(stage: SignalStageUi): String = when (stage.stableId.substringAfterLast(':')) {
        "source" -> "Archivo original"
        "decode" -> "Decodificación PCM"
        "audio-track" -> "Salida Android · AudioTrack"
        "physical" -> "Dispositivo final"
        else -> stage.label
    }

    /** A missing observation stays unknown; only the phrasing is condensed. */
    fun summary(stage: SignalStageUi): String = when {
        stage.evidenceType == SignalEvidenceType.UNKNOWN &&
            stage.stableId.endsWith(":decode") && stage.summary == "No observado" ->
            "Sin medición PCM de esta pista"
        stage.evidenceType == SignalEvidenceType.UNKNOWN &&
            stage.stableId.endsWith(":physical") && stage.summary == "No verificado" ->
            "La salida física no está verificada"
        else -> stage.summary
    }

    fun actionLabel(action: SignalStageAction): String? = when (action) {
        SignalStageAction.NONE -> null
        SignalStageAction.OPEN_EQ -> "Editar ecualizador"
        SignalStageAction.REPLAY_GAIN -> "Ajustar ReplayGain"
        SignalStageAction.HEADPHONE_PROFILE -> "Elegir perfil"
        SignalStageAction.USB_MIXER -> "Ajustar mezclador USB"
    }
}
