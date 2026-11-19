package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure contract tests. Android Compose must still prove that labels are visible. */
class UI8SettingsTruthPolicyTest {
    @Test fun automatedLibraryFeaturesRemainExplicitlyUnavailable() {
        val text = settingsTruthLine(SettingsTruthArea.LIBRARY_AUTOMATION)
        assertTrue(text.contains("no disponibles"))
        assertTrue(text.contains("Escaneo al iniciar"))
        assertTrue(text.contains("carátulas"))
    }

    @Test fun preferredUsbIsNotMisrepresentedAsMeasuredDacRate() {
        val text = settingsTruthLine(SettingsTruthArea.OUTPUT)
        assertTrue(text.contains("no confirma"))
        assertTrue(text.contains("física"))
        assertTrue(text.contains("DAC"))
    }

    @Test fun processingModeDoesNotImplyAppliedEffect() {
        val text = settingsTruthLine(SettingsTruthArea.PROCESSING)
        assertTrue(text.contains("solicitado"))
        assertTrue(text.contains("puede limitar"))
    }

    @Test fun everyTruthAreaHasDistinctNonBlankUserFacingText() {
        val labels = SettingsTruthArea.entries.map(::settingsTruthLine)
        assertEquals(SettingsTruthArea.entries.size, labels.distinct().size)
        assertTrue(labels.all { it.isNotBlank() })
    }
}
