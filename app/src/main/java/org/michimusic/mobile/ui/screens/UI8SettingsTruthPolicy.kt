package org.michimusic.mobile.ui.screens

/** UI8-DEV-12: safety/availability facts are visible WITHOUT opening a disclosure. */
internal enum class SettingsTruthArea { LIBRARY_AUTOMATION, OUTPUT, PROCESSING }

internal fun settingsTruthLine(area: SettingsTruthArea): String = when (area) {
    SettingsTruthArea.LIBRARY_AUTOMATION ->
        "Escaneo al iniciar y carátulas automáticas: todavía no disponibles."
    SettingsTruthArea.OUTPUT ->
        "La preferencia USB no confirma la frecuencia física del DAC."
    SettingsTruthArea.PROCESSING ->
        "Modo solicitado: la ruta activa puede limitar los efectos aplicados."
}
