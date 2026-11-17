package org.michimusic.mobile.artist

import org.michimusic.mobile.artist.artwork.ArtistArtworkCoverageState
import org.michimusic.mobile.artist.artwork.ArtistArtworkCoverageUiState
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistEnrichmentPrimaryAction
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource

/**
 * K48-R07: what a photograph slot answers before any provider detail is expanded.
 *
 * The card must say one of five things and never invent a sixth: the image exists,
 * the user chose it, it is still pending, a real cause blocks it, or the provider
 * failed temporarily. A pending slot is not an error and an error is not a pending
 * slot, so the reader can tell which action changes it.
 */
enum class ArtistPhotoAvailability {
    AVAILABLE,
    MANUALLY_CHOSEN,
    PENDING,
    BLOCKED,
    TEMPORARY_ERROR,
}

/**
 * One photograph slot as presentation data.
 *
 * [imagePath] is an absolute path to a file already downloaded and verified in the
 * app cache. It is null whenever no verified local file backs this slot, so the card
 * renders a neutral monogram instead of anything that could look like a recovered
 * photograph. A remote URL never reaches this field: artwork is never fetched from
 * composition.
 */
data class ArtistPhotoSlot(
    val slot: ArtworkSlot,
    val title: String,
    val availability: ArtistPhotoAvailability,
    val statusLabel: String,
    val detail: String? = null,
    val imagePath: String? = null,
    val attribution: String? = null,
    val license: String? = null,
) {
    val hasThumbnail: Boolean get() = imagePath != null
    val isSatisfied: Boolean
        get() = availability == ArtistPhotoAvailability.AVAILABLE ||
            availability == ArtistPhotoAvailability.MANUALLY_CHOSEN
}

/** One line of the causal diagnosis: what happened and what changes it. */
data class ArtistCoverageRow(
    val title: String,
    val detail: String? = null,
    val nextAction: String? = null,
)

/** The collapsible regions of the enrichment summary; only one is open at a time. */
/**
 * What tapping the primary action is allowed to do.
 *
 * K48-R12-01: there is deliberately no `Search` member. Opening the photo picker is a
 * local, offline operation; the provider query can only start after the user confirms
 * the disclosure inside the picker. Encoding this as a closed set means a future action
 * cannot start a network call by accident.
 */
enum class ArtistEnrichmentPanelEffect { Nothing, RevealIdentity, OpenPicker }

/** Maps every primary action to the one effect it may run. */
fun ArtistEnrichmentPrimaryAction.panelEffect(): ArtistEnrichmentPanelEffect = when (this) {
    ArtistEnrichmentPrimaryAction.ReviewIdentity,
    ArtistEnrichmentPrimaryAction.VerifyWikidataIdentity,
    -> ArtistEnrichmentPanelEffect.RevealIdentity

    ArtistEnrichmentPrimaryAction.SearchMissingPhotos,
    ArtistEnrichmentPrimaryAction.RetryProvider,
    ArtistEnrichmentPrimaryAction.ReviewLicense,
    ArtistEnrichmentPrimaryAction.ViewPhotos,
    -> ArtistEnrichmentPanelEffect.OpenPicker

    ArtistEnrichmentPrimaryAction.None -> ArtistEnrichmentPanelEffect.Nothing
}

enum class ArtistEnrichmentSection { NONE, DIAGNOSIS, IDENTITY, ADVANCED }

/** Persistence facts stated as one sentence, never as a raw enum. */
data class ArtistPersistencePresentation(
    val message: String?,
    val failed: Boolean,
)

/**
 * Everything the enrichment summary renders, and nothing else.
 *
 * Every field is a presentation value: strings, booleans, verified local paths and
 * the already-typed [ArtistEnrichmentPrimaryAction]. Services, stores, gateways and
 * coroutine scopes stay in the host, so the content composables are renderable with
 * no Koin, Room, Media3 or network.
 */
data class ArtistEnrichmentUiState(
    val artistName: String,
    val heading: String,
    val identityConfirmed: Boolean,
    val identityLabel: String,
    /** Shown only inside "Identidad y álbumes"; never in the summary. */
    val identityDetail: String?,
    val mosaic: ArtistPhotoSlot,
    val card: ArtistPhotoSlot,
    val primaryAction: ArtistEnrichmentPrimaryAction,
    val primaryActionLabel: String,
    val searchActive: Boolean,
    val searchMessage: String?,
    val coverageRows: List<ArtistCoverageRow>,
    val providerRows: List<ArtistCoverageRow>,
    val diagnosed: Boolean,
    val delete: EnrichmentDeleteState,
    val persistence: ArtistPersistencePresentation,
    val canPersist: Boolean,
    /** True when the artist has more than one verifiable album membership. */
    val hasAlbumMembership: Boolean,
    val sections: ArtistEnrichmentSection = ArtistEnrichmentSection.NONE,
    /** Number of albums the advanced deletion would unbind. */
    val albumCount: Int = 0,
) {
    val slots: List<ArtistPhotoSlot> get() = listOf(mosaic, card)

    /** True only when every slot already shows a real image. */
    val allPhotosAvailable: Boolean get() = slots.all { it.isSatisfied }

    /** The single question the summary answers first: what is still missing? */
    val missingSummary: String?
        get() = when {
            allPhotosAvailable -> null
            mosaic.isSatisfied -> "Falta la foto de ficha."
            card.isSatisfied -> "Falta la foto de mosaico."
            else -> "Faltan las dos fotografías."
        }

    val isBusy: Boolean get() = searchActive || delete is EnrichmentDeleteState.Deleting

    companion object {
        val Empty: ArtistEnrichmentUiState = from(
            artistCredit = "",
            state = ArtistEnrichmentState(),
            coverage = ArtistArtworkCoverageUiState.undiagnosed(),
            canPersist = false,
            albumCount = 0,
            section = ArtistEnrichmentSection.NONE,
        )

        /**
         * Maps the controller state plus the causal coverage into presentation data.
         *
         * Pure: no Android, no coroutine, no store. The caller decides when to read
         * the artwork cache; this function only accepts already-verified selections.
         */
        fun from(
            artistCredit: String,
            state: ArtistEnrichmentState,
            coverage: ArtistArtworkCoverageUiState,
            canPersist: Boolean,
            albumCount: Int,
            section: ArtistEnrichmentSection = ArtistEnrichmentSection.NONE,
        ): ArtistEnrichmentUiState {
            val profile = state.profile
            val confirmedMbid = profile?.mbid?.takeIf { it.isNotBlank() }
            val slots = ArtworkSlot.entries.map { slot ->
                val slotCoverage = coverage.slots.firstOrNull { it.slot == slot }
                slotPresentation(
                    slot = slot,
                    state = slotCoverage?.state ?: ArtistArtworkCoverageState.NOT_ATTEMPTED,
                    coverageLabel = slotCoverage?.statusLabel,
                    reason = slotCoverage?.reason,
                    selection = state.selections[slot],
                )
            }
            return ArtistEnrichmentUiState(
                artistName = profile?.name?.takeIf { it.isNotBlank() } ?: artistCredit,
                heading = heading(artistCredit),
                identityConfirmed = confirmedMbid != null,
                identityLabel = if (confirmedMbid != null) {
                    "Identidad musical confirmada"
                } else {
                    "Identidad musical pendiente"
                },
                identityDetail = confirmedMbid?.let { mbid ->
                    buildString {
                        append("MBID: ").append(mbid)
                        profile?.wikidataQid?.takeIf { it.isNotBlank() }
                            ?.let { append(" · Wikidata: ").append(it) }
                        state.associationGroupKey?.takeIf { it.isNotBlank() }
                            ?.let { append(" · Grupo: ").append(it) }
                    }
                },
                mosaic = slots[0],
                card = slots[1],
                primaryAction = coverage.primaryAction,
                primaryActionLabel = coverage.primaryAction.label ?: "Buscar fotos faltantes",
                searchActive = state.searchingPortraits,
                searchMessage = searchMessage(state),
                coverageRows = coverage.slots.map { slotCoverage ->
                    ArtistCoverageRow(
                        title = "${slotCoverage.slotLabel} · ${slotCoverage.statusLabel}",
                        detail = slotCoverage.reason,
                        nextAction = slotCoverage.nextAction,
                    )
                },
                providerRows = coverage.providers.map { provider ->
                    ArtistCoverageRow(
                        title = "${provider.label} · ${provider.statusLabel}",
                        detail = provider.reason,
                    )
                },
                diagnosed = coverage.diagnosed,
                delete = state.delete,
                persistence = ArtistPersistencePresentation(
                    message = persistenceMessage(state, canPersist),
                    failed = state.persistenceError != null,
                ),
                canPersist = canPersist,
                hasAlbumMembership = state.needsConfirmation ||
                    state.unboundAlbumIds.isNotEmpty() || albumCount > 1,
                sections = section,
                albumCount = albumCount,
            )
        }

        /** A credit like "'Til Tuesday" already starts with a quote. */
        internal fun heading(artistCredit: String): String =
            if (artistCredit.startsWith("'")) "Fotografías de $artistCredit"
            else "Fotografías de '$artistCredit'"

        /**
         * Availability and label are decided in one place so a card can never show a
         * word that contradicts its state: "Pendiente" only for a photograph that is
         * still missing, the real cause for one that is blocked, and "Error temporal"
         * only for a provider that failed and will be retried.
         */
        private fun slotPresentation(
            slot: ArtworkSlot,
            state: ArtistArtworkCoverageState,
            coverageLabel: String?,
            reason: String?,
            selection: ArtistArtworkSelection?,
        ): ArtistPhotoSlot {
            val availability = availabilityOf(state)
            // A path is publishable only when the slot is actually backed by a verified
            // local file. A stale selection from another identity never renders here.
            val verifiedPath = when (availability) {
                ArtistPhotoAvailability.AVAILABLE,
                ArtistPhotoAvailability.MANUALLY_CHOSEN,
                -> selection
                    ?.takeIf { it.status == ArtworkLoadStatus.READY }
                    ?.localImagePath
                    ?.takeIf { it.isNotBlank() }
                else -> null
            }
            // Presence of bytes on disk is what promotes the slot: a "Disponible"
            // label without a local file must not claim the photograph exists.
            val effective = if (verifiedPath == null &&
                (availability == ArtistPhotoAvailability.AVAILABLE ||
                    availability == ArtistPhotoAvailability.MANUALLY_CHOSEN)
            ) {
                ArtistPhotoAvailability.PENDING
            } else {
                availability
            }
            return ArtistPhotoSlot(
                slot = slot,
                title = titleOf(slot),
                availability = effective,
                statusLabel = labelOf(effective, coverageLabel),
                detail = if (effective == ArtistPhotoAvailability.BLOCKED) reason else null,
                imagePath = verifiedPath,
                attribution = selection?.attribution?.takeIf { verifiedPath != null && it.isNotBlank() },
                // K48: a catalogue photo carries no licence, and the slot must say so.
                // An empty string would render as nothing at all, which reads as if the
                // licence had simply not been formatted.
                license = when {
                    verifiedPath == null -> null
                    selection?.licenseId.isNullOrBlank() &&
                        selection?.source == ArtworkSource.THEAUDIODB -> "Licencia no declarada"
                    else -> selection?.licenseId?.takeIf { it.isNotBlank() }
                },
            )
        }

        private fun availabilityOf(state: ArtistArtworkCoverageState): ArtistPhotoAvailability =
            when (state) {
                ArtistArtworkCoverageState.READY -> ArtistPhotoAvailability.AVAILABLE
                ArtistArtworkCoverageState.MANUAL_PROTECTED -> ArtistPhotoAvailability.MANUALLY_CHOSEN
                ArtistArtworkCoverageState.NOT_ATTEMPTED,
                ArtistArtworkCoverageState.NO_MATCH,
                ArtistArtworkCoverageState.QID_MISSING,
                -> ArtistPhotoAvailability.PENDING
                ArtistArtworkCoverageState.NETWORK_FAILED -> ArtistPhotoAvailability.TEMPORARY_ERROR
                ArtistArtworkCoverageState.IDENTITY_UNVERIFIED,
                ArtistArtworkCoverageState.LICENSE_REJECTED,
                ArtistArtworkCoverageState.DOWNLOAD_FAILED,
                ArtistArtworkCoverageState.STORAGE_FAILED,
                -> ArtistPhotoAvailability.BLOCKED
            }

        private fun labelOf(
            availability: ArtistPhotoAvailability,
            coverageLabel: String?,
        ): String = when (availability) {
            ArtistPhotoAvailability.AVAILABLE -> "Disponible"
            ArtistPhotoAvailability.MANUALLY_CHOSEN -> "Elegida por ti"
            ArtistPhotoAvailability.TEMPORARY_ERROR -> "Error temporal"
            // Pending keeps the cause when the coverage states one: "Pendiente de
            // identidad Wikidata" tells the reader what to do; a bare "Pendiente" does not.
            ArtistPhotoAvailability.PENDING ->
                coverageLabel?.takeIf { it.startsWith("Pendiente") || it == "Sin coincidencias verificables" }
                    ?: "Pendiente"
            // The real cause, never a generic label: the reader must know why.
            ArtistPhotoAvailability.BLOCKED ->
                coverageLabel?.takeIf { it.isNotBlank() } ?: "Bloqueada"
        }

        private fun titleOf(slot: ArtworkSlot): String = when (slot) {
            ArtworkSlot.ARTIST_THUMB -> "Foto de mosaico"
            ArtworkSlot.ARTIST_HERO -> "Foto de ficha"
        }

        private fun searchMessage(state: ArtistEnrichmentState): String? {
            val candidates = state.pendingPortraits
            return when {
                state.searchingPortraits -> "Buscando fotografías con licencia verificada…"
                state.portraitSearchFailure != null -> state.portraitSearchFailure.userMessage()
                candidates.isNotEmpty() ->
                    "${candidates.size} fotografías con licencia verificada."
                else -> null
            }
        }

        private fun persistenceMessage(state: ArtistEnrichmentState, canPersist: Boolean): String? =
            when {
                state.persistenceError != null -> state.persistenceError
                state.refreshFailed ->
                    "No fue posible actualizar los datos externos. Se muestran los últimos datos verificados."
                state.stale -> "Datos guardados: revisión vencida. Confirma nuevamente para actualizar."
                state.profilePersisted -> "Datos guardados para este crédito y biblioteca."
                state.persisted -> "Identidad guardada para este crédito y biblioteca."
                state.profile != null && !canPersist ->
                    "Solo esta consulta. Cambia a una biblioteca individual para guardar."
                state.profile != null ->
                    "Datos consultados, pero no guardados; revisa disponibilidad del almacenamiento."
                else -> null
            }
    }
}
