package org.michimusic.mobile.artist.artwork

/**
 * K47-ART-01: causal coverage diagnosis per artwork slot.
 *
 * The panel used to flatten every provider outcome into one shallow message, so an
 * empty slot could mean five different things and the reader could not tell which.
 * This policy turns the machine state into one of the contract states, with a
 * translated reason and the single next action that can actually change it.
 *
 * Pure Kotlin: no Android, no network, no paths. Provider reasons are sanitized so a
 * raw URL or a filesystem path can never reach the UI.
 */
enum class ArtistArtworkCoverageState {
    NOT_ATTEMPTED,
    IDENTITY_UNVERIFIED,
    QID_MISSING,
    NO_MATCH,
    LICENSE_REJECTED,
    DOWNLOAD_FAILED,
    STORAGE_FAILED,
    NETWORK_FAILED,
    READY,
    MANUAL_PROTECTED,
}

data class ArtistArtworkSlotCoverage(
    val slot: ArtworkSlot,
    val state: ArtistArtworkCoverageState,
    val slotLabel: String,
    val statusLabel: String,
    val reason: String?,
    val nextAction: String?,
)

data class ArtistArtworkProviderCoverage(
    val source: ArtworkSource,
    val label: String,
    val statusLabel: String,
    val reason: String?,
)

data class ArtistArtworkCoverageUiState(
    val slots: List<ArtistArtworkSlotCoverage>,
    val providers: List<ArtistArtworkProviderCoverage>,
    val diagnosed: Boolean,
    val primaryAction: String? = null,
) {
    companion object {
        fun undiagnosed(): ArtistArtworkCoverageUiState = ArtistArtworkCoverageUiState(
            slots = ArtworkSlot.entries.map { slot ->
                ArtistArtworkSlotCoverage(
                    slot = slot,
                    state = ArtistArtworkCoverageState.NOT_ATTEMPTED,
                    slotLabel = slotLabel(slot),
                    statusLabel = "Aún no diagnosticado",
                    reason = null,
                    nextAction = null,
                )
            },
            providers = emptyList(),
            diagnosed = false,
        )

        /**
         * @param identityConfirmed true only when the stored association carries a
         *   confirmed MusicBrainz identity. A merely typed credit is not identity.
         * @param verifiedQid the verified Wikidata QID, if any.
         */
        fun from(
            uiState: ArtistArtworkUiState,
            report: ArtworkResolutionReport?,
            identityConfirmed: Boolean,
            verifiedQid: String?,
        ): ArtistArtworkCoverageUiState {
            if (report == null) return undiagnosed()
            val slots = ArtworkSlot.entries.map { slot ->
                val state = if (slot == ArtworkSlot.ARTIST_THUMB) uiState.thumb else uiState.hero
                slotCoverage(slot, state, identityConfirmed, verifiedQid)
            }
            val providers = report.outcomes
                .filter { it.source in PROVIDER_ORDER }
                .sortedBy { PROVIDER_ORDER.indexOf(it.source) }
                .map { outcome ->
                    ArtistArtworkProviderCoverage(
                        source = outcome.source,
                        label = sourceLabel(outcome.source),
                        statusLabel = providerStatusLabel(outcome),
                        reason = sanitizeProviderReason(outcome.reason),
                    )
                }
            return ArtistArtworkCoverageUiState(
                slots = slots,
                providers = providers,
                diagnosed = true,
                primaryAction = primaryAction(slots, identityConfirmed, verifiedQid),
            )
        }

        private val PROVIDER_ORDER = listOf(
            ArtworkSource.COMMONS_P18,
            ArtworkSource.COMMONS_P180,
            ArtworkSource.WIKIPEDIA_PAGEIMAGE,
            ArtworkSource.COMMONS_CATEGORY,
            ArtworkSource.COMMONS_GALLERY,
            ArtworkSource.OPENVERSE,
        )

        private fun slotCoverage(
            slot: ArtworkSlot,
            state: ArtistArtworkSlotState,
            identityConfirmed: Boolean,
            verifiedQid: String?,
        ): ArtistArtworkSlotCoverage {
            val selection = state.selection
            if (selection?.manuallyChosen == true &&
                selection.status == ArtworkLoadStatus.READY &&
                !selection.localImagePath.isNullOrBlank()
            ) {
                return coverage(slot, ArtistArtworkCoverageState.MANUAL_PROTECTED,
                    reason = "Elegida manualmente; las búsquedas automáticas no la sustituyen.",
                    nextAction = "Cambiar foto")
            }
            val mapped = when (state.status) {
                ArtworkLoadStatus.READY ->
                    if (!selection?.localImagePath.isNullOrBlank()) {
                        ArtistArtworkCoverageState.READY
                    } else {
                        ArtistArtworkCoverageState.DOWNLOAD_FAILED
                    }
                ArtworkLoadStatus.FETCHING -> ArtistArtworkCoverageState.NOT_ATTEMPTED
                ArtworkLoadStatus.NOT_REQUESTED -> when {
                    !identityConfirmed -> ArtistArtworkCoverageState.IDENTITY_UNVERIFIED
                    !validWikidataQid(verifiedQid) -> ArtistArtworkCoverageState.QID_MISSING
                    else -> ArtistArtworkCoverageState.NO_MATCH
                }
                ArtworkLoadStatus.PARTIAL -> ArtistArtworkCoverageState.DOWNLOAD_FAILED
                ArtworkLoadStatus.NO_IMAGE -> ArtistArtworkCoverageState.NO_MATCH
                ArtworkLoadStatus.REJECTED -> ArtistArtworkCoverageState.LICENSE_REJECTED
                ArtworkLoadStatus.PROVIDER_ERROR -> ArtistArtworkCoverageState.NETWORK_FAILED
                ArtworkLoadStatus.CACHE_ERROR -> ArtistArtworkCoverageState.STORAGE_FAILED
                ArtworkLoadStatus.IDENTITY_MISMATCH -> ArtistArtworkCoverageState.IDENTITY_UNVERIFIED
            }
            val reason = if (state.status == ArtworkLoadStatus.FETCHING) {
                "La descarga de la imagen sigue en curso."
            } else {
                sanitizeProviderReason(state.reason)
            }
            return coverage(slot, mapped, reason = reason, nextAction = null)
        }

        private fun coverage(
            slot: ArtworkSlot,
            state: ArtistArtworkCoverageState,
            reason: String?,
            nextAction: String?,
        ) = ArtistArtworkSlotCoverage(
            slot = slot,
            state = state,
            slotLabel = slotLabel(slot),
            statusLabel = statusLabel(state),
            reason = reason,
            nextAction = nextAction ?: defaultNextAction(state),
        )

        fun slotLabel(slot: ArtworkSlot): String = when (slot) {
            ArtworkSlot.ARTIST_THUMB -> "Foto de mosaico"
            ArtworkSlot.ARTIST_HERO -> "Foto de ficha"
        }

        fun statusLabel(state: ArtistArtworkCoverageState): String = when (state) {
            ArtistArtworkCoverageState.NOT_ATTEMPTED -> "Aún no diagnosticado"
            ArtistArtworkCoverageState.IDENTITY_UNVERIFIED -> "Bloqueada por identidad"
            ArtistArtworkCoverageState.QID_MISSING -> "Pendiente de identidad Wikidata"
            ArtistArtworkCoverageState.NO_MATCH -> "Sin coincidencias verificables"
            ArtistArtworkCoverageState.LICENSE_REJECTED -> "Licencia no admisible"
            ArtistArtworkCoverageState.DOWNLOAD_FAILED -> "Descarga incompleta"
            ArtistArtworkCoverageState.STORAGE_FAILED -> "Error de almacenamiento"
            ArtistArtworkCoverageState.NETWORK_FAILED -> "Error temporal"
            ArtistArtworkCoverageState.READY -> "Disponible"
            ArtistArtworkCoverageState.MANUAL_PROTECTED -> "Disponible · elegida por ti"
        }

        private fun defaultNextAction(state: ArtistArtworkCoverageState): String? = when (state) {
            ArtistArtworkCoverageState.READY, ArtistArtworkCoverageState.MANUAL_PROTECTED -> null
            ArtistArtworkCoverageState.NOT_ATTEMPTED -> "Abrir diagnóstico"
            ArtistArtworkCoverageState.IDENTITY_UNVERIFIED -> "Revisar identidad"
            ArtistArtworkCoverageState.QID_MISSING -> "Verificar identidad para fotos"
            ArtistArtworkCoverageState.NO_MATCH -> "Buscar otras fotografías"
            ArtistArtworkCoverageState.LICENSE_REJECTED -> "Elegir otra fotografía"
            ArtistArtworkCoverageState.DOWNLOAD_FAILED -> "Reintentar descarga"
            ArtistArtworkCoverageState.STORAGE_FAILED -> "Liberar espacio y reintentar"
            ArtistArtworkCoverageState.NETWORK_FAILED -> "Reintentar cuando haya conexión"
        }

        fun sourceLabel(source: ArtworkSource): String = when (source) {
            ArtworkSource.COMMONS_P18 -> "Wikidata P18"
            ArtworkSource.COMMONS_P180 -> "Commons P180"
            ArtworkSource.WIKIPEDIA_PAGEIMAGE -> "Wikipedia"
            ArtworkSource.COMMONS_CATEGORY -> "Commons · categoría"
            ArtworkSource.COMMONS_GALLERY -> "Commons · galería"
            ArtworkSource.OPENVERSE -> "Openverse · búsqueda manual"
            ArtworkSource.THEAUDIODB -> "TheAudioDB · deshabilitado"
            ArtworkSource.FANART_TV -> "Fanart.tv · deshabilitado"
        }

        fun providerStatusLabel(outcome: ArtworkProviderOutcome): String = when (outcome.status) {
            ArtworkProviderStatus.AVAILABLE -> "Con resultados"
            ArtworkProviderStatus.NO_MATCH -> "Sin coincidencias"
            ArtworkProviderStatus.FAILURE -> "Error temporal"
            ArtworkProviderStatus.DISABLED -> "Deshabilitado"
            ArtworkProviderStatus.SKIPPED -> "No intentado"
        }

        /** Keeps a short, human reason and drops anything that smells like a path or URL. */
        fun sanitizeProviderReason(reason: String?): String? {
            val text = reason?.trim().orEmpty()
            if (text.isEmpty()) return null
            val lowered = text.lowercase()
            if (lowered.contains("http") || lowered.contains("content://") ||
                lowered.contains("file://") || lowered.contains("/") ||
                lowered.contains("\\")
            ) return null
            return text.take(140)
        }

        private fun primaryAction(
            slots: List<ArtistArtworkSlotCoverage>,
            identityConfirmed: Boolean,
            verifiedQid: String?,
        ): String {
            if (!identityConfirmed) return "Revisar identidad"
            if (!validWikidataQid(verifiedQid)) return "Verificar identidad para fotos"
            if (slots.any { it.state == ArtistArtworkCoverageState.NETWORK_FAILED }) {
                return "Reintentar cuando haya conexión"
            }
            if (slots.any { it.state == ArtistArtworkCoverageState.LICENSE_REJECTED }) {
                return "Elegir otra fotografía"
            }
            if (slots.all { it.state == ArtistArtworkCoverageState.READY }) return "Ver fotos"
            if (slots.any { it.state == ArtistArtworkCoverageState.NO_MATCH }) return "Buscar fotos faltantes"
            return "Buscar fotos faltantes"
        }
    }
}
