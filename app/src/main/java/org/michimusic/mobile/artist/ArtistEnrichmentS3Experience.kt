package org.michimusic.mobile.artist

import org.michimusic.mobile.artist.artwork.ArtistArtworkCoverageState

/**
 * ARTFIX2-S3-FINAL — UI truth and action guidance only. No network, file, identity,
 * profile, license, or Room authority lives here. The controller owns the facts;
 * this policy describes them without conflating observed, saved, and verified.
 */
internal enum class ArtistS3JourneyPhase {
    IDENTIFY, READ_ONLY, LOOKUP_CONSENT, LOOKUP_RUNNING, REVIEW_CANDIDATES,
    PREVIEW, REPLACEMENT_REVIEW, SAVING, EDIT_CROP, NO_RESULTS, READY_TO_SEARCH,
    PHOTOS_READY, PROVIDER_PROBLEM, STORAGE_PROBLEM, OPERATION_RESULT,
}

internal data class ArtistS3JourneyFacts(
    val identityConfirmed: Boolean,
    val canPersist: Boolean,
    val mosaicSaved: Boolean,
    val heroSaved: Boolean,
    val lookupRunning: Boolean = false,
    val providerProblem: Boolean = false,
    val storageProblem: Boolean = false,
    val diagnosed: Boolean = false,
)

internal data class ArtistS3JourneyCopy(
    val phase: ArtistS3JourneyPhase,
    val step: String,
    val title: String,
    val explanation: String,
    val accessibilityText: String = "$step. $title. $explanation",
)

/** Fail closed before considering availability, provider or image results. */
internal fun artistS3Overview(facts: ArtistS3JourneyFacts): ArtistS3JourneyCopy = when {
    !facts.identityConfirmed -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.IDENTIFY, "Paso 1 de 3 · Identidad",
        "Primero, identifica al artista",
        "Revisa el artista y los álbumes asociados. No se cambiarán etiquetas ni fotografías al abrir esta revisión.",
    )
    !facts.canPersist -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.READ_ONLY, "Ámbito de biblioteca",
        "Solo consulta: elige una biblioteca",
        "El ámbito actual no permite guardar cambios. Selecciona una biblioteca con pertenencias verificadas.",
    )
    facts.lookupRunning -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.LOOKUP_RUNNING, "Paso 2 de 3 · Búsqueda",
        "Buscando fotografías solicitadas",
        "Todavía no se ha guardado ninguna candidata nueva. Puedes conservar las fotografías existentes.",
    )
    facts.storageProblem -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.STORAGE_PROBLEM, "Paso 3 de 3 · Guardado",
        "No se pudo completar un cambio guardado",
        "Revisa el error mostrado por almacenamiento. No se atribuye éxito a las fotografías anteriores ni se repite la escritura automáticamente.",
    )
    facts.mosaicSaved && facts.heroSaved -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.PHOTOS_READY, "Paso 3 de 3 · Fotografías",
        "Ambos destinos tienen imágenes guardadas",
        "Mosaico y ficha son independientes. Puedes revisarlos o cambiarlos sin modificar los metadatos de audio.",
    )
    facts.providerProblem -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.PROVIDER_PROBLEM, "Paso 2 de 3 · Fuentes",
        "No fue posible completar la búsqueda",
        "Las imágenes previamente guardadas se conservan. Revisa el detalle del proveedor y reintenta por decisión propia.",
    )
    else -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.READY_TO_SEARCH, "Paso 2 de 3 · Fotografías",
        "Revisa el mosaico y la ficha por separado",
        when {
            facts.mosaicSaved -> "La foto de mosaico está guardada; falta completar o revisar la foto de ficha."
            facts.heroSaved -> "La foto de ficha está guardada; falta completar o revisar la foto de mosaico."
            facts.diagnosed -> "El diagnóstico no guarda imágenes. Abre Fotografías y confirma cada candidata antes de guardarla."
            else -> "Ninguna búsqueda se inicia al abrir esta pantalla. Primero revisa las opciones de fotografías."
        },
    )
}

internal data class ArtistS3PickerFacts(
    val canSearch: Boolean,
    val consentOpen: Boolean,
    val searching: Boolean,
    val busy: Boolean,
    val totalCandidates: Int,
    val visibleCandidates: Int,
    val previewOpen: Boolean,
    val replacementRequired: Boolean,
    val replacementAcknowledged: Boolean,
    val identityConfirmedForPreview: Boolean,
    val destinationSelected: Boolean,
    val focusEditorOpen: Boolean,
    val messagePresent: Boolean,
)

/** Pure user guidance; NEVER a substitute for mayCommitArtworkPreview/CAS. */
internal fun artistS3Picker(facts: ArtistS3PickerFacts): ArtistS3JourneyCopy = when {
    facts.busy -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.SAVING, "Paso 3 de 3 · Operación",
        "Comprobando el cambio solicitado",
        "No repitas la acción. Espera el resultado explícito antes de considerar el cambio guardado.",
    )
    facts.focusEditorOpen -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.EDIT_CROP, "Encuadre",
        "Previsualiza antes de guardar",
        "Mover el encuadre no modifica la fotografía persistida. Guardar aplica solo al destino seleccionado.",
    )
    facts.consentOpen -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.LOOKUP_CONSENT, "Paso 2 de 3 · Permiso de búsqueda",
        "Decide antes de consultar proveedores",
        "Solo Confirmar búsqueda permite enviar los identificadores indicados. Cancelar no consulta la red.",
    )
    facts.searching -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.LOOKUP_RUNNING, "Paso 2 de 3 · Búsqueda",
        "Buscando candidatas",
        "Los resultados no están guardados ni confirman identidad por sí mismos.",
    )
    facts.previewOpen && facts.replacementRequired && !facts.replacementAcknowledged -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.REPLACEMENT_REVIEW, "Paso 3 de 3 · Reemplazo",
        "Revisa la fotografía que vas a sustituir",
        "Si el destino contiene una elección manual, debes aceptar expresamente el reemplazo. La otra fotografía permanece intacta.",
    )
    facts.previewOpen -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.PREVIEW, "Paso 3 de 3 · Vista previa",
        "Confirma la candidata y el destino",
        "Selecciona mosaico, ficha o ambos; revisa autoría y licencia; confirma identidad y pulsa Guardar."
    )
    facts.totalCandidates > 0 -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.REVIEW_CANDIDATES, "Paso 2 de 3 · Galería",
        "Fotografías encontradas, todavía no guardadas",
        "Mostrando ${facts.visibleCandidates.coerceIn(0, facts.totalCandidates)} de ${facts.totalCandidates}. " +
            "Abrir una miniatura no guarda la imagen ni modifica la identidad.",
    )
    facts.messagePresent -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.OPERATION_RESULT, "Resultado de la operación",
        "Revisa el mensaje de la operación",
        "El mensaje puede corresponder a búsqueda, guardado o encuadre. Comprueba los destinos guardados antes de repetir la acción.",
    )
    facts.canSearch -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.READY_TO_SEARCH, "Paso 2 de 3 · Fuentes",
        "Busca solamente cuando lo decidas",
        "Verás el consentimiento antes de consultar proveedores. Los resultados serán candidatos, no fotografías guardadas.",
    )
    else -> ArtistS3JourneyCopy(
        ArtistS3JourneyPhase.READ_ONLY, "Paso 2 de 3 · Fuentes",
        "Búsqueda no disponible",
        "La revisión local no concede permisos de red. Comprueba primero la identidad y el ámbito de biblioteca.",
    )
}

internal enum class ArtistS3SlotRole(val label: String, val purpose: String, val accessibility: String) {
    MOSAIC("Mosaico", "Imagen pequeña y normalmente cuadrada para la biblioteca.",
        "Foto del mosaico. Independiente de la foto de ficha."),
    HERO("Ficha", "Imagen de cabecera; el encuadre se puede ajustar sin alterar mosaico.",
        "Foto de la ficha. Independiente de la foto del mosaico."),
}

internal data class ArtistS3PreviewRequirements(
    val candidatePresent: Boolean,
    val destinationSelected: Boolean,
    val identityConfirmed: Boolean,
    val replacementRequired: Boolean,
    val replacementAccepted: Boolean,
    val operationBusy: Boolean,
) {
    /** For communication only; write authorization belongs to existing guarded host. */
    val unmet: List<String> get() = buildList {
        if (!candidatePresent) add("Elige una fotografía de esta búsqueda.")
        if (!destinationSelected) add("Selecciona mosaico, ficha o ambos.")
        if (!identityConfirmed) add("Confirma la identidad para esta fotografía.")
        if (replacementRequired && !replacementAccepted) add("Autoriza el reemplazo manual de los destinos seleccionados.")
        if (operationBusy) add("La operación actual todavía no termina.")
    }
    val description: String get() = if (unmet.isEmpty())
        "Selección revisada. Guardar es la única acción que puede modificar los destinos." else
        unmet.joinToString(" ")
}

/** UI breakpoints, not a claim of device certification. */
internal enum class ArtistS3LayoutMode { SINGLE_COLUMN, TWO_COLUMNS }
internal fun artistS3Layout(widthDp: Int, fontScale: Float): ArtistS3LayoutMode =
    if (widthDp >= 368 && fontScale.isFinite() && fontScale >= 0.5f && fontScale < 1.6f)
        ArtistS3LayoutMode.TWO_COLUMNS else ArtistS3LayoutMode.SINGLE_COLUMN

internal const val ARTIST_S3_MIN_TOUCH_TARGET_DP = 48

/** Friendly public providers, never internal enum or network transport names. */
internal fun artistS3ProviderLabel(sourceName: String): String = when (sourceName) {
    "COMMONS_P18", "COMMONS_P180", "COMMONS_CATEGORY", "COMMONS_GALLERY" -> "Wikimedia Commons"
    "WIKIPEDIA_PAGEIMAGE" -> "Wikipedia"
    "OPENVERSE" -> "Openverse"
    "THEAUDIODB" -> "TheAudioDB"
    "FANART_TV" -> "fanart.tv"
    else -> "Fuente no identificada"
}

/** A discovered profile is not a persisted artist association. */
internal fun artistS3IdentityIsConfirmed(
    profileMbid: String?, persistedMbid: String?, persisted: Boolean,
    needsConfirmation: Boolean,
): Boolean {
    if (!persisted || needsConfirmation) return false
    val profile = canonicalArtistMbid(profileMbid) ?: return false
    val stored = canonicalArtistMbid(persistedMbid) ?: return false
    return profile == stored
}

/** Typed recovery for a missing slot. A UI action can only open a local task. */
enum class ArtistS3RecoveryRoute { REVIEW_IDENTITY, OPEN_PICKER }
data class ArtistS3Recovery(val explanation: String, val actionLabel: String, val route: ArtistS3RecoveryRoute)

internal fun artistS3Recovery(
    state: ArtistArtworkCoverageState,
): ArtistS3Recovery? {
    fun picker(reason: String, action: String) = ArtistS3Recovery(reason, action, ArtistS3RecoveryRoute.OPEN_PICKER)
    return when (state) {
        ArtistArtworkCoverageState.READY,
        ArtistArtworkCoverageState.MANUAL_PROTECTED -> null
        ArtistArtworkCoverageState.IDENTITY_UNVERIFIED -> ArtistS3Recovery(
            "No se puede atribuir esta foto a una identidad confirmada.", "Revisar identidad", ArtistS3RecoveryRoute.REVIEW_IDENTITY)
        ArtistArtworkCoverageState.QID_MISSING -> picker(
            "Falta un vínculo Wikidata verificado para las fuentes automáticas. Puedes revisar la identidad o buscar una foto manual con consentimiento.", "Buscar foto manual")
        ArtistArtworkCoverageState.NO_MATCH -> picker(
            "La búsqueda anterior no produjo coincidencias verificables. No se rellenará por semejanza de nombres.", "Buscar otras fotos")
        ArtistArtworkCoverageState.LICENSE_REJECTED -> picker(
            "La licencia de la candidata no permite usarla con esta política. Elige otra y revisa su licencia.", "Revisar otra licencia")
        ArtistArtworkCoverageState.DOWNLOAD_FAILED -> picker(
            "No hay una copia local verificable. Revisa la candidata de nuevo antes de guardar.", "Revisar descarga")
        ArtistArtworkCoverageState.STORAGE_FAILED -> picker(
            "No se confirmó el guardado. Comprueba espacio y almacenamiento antes de volver a intentarlo.", "Revisar tras liberar espacio")
        ArtistArtworkCoverageState.NETWORK_FAILED -> picker(
            "El proveedor no respondió correctamente. Cuando haya conexión, abre la búsqueda y confirma de nuevo.", "Revisar búsqueda")
        ArtistArtworkCoverageState.NOT_ATTEMPTED -> picker(
            "No hay resultado de búsqueda para este destino.", "Elegir fotografía")
    }
}
