package org.michimusic.mobile.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.ui.theme.MichiSpacing

class A11yAndTokensTest {

    @Test
    fun testMinTouchTargetAdheresToMaterialAndAccessibilityGuidelines() {
        // MichiSpacing.minTouchTarget debe ser de al menos 48dp conforme a WCAG y Material Design
        assertTrue(
            "Target de toque mínimo debe ser >= 48.dp",
            MichiSpacing.minTouchTarget >= 48.dp,
        )
    }

    @Test
    fun testSpacingHierarchyConsistency() {
        assertTrue(MichiSpacing.xs < MichiSpacing.sm)
        assertTrue(MichiSpacing.sm < MichiSpacing.md)
        assertTrue(MichiSpacing.md < MichiSpacing.lg)
        assertTrue(MichiSpacing.lg < MichiSpacing.xl)
        assertTrue(MichiSpacing.xl < MichiSpacing.xxl)
    }
}
