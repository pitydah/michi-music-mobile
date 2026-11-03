package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Test
import org.michimusic.link.dto.DiagnosticReport
import org.michimusic.link.dto.DiagnosticTest

class DiagnosticsRedactionR16Test {
    @Test fun headersAndTestNamesCannotLeakExplicitSecrets() {
        val exported = DiagnosticsRedactor.formatExportReport(DiagnosticReport(
            device = "device token=secretDevice", server = "server password=secretServer",
            baseUrl = "http://127.0.0.1", authStrategy = "auth=secretStrategy",
            tokenRefreshSupported = false,
            tests = listOf(DiagnosticTest(name = "token=secretName", passed = true)),
        ))
        for (value in listOf("secretDevice", "secretServer", "secretStrategy", "secretName")) {
            assertFalse(value, exported.contains(value))
        }
    }
}
