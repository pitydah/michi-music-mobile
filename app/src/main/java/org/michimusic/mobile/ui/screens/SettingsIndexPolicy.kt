package org.michimusic.mobile.ui.screens

import java.text.Normalizer
import java.util.Locale

/** Stable route IDs: AUDIO_QUALITY remains a compatibility route for existing callers. */
enum class SettingsSection {
    ROOT, AUDIO_QUALITY, PLAYBACK, PROCESSING, AUDIO_OUTPUT, LIBRARY_STORAGE,
    ECOSYSTEM, APPEARANCE, ACCESSIBILITY, PRIVACY, SYSTEM, ABOUT,
}

enum class SettingsIndexGroup(val caption: String) {
    LISTENING("ESCUCHA"),
    COLLECTION("COLECCIÓN Y ECOSISTEMA"),
    PREFERENCES("PREFERENCIAS"),
    SUPPORT("SOPORTE"),
}

data class SettingsIndexDestination(
    val section: SettingsSection,
    val group: SettingsIndexGroup,
    val title: String,
    val summary: String,
    val keywords: String = "",
)

/** No synthetic controls: every destination maps to a working in-app route. */
val settingsIndexDestinations: List<SettingsIndexDestination> = listOf(
    SettingsIndexDestination(SettingsSection.PLAYBACK, SettingsIndexGroup.LISTENING,
        "Reproducción", "ReplayGain y preamplificación", "volumen normalizacion pista album"),
    SettingsIndexDestination(SettingsSection.PROCESSING, SettingsIndexGroup.LISTENING,
        "Procesamiento y ecualización", "Modo de audio, EQ y perfiles", "dsp ecualizador auriculares peq efectos"),
    SettingsIndexDestination(SettingsSection.AUDIO_OUTPUT, SettingsIndexGroup.LISTENING,
        "Salidas de audio", "Ruta, DAC USB y mezclador", "dispositivos usb salida altavoz bluetooth"),
    SettingsIndexDestination(SettingsSection.LIBRARY_STORAGE, SettingsIndexGroup.COLLECTION,
        "Biblioteca y almacenamiento", "Carpetas, permisos y canciones ocultas", "music saf escanear archivos espacio"),
    SettingsIndexDestination(SettingsSection.ECOSYSTEM, SettingsIndexGroup.COLLECTION,
        "Ecosistema Michi", "Michi Link, dispositivos y sincronización", "micro server music stream aircat sync"),
    SettingsIndexDestination(SettingsSection.APPEARANCE, SettingsIndexGroup.PREFERENCES,
        "Apariencia Aero", "Materiales, CoverFlow y barra", "cristalino microtexturas seeker"),
    SettingsIndexDestination(SettingsSection.ACCESSIBILITY, SettingsIndexGroup.PREFERENCES,
        "Accesibilidad", "Contraste, movimiento y háptica", "visual vibracion animaciones"),
    SettingsIndexDestination(SettingsSection.PRIVACY, SettingsIndexGroup.PREFERENCES,
        "Privacidad y datos", "Historial e identidad local", "guardar reproduccion privacidad"),
    SettingsIndexDestination(SettingsSection.SYSTEM, SettingsIndexGroup.SUPPORT,
        "Sistema y diagnóstico", "Herramientas y asistente inicial", "onboarding configurar errores"),
    SettingsIndexDestination(SettingsSection.ABOUT, SettingsIndexGroup.SUPPORT,
        "Acerca de Michi", "Versión, autor y código fuente", "github pitydah desarrollo version"),
)

private fun String.settingsSearchKey(): String =
    Normalizer.normalize(lowercase(Locale.ROOT).trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")

/** Local-only index. Does not search music files, device identities or network services. */
fun filterSettingsDestinations(query: String): List<SettingsIndexDestination> {
    val term = query.settingsSearchKey()
    if (term.isEmpty()) return settingsIndexDestinations
    return settingsIndexDestinations.filter { item ->
        "${item.title} ${item.summary} ${item.keywords}".settingsSearchKey().contains(term)
    }
}

/** Pure route labels ensure SettingsScreen does not need to know domain state. */
fun settingsDestinationTitle(section: SettingsSection): String = when (section) {
    SettingsSection.ROOT -> "Ajustes"
    SettingsSection.PLAYBACK, SettingsSection.AUDIO_QUALITY -> "Reproducción"
    SettingsSection.PROCESSING -> "Procesamiento y EQ"
    SettingsSection.AUDIO_OUTPUT -> "Salidas de audio"
    SettingsSection.LIBRARY_STORAGE -> "Biblioteca"
    SettingsSection.ECOSYSTEM -> "Ecosistema Michi"
    SettingsSection.APPEARANCE -> "Apariencia Aero"
    SettingsSection.ACCESSIBILITY -> "Accesibilidad"
    SettingsSection.PRIVACY -> "Privacidad y datos"
    SettingsSection.SYSTEM -> "Sistema y diagnóstico"
    SettingsSection.ABOUT -> "Acerca de Michi"
}
